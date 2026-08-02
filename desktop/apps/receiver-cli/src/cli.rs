use std::{net::IpAddr, path::PathBuf};

use clap::{Parser, ValueEnum};
use receiver_core::{
    LatencyConfig, OutputFormat, ReceiverConfig, DEFAULT_CONTROL_PORT, DEFAULT_DEMUX_LATENCY_MS,
    DEFAULT_LISTEN_ADDRESS, DEFAULT_OUTPUT_QUEUE_FRAMES,
};

#[derive(Debug, Parser)]
#[command(name = "mobile-webcam-receiver", about = "Low-latency Android webcam receiver")]
pub struct Cli {
    #[arg(long, default_value_t = DEFAULT_LISTEN_ADDRESS)]
    pub listen: IpAddr,

    #[arg(long, default_value_t = DEFAULT_CONTROL_PORT)]
    pub control_port: u16,

    /// Use a specific v4l2loopback device. If omitted, the first loopback
    /// device is selected automatically.
    #[arg(long)]
    pub device: Option<PathBuf>,

    #[arg(long, value_enum, default_value_t = OutputFormatArg::Auto)]
    pub output_format: OutputFormatArg,

    #[arg(long, default_value_t = DEFAULT_DEMUX_LATENCY_MS)]
    pub demux_latency_ms: u32,

    #[arg(long, default_value_t = DEFAULT_OUTPUT_QUEUE_FRAMES)]
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
    pub fn receiver_config(&self, device: PathBuf) -> ReceiverConfig {
        ReceiverConfig {
            listen_addr: self.listen,
            control_port: self.control_port,
            device,
            output_format: self.output_format.to_core(),
            latency: LatencyConfig {
                demux_latency_ms: self.demux_latency_ms,
                output_queue_frames: self.queue_frames,
            },
            ..ReceiverConfig::default()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    #[test]
    fn device_is_automatic_by_default() {
        let cli = Cli::parse_from(["mobile-webcam-receiver"]);

        assert!(cli.device.is_none());
        assert_eq!(cli.receiver_config(PathBuf::from("/dev/video10")).media_port, 0);
    }

    #[test]
    fn explicit_device_is_preserved() {
        let cli = Cli::parse_from(["mobile-webcam-receiver", "--device", "/dev/video42"]);

        assert_eq!(cli.device.as_deref(), Some(Path::new("/dev/video42")));
    }
}
