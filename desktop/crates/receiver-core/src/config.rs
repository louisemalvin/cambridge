use std::net::IpAddr;
use std::path::PathBuf;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutputFormat {
    Auto,
    Yuy2,
    Nv12,
    I420,
}

impl OutputFormat {
    pub const fn protocol_format(self) -> Option<receiver_protocol::PixelFormat> {
        match self {
            Self::Auto => None,
            Self::Yuy2 => Some(receiver_protocol::PixelFormat::Yuy2),
            Self::Nv12 => Some(receiver_protocol::PixelFormat::Nv12),
            Self::I420 => Some(receiver_protocol::PixelFormat::I420),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LatencyConfig {
    pub demux_latency_ms: u32,
    pub output_queue_frames: u32,
}

impl Default for LatencyConfig {
    fn default() -> Self {
        Self { demux_latency_ms: 0, output_queue_frames: 2 }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceiverConfig {
    pub listen_addr: IpAddr,
    pub control_port: u16,
    pub media_port: u16,
    pub device: PathBuf,
    pub output_format: OutputFormat,
    pub latency: LatencyConfig,
    pub udp_timeout_ms: u64,
}

impl Default for ReceiverConfig {
    fn default() -> Self {
        Self {
            listen_addr: IpAddr::from([0, 0, 0, 0]),
            control_port: 5001,
            media_port: 5000,
            device: PathBuf::from("/dev/video10"),
            output_format: OutputFormat::Auto,
            latency: LatencyConfig::default(),
            udp_timeout_ms: 2_000,
        }
    }
}
