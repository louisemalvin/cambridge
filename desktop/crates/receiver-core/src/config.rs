use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;

pub const DEFAULT_LISTEN_ADDRESS: IpAddr = IpAddr::V4(Ipv4Addr::UNSPECIFIED);
pub const DEFAULT_CONTROL_PORT: u16 = 5_001;
pub const PORT_UNASSIGNED: u16 = 0;
pub const DEFAULT_DEMUX_LATENCY_MS: u32 = 0;
pub const DEFAULT_OUTPUT_QUEUE_FRAMES: u32 = 2;
pub const DEFAULT_UDP_TIMEOUT_MS: u64 = 2_000;
pub const DEFAULT_SESSION_TIMEOUT_GRACE_MS: u64 = 30_000;
pub const DEFAULT_VIDEO_DEVICE: &str = "/dev/video10";
pub const MEDIA_PORT_RANGE_START: u16 = 50_000;
pub const MEDIA_PORT_RANGE_END: u16 = 50_099;
pub const MEDIA_PORT_RANGE_SIZE: u16 = MEDIA_PORT_RANGE_END - MEDIA_PORT_RANGE_START + 1;

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
        Self {
            demux_latency_ms: DEFAULT_DEMUX_LATENCY_MS,
            output_queue_frames: DEFAULT_OUTPUT_QUEUE_FRAMES,
        }
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
    pub session_timeout_grace_ms: u64,
}

impl Default for ReceiverConfig {
    fn default() -> Self {
        Self {
            listen_addr: DEFAULT_LISTEN_ADDRESS,
            control_port: DEFAULT_CONTROL_PORT,
            media_port: PORT_UNASSIGNED,
            device: PathBuf::from(DEFAULT_VIDEO_DEVICE),
            output_format: OutputFormat::Auto,
            latency: LatencyConfig::default(),
            udp_timeout_ms: DEFAULT_UDP_TIMEOUT_MS,
            session_timeout_grace_ms: DEFAULT_SESSION_TIMEOUT_GRACE_MS,
        }
    }
}
