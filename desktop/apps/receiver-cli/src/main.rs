use std::net::SocketAddr;

use anyhow::{Context, Result};
use clap::Parser;
use receiver_control_http::{serve, ControlState};
use receiver_core::{ReceiverService, StaticCapabilityProvider};
use receiver_gstreamer::{probe_capabilities, GStreamerReceiver};
use receiver_platform_linux::LinuxVideoSinkFactory;
use tracing::info;
use tracing_subscriber::EnvFilter;

mod cli;
mod output;
mod shutdown;

use cli::Cli;

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    init_logging(&cli.log_level)?;
    let config = cli.receiver_config();

    if cli.print_pipeline {
        output::print_pipeline(&config);
        return Ok(());
    }

    let capabilities = probe_capabilities(
        config.media_port,
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

    let sink_factory = LinuxVideoSinkFactory::new(&config.device)
        .context("validate Linux v4l2loopback output device")?;
    let provider = StaticCapabilityProvider::new(capabilities.clone());
    let media_receiver =
        GStreamerReceiver::new(sink_factory).context("initialise the GStreamer receiver")?;
    let service =
        ReceiverService::new(config.clone(), Box::new(provider), Box::new(media_receiver))
            .context("create receiver service")?;
    let state = ControlState::new(service);
    let control_addr = SocketAddr::new(config.listen_addr, config.control_port);
    output::print_banner(&config, &capabilities);
    info!(
        control_port = config.control_port,
        media_port = config.media_port,
        device = %config.device.display(),
        "receiver ready"
    );

    let shutdown_signal = async {
        if let Err(error) = shutdown::wait_for_shutdown().await {
            tracing::error!(%error, "shutdown signal listener failed");
        }
    };
    serve(state.clone(), control_addr, shutdown_signal)
        .await
        .context("run receiver control server")?;
    state.shutdown().context("stop active receiver session")?;
    Ok(())
}

fn init_logging(level: &str) -> Result<()> {
    let filter =
        EnvFilter::try_new(level).with_context(|| format!("invalid log level: {level}"))?;
    tracing_subscriber::fmt().with_env_filter(filter).try_init().ok();
    Ok(())
}
