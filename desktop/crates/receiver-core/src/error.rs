use receiver_protocol::VideoCodec;
use thiserror::Error;

use crate::VideoProfile;

#[derive(Debug, Error)]
pub enum ReceiverError {
    #[error("invalid receiver configuration: {0}")]
    InvalidConfiguration(String),
    #[error("no compatible codec for {width}x{height} at {fps} FPS")]
    NoCompatibleCodec { width: u32, height: u32, fps: u32 },
    #[error("unsupported codec: {0}")]
    UnsupportedCodec(VideoCodec),
    #[error("codec {codec} does not support {profile:?}")]
    UnsupportedProfile { codec: VideoCodec, profile: VideoProfile },
    #[error("another receiver session is active")]
    SessionConflict,
    #[error("receiver session not found: {0}")]
    SessionNotFound(String),
    #[error("receiver diagnostics are not available")]
    DiagnosticsNotFound,
    #[error("media preparation failed: {0}")]
    MediaPreparation(String),
    #[error("media start failed: {0}")]
    MediaStart(String),
    #[error("media stop failed: {0}")]
    MediaStop(String),
    #[error("wrong stream codec: expected {expected}, received {received}")]
    WrongStreamCodec { expected: VideoCodec, received: VideoCodec },
    #[error("output consumer failed: {0}")]
    OutputConsumer(String),
    #[error("permission denied: {0}")]
    PermissionDenied(String),
    #[error("GStreamer pipeline failure: {0}")]
    GStreamer(String),
}
