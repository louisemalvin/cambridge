use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ProtocolError {
    #[error("unsupported protocol version: {0}")]
    UnsupportedVersion(u32),
    #[error("at least one preferred codec is required")]
    EmptyCodecPreference,
    #[error("preferred codecs must not contain duplicates")]
    DuplicateCodecPreference,
    #[error("video profile dimensions and frame rate must be greater than zero")]
    InvalidProfile,
    #[error("bitrate for {codec} must be greater than zero")]
    InvalidBitrate { codec: &'static str },
}
