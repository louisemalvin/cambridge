use serde::{Deserialize, Serialize};

use crate::{ProtocolVersion, VideoCodec};

pub const V2_PROTOCOL_VERSION: ProtocolVersion = 2;
pub const SRT_KEY_LENGTH_BYTES: u16 = 32;
pub const SRT_PASSPHRASE_MIN_LENGTH: usize = 10;
pub const SRT_PASSPHRASE_MAX_LENGTH: usize = 79;
pub const INITIAL_DEMAND_GENERATION: u64 = 0;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SrtMode {
    Caller,
    Listener,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SrtTransportKind {
    Srt,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SrtEndpoint {
    pub kind: SrtTransportKind,
    pub mode: SrtMode,
    pub host: String,
    pub port: u16,
    pub stream_id: String,
    pub latency_ms: u32,
    pub key_length_bytes: u16,
    pub passphrase: String,
}

impl SrtEndpoint {
    pub fn validate(&self) -> Result<(), V2ProtocolError> {
        if self.host.trim().is_empty() || self.port == 0 {
            return Err(V2ProtocolError::InvalidSrtEndpoint);
        }
        if self.stream_id.trim().is_empty() {
            return Err(V2ProtocolError::InvalidStreamId);
        }
        if self.key_length_bytes != SRT_KEY_LENGTH_BYTES {
            return Err(V2ProtocolError::InvalidSrtKeyLength(self.key_length_bytes));
        }
        let passphrase_length = self.passphrase.chars().count();
        if !(SRT_PASSPHRASE_MIN_LENGTH..=SRT_PASSPHRASE_MAX_LENGTH).contains(&passphrase_length) {
            return Err(V2ProtocolError::InvalidSrtPassphrase);
        }
        if self.latency_ms == 0 {
            return Err(V2ProtocolError::InvalidSrtLatency);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SrtTransportCapabilities {
    pub kind: SrtTransportKind,
    pub modes: Vec<SrtMode>,
    pub key_length_bytes: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct V2VideoProfile {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
}

impl V2VideoProfile {
    #[must_use]
    pub const fn is_valid(&self) -> bool {
        self.width > 0 && self.height > 0 && self.fps > 0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct V2BitrateByCodec {
    pub h264: u32,
    pub h265: u32,
}

impl V2BitrateByCodec {
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
pub struct V2VideoConfiguration {
    pub codec: VideoCodec,
    pub container: V2Container,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_bps: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum V2Container {
    Mpegts,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutputConfiguration {
    pub pixel_format: crate::PixelFormat,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverCapabilitiesV2 {
    pub protocol_version: ProtocolVersion,
    pub transport: SrtTransportCapabilities,
    pub video_codecs: Vec<VideoCodec>,
    pub output_profile: V2VideoProfile,
    pub output: OutputConfiguration,
    pub maximum_concurrent_sessions: u8,
    pub active: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DemandStateV2 {
    Inactive,
    Active,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DemandEventV2 {
    pub protocol_version: ProtocolVersion,
    pub generation: u64,
    pub demand: DemandStateV2,
    pub consumer_count: u32,
}

impl DemandEventV2 {
    pub fn validate(&self) -> Result<(), V2ProtocolError> {
        validate_v2_protocol_version(self.protocol_version)?;
        match self.demand {
            DemandStateV2::Inactive if self.consumer_count != 0 => {
                Err(V2ProtocolError::InactiveDemandHasConsumers)
            }
            DemandStateV2::Active if self.consumer_count == 0 => {
                Err(V2ProtocolError::ActiveDemandHasNoConsumers)
            }
            DemandStateV2::Inactive | DemandStateV2::Active => Ok(()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProtocolStatusV2 {
    Ready,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthResponseV2 {
    pub status: ProtocolStatusV2,
    pub protocol_version: ProtocolVersion,
}

impl Default for HealthResponseV2 {
    fn default() -> Self {
        Self { status: ProtocolStatusV2::Ready, protocol_version: V2_PROTOCOL_VERSION }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateSessionRequest {
    pub protocol_version: ProtocolVersion,
    pub preferred_codecs: Vec<VideoCodec>,
    pub profile: V2VideoProfile,
    pub bitrate_by_codec: V2BitrateByCodec,
}

impl CreateSessionRequest {
    pub fn validate(&self) -> Result<(), V2ProtocolError> {
        validate_v2_protocol_version(self.protocol_version)?;
        if self.preferred_codecs.is_empty() {
            return Err(V2ProtocolError::EmptyCodecPreference);
        }
        if self
            .preferred_codecs
            .iter()
            .enumerate()
            .any(|(index, codec)| self.preferred_codecs[..index].contains(codec))
        {
            return Err(V2ProtocolError::DuplicateCodecPreference);
        }
        if !self.profile.is_valid() {
            return Err(V2ProtocolError::InvalidProfile);
        }
        if self.bitrate_by_codec.h264 == 0 || self.bitrate_by_codec.h265 == 0 {
            return Err(V2ProtocolError::InvalidBitrate);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateSessionResponse {
    pub protocol_version: ProtocolVersion,
    pub session_id: String,
    pub connect_deadline_ms: u64,
    pub reconnect_grace_ms: u64,
    pub video: V2VideoConfiguration,
    pub transport: SrtEndpoint,
    pub output: OutputConfiguration,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionStateV2 {
    Idle,
    Allocating,
    Listening,
    Connected,
    Receiving,
    Reconnecting,
    Stopping,
    Failed,
    Expired,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionMetrics {
    pub bytes_received: Option<u64>,
    pub packets_received: Option<u64>,
    pub packets_lost: Option<u64>,
    pub packets_retransmitted: Option<u64>,
    pub packets_dropped: Option<u64>,
    pub rtt_ms: Option<u32>,
    pub decoded_frames: Option<u64>,
    pub output_fps: Option<u32>,
    pub output_queue_depth: Option<u32>,
    pub reconnect_count: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionStatusResponse {
    pub protocol_version: ProtocolVersion,
    pub session_id: String,
    pub state: SessionStateV2,
    pub decoder: Option<String>,
    pub metrics: SessionMetrics,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProblemDetails {
    pub code: V2ErrorCode,
    pub detail: String,
    pub protocol_version: ProtocolVersion,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum V2ErrorCode {
    UnsupportedProtocolVersion,
    Unauthorized,
    ReceiverBusy,
    UnsupportedCodec,
    UnsupportedProfile,
    InvalidSession,
    SessionExpired,
    TransportUnavailable,
    OutputUnavailable,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum V2ProtocolError {
    #[error("unsupported protocol version: {0}")]
    UnsupportedVersion(ProtocolVersion),
    #[error("at least one preferred codec is required")]
    EmptyCodecPreference,
    #[error("preferred codecs must not contain duplicates")]
    DuplicateCodecPreference,
    #[error("video profile dimensions and frame rate must be greater than zero")]
    InvalidProfile,
    #[error("codec bitrates must be greater than zero")]
    InvalidBitrate,
    #[error("SRT endpoint host and port are required")]
    InvalidSrtEndpoint,
    #[error("SRT stream ID is required")]
    InvalidStreamId,
    #[error("SRT key length is not AES-256: {0} bytes")]
    InvalidSrtKeyLength(u16),
    #[error("SRT passphrase length is outside the supported range")]
    InvalidSrtPassphrase,
    #[error("SRT latency must be greater than zero")]
    InvalidSrtLatency,
    #[error("inactive demand must not report consumers")]
    InactiveDemandHasConsumers,
    #[error("active demand must report at least one consumer")]
    ActiveDemandHasNoConsumers,
}

pub fn validate_v2_protocol_version(version: ProtocolVersion) -> Result<(), V2ProtocolError> {
    if version == V2_PROTOCOL_VERSION {
        Ok(())
    } else {
        Err(V2ProtocolError::UnsupportedVersion(version))
    }
}
