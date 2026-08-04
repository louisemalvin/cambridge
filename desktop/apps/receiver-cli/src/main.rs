use std::{
    net::SocketAddr,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::RecvTimeoutError,
        Arc,
    },
    thread,
    time::Duration,
};

use anyhow::{Context, Result};
use clap::Parser;
use receiver_control_http::{serve, ControlState};
use receiver_core::{ReceiverService, StaticCapabilityProvider};
use receiver_discovery::{DiscoveryConfig, ReceiverDiscoveryPublisher};
use receiver_gstreamer::{probe_capabilities, GStreamerReceiver};
use receiver_platform_linux::{
    resolve_v4l2loopback_device, PersistentVideoSinkFactory, V4l2LoopbackDemandMonitor,
    DEMAND_POLL_INTERVAL, PERSISTENT_PRODUCER_BASELINE,
};
use tracing::info;
use tracing_subscriber::EnvFilter;

mod cli;
mod http;
mod inspect;
mod output;
mod report;
mod shutdown;

use cli::Cli;

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    init_logging(&cli.log_level, cli.json_logs)?;
    if cli.inspect_session.is_some() {
        inspect::run(&cli)?;
        return Ok(());
    }
    let config = if cli.print_pipeline || cli.print_capabilities {
        cli.receiver_config(
            cli.device.clone().unwrap_or_else(|| PathBuf::from("<auto-detected-v4l2loopback>")),
        )
    } else {
        let device = resolve_v4l2loopback_device(cli.device.as_deref()).with_context(|| {
            if cli.device.is_some() {
                "validate the configured virtual-camera device"
            } else {
                "find a v4l2loopback device; run ./scripts/linux/install-receiver.sh once"
            }
        })?;
        cli.receiver_config(device.path)
    };

    if cli.print_pipeline {
        output::print_pipeline(&config);
        return Ok(());
    }

    let sink_factory = PersistentVideoSinkFactory::new(&config.device)
        .context("validate Linux v4l2loopback output device")?;
    let persistent_output = sink_factory.persistent_output();
    let capabilities = probe_capabilities(
        config.device.to_string_lossy().into_owned(),
        vec![
            receiver_protocol::PixelFormat::Yuy2,
            receiver_protocol::PixelFormat::Nv12,
            receiver_protocol::PixelFormat::I420,
        ],
    )
    .context("probe GStreamer receiver capabilities")?;
    if cli.print_capabilities {
        return output::print_capabilities(&capabilities);
    }

    let (mut demand_monitor, demand_events) =
        V4l2LoopbackDemandMonitor::start(&config.device, PERSISTENT_PRODUCER_BASELINE)
            .context("start v4l2loopback client-usage demand monitor")?;
    let provider = StaticCapabilityProvider::new(capabilities.clone());
    let media_receiver =
        GStreamerReceiver::new(sink_factory).context("initialise the GStreamer receiver")?;
    let service =
        ReceiverService::new(config.clone(), Box::new(provider), Box::new(media_receiver))
            .context("create receiver service")?;
    let state = ControlState::new(service);
    let _discovery = start_discovery(&config);
    let control_addr = SocketAddr::new(config.listen_addr, config.control_port);
    output::print_banner(&config, &capabilities);
    info!(
        control_port = config.control_port,
        srt_listen_port = config.srt.listen_port,
        device = %config.device.display(),
        "receiver ready"
    );

    let demand_relay_shutdown = Arc::new(AtomicBool::new(false));
    let relay_shutdown = demand_relay_shutdown.clone();
    let relay_state = state.clone();
    let relay_output = persistent_output.clone();
    let demand_relay = thread::Builder::new()
        .name("mobile-webcam-demand-relay".to_owned())
        .spawn(move || {
            while !relay_shutdown.load(Ordering::Relaxed) {
                match demand_events.recv_timeout(DEMAND_RELAY_TIMEOUT) {
                    Ok(event) => {
                        if !event.demand.is_active() {
                            let _ = relay_output.set_standby();
                        }
                        relay_state.publish_demand(event.demand);
                    }
                    Err(RecvTimeoutError::Timeout) => {}
                    Err(RecvTimeoutError::Disconnected) => break,
                }
            }
        })
        .context("start webcam demand relay thread")?;

    let shutdown_signal = async {
        if let Err(error) = shutdown::wait_for_shutdown().await {
            tracing::error!(%error, "shutdown signal listener failed");
        }
    };
    let serve_result = serve(state.clone(), control_addr, shutdown_signal).await;
    demand_relay_shutdown.store(true, Ordering::Relaxed);
    demand_monitor.stop();
    let _ = demand_relay.join();
    serve_result.context("run receiver control server")?;
    state.shutdown().context("stop active receiver session")?;
    persistent_output
        .stop()
        .map_err(|error| anyhow::anyhow!(error))
        .context("stop persistent virtual-camera output")?;
    Ok(())
}

const DEMAND_RELAY_TIMEOUT: Duration = DEMAND_POLL_INTERVAL;

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

fn init_logging(level: &str, json_logs: bool) -> Result<()> {
    let filter =
        EnvFilter::try_new(level).with_context(|| format!("invalid log level: {level}"))?;
    if json_logs {
        tracing_subscriber::fmt().json().with_env_filter(filter).try_init().ok();
    } else {
        tracing_subscriber::fmt().with_env_filter(filter).try_init().ok();
    }
    Ok(())
}
