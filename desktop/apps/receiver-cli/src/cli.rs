use std::{net::IpAddr, path::PathBuf};

use clap::{Parser, ValueEnum};
use receiver_core::{LatencyConfig, OutputFormat, ReceiverConfig};

#[derive(Debug, Parser)]
#[command(name = "mobile-webcam-receiver", about = "Low-latency Android webcam receiver")]
pub struct Cli {
    #[arg(long, default_value = "0.0.0.0")]
    pub listen: IpAddr,

    #[arg(long, default_value_t = 5001)]
    pub control_port: u16,

    #[arg(long, default_value_t = 5000)]
    pub media_port: u16,

    #[arg(long, default_value = "/dev/video10")]
    pub device: PathBuf,

    #[arg(long, value_enum, default_value_t = OutputFormatArg::Auto)]
    pub output_format: OutputFormatArg,

    #[arg(long, default_value_t = 0)]
    pub demux_latency_ms: u32,

    #[arg(long, default_value_t = 2)]
    pub queue_frames: u32,

    #[arg(long, default_value = "info")]
    pub log_level: String,

    #[arg(long)]
    pub print_capabilities: bool,

    #[arg(long)]
    pub print_pipeline: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum)]
pub enum OutputFormatArg {
    Auto,
    Yuy2,
    Nv12,
    I420,
}

impl OutputFormatArg {
    const fn to_core(self) -> OutputFormat {
        match self {
            Self::Auto => OutputFormat::Auto,
            Self::Yuy2 => OutputFormat::Yuy2,
            Self::Nv12 => OutputFormat::Nv12,
            Self::I420 => OutputFormat::I420,
        }
    }
}

impl Cli {
    pub fn receiver_config(&self) -> ReceiverConfig {
        ReceiverConfig {
            listen_addr: self.listen,
            control_port: self.control_port,
            media_port: self.media_port,
            device: self.device.clone(),
            output_format: self.output_format.to_core(),
            latency: LatencyConfig {
                demux_latency_ms: self.demux_latency_ms,
                output_queue_frames: self.queue_frames,
            },
            udp_timeout_ms: 2_000,
            session_timeout_grace_ms: 30_000,
        }
    }
}
