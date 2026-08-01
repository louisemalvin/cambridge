use receiver_protocol::{PixelFormat, VideoCodec, VideoProfile};
use uuid::Uuid;

use crate::{LatencyConfig, ReceiverState};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaSessionConfig {
    pub session_id: Uuid,
    pub codec: VideoCodec,
    pub profile: VideoProfile,
    pub bitrate_bps: u32,
    pub media_port: u16,
    pub output_format: PixelFormat,
    pub latency: LatencyConfig,
    pub udp_timeout_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceiverSession {
    pub id: Uuid,
    pub codec: VideoCodec,
    pub profile: VideoProfile,
    pub bitrate_bps: u32,
    pub output_format: PixelFormat,
    pub state: ReceiverState,
    pub decoder: Option<String>,
    pub received_bitrate_bps: u32,
    pub timeout_count: u64,
}

impl ReceiverSession {
    pub fn new(config: &MediaSessionConfig) -> Self {
        Self {
            id: config.session_id,
            codec: config.codec,
            profile: config.profile.clone(),
            bitrate_bps: config.bitrate_bps,
            output_format: config.output_format,
            state: ReceiverState::Prepared,
            decoder: None,
            received_bitrate_bps: 0,
            timeout_count: 0,
        }
    }
}
