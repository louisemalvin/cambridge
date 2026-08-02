use serde::{Deserialize, Serialize};

use crate::{DecoderAcceleration, PixelFormat, ProtocolVersion, Transport, VideoCodec};

pub const MAXIMUM_CONCURRENT_SESSIONS: u8 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverCapabilities {
    pub protocol_version: ProtocolVersion,
    pub media: MediaCapabilities,
    pub video_codecs: Vec<VideoCodecCapability>,
    pub output: OutputCapabilities,
    pub session: SessionCapabilities,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaCapabilities {
    pub transport: Transport,
    pub port_assignment: MediaPortAssignment,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaPortAssignment {
    PerSession,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoCodecCapability {
    pub codec: VideoCodec,
    pub supported: bool,
    pub decoder_acceleration: DecoderAcceleration,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutputCapabilities {
    pub device: String,
    pub pixel_formats: Vec<PixelFormat>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionCapabilities {
    pub maximum_concurrent_sessions: u8,
    pub active: bool,
}

impl ReceiverCapabilities {
    #[must_use]
    pub fn supports(&self, codec: VideoCodec) -> bool {
        self.video_codecs.iter().any(|capability| capability.codec == codec && capability.supported)
    }
}
