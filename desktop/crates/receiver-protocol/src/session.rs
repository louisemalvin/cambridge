use serde::{Deserialize, Serialize};

use crate::{validate_protocol_version, ProtocolError, ProtocolVersion, Transport, VideoCodec};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoProfile {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
}

impl VideoProfile {
    #[must_use]
    pub const fn is_valid(&self) -> bool {
        self.width > 0 && self.height > 0 && self.fps > 0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BitrateByCodec {
    pub h264: u32,
    pub h265: u32,
}

impl BitrateByCodec {
    #[must_use]
    pub const fn for_codec(&self, codec: VideoCodec) -> u32 {
        match codec {
            VideoCodec::H264 => self.h264,
            VideoCodec::H265 => self.h265,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PrepareSessionRequest {
    pub protocol_version: ProtocolVersion,
    pub preferred_codecs: Vec<VideoCodec>,
    pub profile: VideoProfile,
    pub bitrate_by_codec: BitrateByCodec,
}

impl PrepareSessionRequest {
    /// Validates the protocol version, codec preference, profile, and bitrates.
    ///
    /// # Errors
    ///
    /// Returns a typed error when a required request field is invalid.
    pub fn validate(&self) -> Result<(), ProtocolError> {
        validate_protocol_version(self.protocol_version)?;
        if self.preferred_codecs.is_empty() {
            return Err(ProtocolError::EmptyCodecPreference);
        }
        if self
            .preferred_codecs
            .iter()
            .enumerate()
            .any(|(index, codec)| self.preferred_codecs[..index].contains(codec))
        {
            return Err(ProtocolError::DuplicateCodecPreference);
        }
        if !self.profile.is_valid() {
            return Err(ProtocolError::InvalidProfile);
        }
        if self.bitrate_by_codec.h264 == 0 {
            return Err(ProtocolError::InvalidBitrate { codec: "h264" });
        }
        if self.bitrate_by_codec.h265 == 0 {
            return Err(ProtocolError::InvalidBitrate { codec: "h265" });
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaResponse {
    pub transport: Transport,
    pub port: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NegotiatedProfile {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_bps: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutputResponse {
    pub pixel_format: crate::PixelFormat,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PrepareSessionResponse {
    pub session_id: String,
    pub selected_codec: VideoCodec,
    pub media: MediaResponse,
    pub profile: NegotiatedProfile,
    pub output: OutputResponse,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReceiverSessionState {
    Idle,
    Prepared,
    WaitingForStream,
    Receiving,
    TimedOut,
    Stopping,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionStateResponse {
    pub session_id: String,
    pub state: ReceiverSessionState,
    pub selected_codec: VideoCodec,
    #[serde(default)]
    pub decoder: Option<String>,
    pub received_bitrate_bps: u32,
    pub timeout_count: u64,
}
