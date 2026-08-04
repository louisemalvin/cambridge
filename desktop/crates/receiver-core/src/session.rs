use receiver_protocol::{
    PixelFormat, SessionStateV2, SrtEndpoint, V2VideoConfiguration, VideoCodec, VideoProfile,
};
use std::time::Instant;
use uuid::Uuid;

use crate::LatencyConfig;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaSessionConfig {
    pub session_id: Uuid,
    pub codec: VideoCodec,
    pub profile: VideoProfile,
    pub bitrate_bps: u32,
    pub output_format: PixelFormat,
    pub latency: LatencyConfig,
    pub transport_timeout_ms: u64,
    pub srt_endpoint: SrtEndpoint,
}

#[derive(Debug, Clone)]
pub struct ReceiverSessionV2 {
    pub id: Uuid,
    pub codec: VideoCodec,
    pub video: V2VideoConfiguration,
    pub output_format: PixelFormat,
    pub transport: SrtEndpoint,
    pub state: SessionStateV2,
    pub decoder: Option<String>,
    pub created_at: Instant,
    pub last_receiving_at: Option<Instant>,
    pub reconnect_count: u32,
}

impl ReceiverSessionV2 {
    pub fn new(
        id: Uuid,
        video: V2VideoConfiguration,
        output_format: PixelFormat,
        transport: SrtEndpoint,
    ) -> Self {
        Self {
            id,
            codec: video.codec,
            video,
            output_format,
            transport,
            state: SessionStateV2::Allocating,
            decoder: None,
            created_at: Instant::now(),
            last_receiving_at: None,
            reconnect_count: 0,
        }
    }
}
