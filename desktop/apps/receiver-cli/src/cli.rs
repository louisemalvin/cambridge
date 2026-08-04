use std::{net::IpAddr, path::PathBuf};

use clap::{Parser, ValueEnum};
use receiver_core::{
    LatencyConfig, OutputFormat, ReceiverConfig, SrtConfig, DEFAULT_ADVERTISED_HOST,
    DEFAULT_CONTROL_PORT, DEFAULT_DEMUX_LATENCY_MS, DEFAULT_LISTEN_ADDRESS,
    DEFAULT_OUTPUT_QUEUE_FRAMES, DEFAULT_RECEIVER_NAME, DEFAULT_SRT_CONNECT_DEADLINE_MS,
    DEFAULT_SRT_INACTIVITY_TIMEOUT_MS, DEFAULT_SRT_LATENCY_MS, DEFAULT_SRT_LISTEN_PORT,
    DEFAULT_SRT_RECONNECT_GRACE_MS,
};

#[derive(Debug, Parser)]
#[command(name = "mobile-webcam-receiver", about = "Low-latency Android webcam receiver")]
pub struct Cli {
    #[arg(long, default_value_t = DEFAULT_LISTEN_ADDRESS)]
    pub listen: IpAddr,

    #[arg(long, default_value_t = DEFAULT_CONTROL_PORT)]
    pub control_port: u16,

    #[arg(long, default_value_t = DEFAULT_SRT_LISTEN_PORT)]
    pub srt_port: u16,

    #[arg(long, default_value_t = DEFAULT_SRT_LATENCY_MS)]
    pub srt_latency_ms: u32,

    #[arg(long, default_value_t = DEFAULT_SRT_INACTIVITY_TIMEOUT_MS)]
    pub srt_inactivity_timeout_ms: u64,

    #[arg(long, default_value_t = DEFAULT_SRT_CONNECT_DEADLINE_MS)]
    pub srt_connect_deadline_ms: u64,

    #[arg(long, default_value_t = DEFAULT_SRT_RECONNECT_GRACE_MS)]
    pub srt_reconnect_grace_ms: u64,

    #[arg(long, default_value = DEFAULT_ADVERTISED_HOST)]
    pub advertise_host: String,

    #[arg(long, default_value = DEFAULT_RECEIVER_NAME)]
    pub receiver_name: String,

    /// Optional bearer token for authenticated v2 sender control.
    #[arg(long, env = "MOBILE_WEBCAM_CONTROL_TOKEN")]
    pub control_token: Option<String>,

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

    /// Poll a live receiver session and build a bounded performance report.
    #[arg(long)]
    pub inspect_session: Option<String>,

    #[arg(long, default_value = DEFAULT_INSPECTOR_RECEIVER_URL)]
    pub receiver_url: String,

    #[arg(long, default_value_t = DEFAULT_INSPECTION_DURATION_SECONDS)]
    pub inspect_duration_seconds: u64,

    #[arg(long, default_value_t = DEFAULT_INSPECTION_POLL_MILLIS)]
    pub inspect_poll_ms: u64,

    #[arg(long, default_value = "unspecified")]
    pub network: String,

    #[arg(long, default_value = "unspecified")]
    pub consumer: String,

    #[arg(long)]
    pub run_id: Option<String>,

    #[arg(long)]
    pub sender_log: Option<PathBuf>,

    #[arg(long)]
    pub receiver_log: Option<PathBuf>,

    #[arg(long)]
    pub output: Option<PathBuf>,

    #[arg(long)]
    pub json_logs: bool,
}

pub const DEFAULT_INSPECTION_DURATION_SECONDS: u64 = 60;
pub const DEFAULT_INSPECTION_POLL_MILLIS: u64 = 1_000;
pub const DEFAULT_INSPECTOR_RECEIVER_URL: &str = "http://127.0.0.1:5001";

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
            srt: SrtConfig {
                listen_port: self.srt_port,
                latency_ms: self.srt_latency_ms,
                inactivity_timeout_ms: self.srt_inactivity_timeout_ms,
                connect_deadline_ms: self.srt_connect_deadline_ms,
                reconnect_grace_ms: self.srt_reconnect_grace_ms,
            },
            advertised_host: self.advertise_host.clone(),
            receiver_name: self.receiver_name.clone(),
            control_token: self.control_token.clone(),
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
        let config = cli.receiver_config(PathBuf::from("/dev/video10"));
        assert_eq!(config.srt.listen_port, DEFAULT_SRT_LISTEN_PORT);
    }

    #[test]
    fn explicit_device_is_preserved() {
        let cli = Cli::parse_from(["mobile-webcam-receiver", "--device", "/dev/video42"]);

        assert_eq!(cli.device.as_deref(), Some(Path::new("/dev/video42")));
    }
}
