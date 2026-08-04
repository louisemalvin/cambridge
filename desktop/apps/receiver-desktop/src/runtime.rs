use std::{
    net::SocketAddr,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{sync_channel, RecvTimeoutError, SyncSender},
        Arc,
    },
    thread,
    thread::JoinHandle,
    time::Duration,
};

use anyhow::{anyhow, Context, Result};
use receiver_control_http::{serve_listener, ControlState};
use receiver_core::{ReceiverService, StaticCapabilityProvider};
use receiver_discovery::{DiscoveryConfig, ReceiverDiscoveryPublisher};
use receiver_gstreamer::{probe_capabilities, GStreamerReceiver};
use receiver_platform_linux::{
    resolve_v4l2loopback_device, PersistentVirtualCameraOutput, V4l2LoopbackDemandMonitor,
    DEMAND_POLL_INTERVAL,
};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tracing::error;

use crate::{cli::Cli, output::DesktopSinkFactory, preview::PreviewStore};

const READY_CHANNEL_CAPACITY: usize = 1;
const DEMAND_RELAY_TIMEOUT: Duration = DEMAND_POLL_INTERVAL;

pub struct ReceiverRuntime {
    state: ControlState,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
    persistent_output: PersistentVirtualCameraOutput,
    discovery: Option<ReceiverDiscoveryPublisher>,
    demand_monitor: Option<V4l2LoopbackDemandMonitor>,
    demand_relay_shutdown: Arc<AtomicBool>,
    demand_relay_thread: Option<JoinHandle<()>>,
}

impl ReceiverRuntime {
    #[allow(clippy::too_many_lines)]
    pub fn start(cli: &Cli) -> Result<(Self, PreviewStore)> {
        let device = resolve_v4l2loopback_device(cli.device.as_deref()).with_context(|| {
            if cli.device.is_some() {
                "validate the configured virtual-camera device"
            } else {
                "find a v4l2loopback device; run ./scripts/linux/install-receiver.sh once"
            }
        })?;
        let config = cli.receiver_config(device.path);
        let sink_factory = DesktopSinkFactory::new(&config.device)
            .context("validate Linux v4l2loopback output device")?;
        let preview_store = sink_factory.preview_store();
        let persistent_output = sink_factory.persistent_output();
        let (demand_monitor, demand_events) =
            V4l2LoopbackDemandMonitor::start(&config.device, 0)
                .context("start v4l2loopback client-usage demand monitor")?;
        let capabilities = probe_capabilities(
            config.device.to_string_lossy().into_owned(),
            vec![
                receiver_protocol::PixelFormat::Yuy2,
                receiver_protocol::PixelFormat::Nv12,
                receiver_protocol::PixelFormat::I420,
            ],
        )
        .context("probe GStreamer receiver capabilities")?;
        PreviewStore::validate().context("validate desktop preview GStreamer elements")?;
        let media_receiver =
            GStreamerReceiver::new(sink_factory).context("initialise the GStreamer receiver")?;
        let provider = StaticCapabilityProvider::new(capabilities);
        let service =
            ReceiverService::new(config.clone(), Box::new(provider), Box::new(media_receiver))
                .context("create receiver service")?;
        let state = ControlState::new(service);
        let control_addr = SocketAddr::new(config.listen_addr, config.control_port);
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let (ready_tx, ready_rx) = sync_channel(READY_CHANNEL_CAPACITY);
        let server_state = state.clone();
        let thread = thread::Builder::new()
            .name("mobile-webcam-control".to_owned())
            .spawn(move || {
                let runtime = match tokio::runtime::Runtime::new() {
                    Ok(runtime) => runtime,
                    Err(error) => {
                        error!(%error, "failed to create control runtime");
                        send_ready(&ready_tx, Err(error.to_string()));
                        return;
                    }
                };
                runtime.block_on(async move {
                    let listener = match TcpListener::bind(control_addr).await {
                        Ok(listener) => listener,
                        Err(error) => {
                            send_ready(&ready_tx, Err(error.to_string()));
                            return;
                        }
                    };
                    send_ready(&ready_tx, Ok(()));
                    let shutdown = async {
                        let _ = shutdown_rx.await;
                    };
                    if let Err(error) = serve_listener(listener, server_state, shutdown).await {
                        error!(%error, "control server stopped with an error");
                    }
                });
            })
            .context("start control server thread")?;
        match ready_rx.recv().context("wait for control server startup")? {
            Ok(()) => {}
            Err(error) => {
                let _ = thread.join();
                return Err(anyhow!("control server could not listen on {control_addr}: {error}"));
            }
        }
        let discovery = start_discovery(&config);
        let mut receiver = Self {
            state,
            shutdown: Some(shutdown_tx),
            thread: Some(thread),
            persistent_output,
            discovery,
            demand_monitor: Some(demand_monitor),
            demand_relay_shutdown: Arc::new(AtomicBool::new(false)),
            demand_relay_thread: None,
        };
        let relay_shutdown = receiver.demand_relay_shutdown.clone();
        let relay_output = receiver.persistent_output.clone();
        receiver.demand_relay_thread = Some(
            thread::Builder::new()
                .name("mobile-webcam-demand-relay".to_owned())
                .spawn(move || {
                    while !relay_shutdown.load(Ordering::Relaxed) {
                        match demand_events.recv_timeout(DEMAND_RELAY_TIMEOUT) {
                            Ok(event) => {
                                if !event.demand.is_active() {
                                    let _ = relay_output.set_standby();
                                }
                            }
                            Err(RecvTimeoutError::Timeout) => {}
                            Err(RecvTimeoutError::Disconnected) => break,
                        }
                    }
                })
                .context("start webcam demand relay thread")?,
        );
        Ok((receiver, preview_store))
    }

    fn stop(&mut self) {
        self.discovery.take();
        self.demand_relay_shutdown.store(true, Ordering::Relaxed);
        if let Some(thread) = self.demand_relay_thread.take() {
            let _ = thread.join();
        }
        self.demand_monitor.take();
        let _ = self.state.shutdown();
        let _ = self.persistent_output.set_standby();
        let _ = self.persistent_output.stop();
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn start_discovery(config: &receiver_core::ReceiverConfig) -> Option<ReceiverDiscoveryPublisher> {
    let discovery_config = DiscoveryConfig {
        display_name: config.receiver_name.clone(),
        control_port: config.control_port,
        authentication_required: config.control_token.is_some(),
    };
    match ReceiverDiscoveryPublisher::start(&discovery_config) {
        Ok(publisher) => Some(publisher),
        Err(error) => {
            tracing::warn!(%error, "receiver discovery is unavailable; manual origin remains available");
            None
        }
    }
}

fn send_ready(sender: &SyncSender<Result<(), String>>, result: Result<(), String>) {
    let _ = sender.send(result);
}

impl Drop for ReceiverRuntime {
    fn drop(&mut self) {
        self.stop();
    }
}
