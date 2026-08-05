use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;

use receiver_protocol::VideoProfile;

pub const DEFAULT_LISTEN_ADDRESS: IpAddr = IpAddr::V4(Ipv4Addr::UNSPECIFIED);
pub const DEFAULT_CONTROL_PORT: u16 = 5_001;
pub const DEFAULT_DEMUX_LATENCY_MS: u32 = 0;
pub const DEFAULT_OUTPUT_QUEUE_FRAMES: u32 = 2;
pub const DEFAULT_VIDEO_DEVICE: &str = "/dev/video10";
pub const DEFAULT_SRT_LISTEN_PORT: u16 = 5_000;
pub const DEFAULT_SRT_LATENCY_MS: u32 = 120;
pub const DEFAULT_SRT_INACTIVITY_TIMEOUT_MS: u64 = 2_000;
pub const DEFAULT_SRT_CONNECT_DEADLINE_MS: u64 = 10_000;
pub const DEFAULT_SRT_RECONNECT_GRACE_MS: u64 = 30_000;
pub const DEFAULT_OUTPUT_WIDTH: u32 = 1_280;
pub const DEFAULT_OUTPUT_HEIGHT: u32 = 720;
pub const DEFAULT_OUTPUT_FPS: u32 = 30;
pub const DEFAULT_H264_BITRATE_BPS: u32 = 4_000_000;
pub const DEFAULT_H265_BITRATE_BPS: u32 = 7_000_000;
pub const DEFAULT_RECEIVER_NAME: &str = "Mobile Webcam";

pub const DEFAULT_OUTPUT_PROFILE: VideoProfile = VideoProfile {
    width: DEFAULT_OUTPUT_WIDTH,
    height: DEFAULT_OUTPUT_HEIGHT,
    fps: DEFAULT_OUTPUT_FPS,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SrtConfig {
    pub listen_port: u16,
    pub latency_ms: u32,
    pub inactivity_timeout_ms: u64,
    pub connect_deadline_ms: u64,
    pub reconnect_grace_ms: u64,
}

impl Default for SrtConfig {
    fn default() -> Self {
        Self {
            listen_port: DEFAULT_SRT_LISTEN_PORT,
            latency_ms: DEFAULT_SRT_LATENCY_MS,
            inactivity_timeout_ms: DEFAULT_SRT_INACTIVITY_TIMEOUT_MS,
            connect_deadline_ms: DEFAULT_SRT_CONNECT_DEADLINE_MS,
            reconnect_grace_ms: DEFAULT_SRT_RECONNECT_GRACE_MS,
        }
    }
}

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
    pub device: PathBuf,
    pub output_format: OutputFormat,
    pub latency: LatencyConfig,
    pub output_profile: VideoProfile,
    pub h264_bitrate_bps: u32,
    pub h265_bitrate_bps: u32,
    pub srt: SrtConfig,
    pub advertised_host: Option<String>,
    pub receiver_name: String,
    pub control_token: Option<String>,
}

impl Default for ReceiverConfig {
    fn default() -> Self {
        Self {
            listen_addr: DEFAULT_LISTEN_ADDRESS,
            control_port: DEFAULT_CONTROL_PORT,
            device: PathBuf::from(DEFAULT_VIDEO_DEVICE),
            output_format: OutputFormat::Auto,
            latency: LatencyConfig::default(),
            output_profile: DEFAULT_OUTPUT_PROFILE,
            h264_bitrate_bps: DEFAULT_H264_BITRATE_BPS,
            h265_bitrate_bps: DEFAULT_H265_BITRATE_BPS,
            srt: SrtConfig::default(),
            advertised_host: None,
            receiver_name: DEFAULT_RECEIVER_NAME.to_owned(),
            control_token: None,
        }
    }
}
