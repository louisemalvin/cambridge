use receiver_protocol::VideoCodec;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum PipelineError {
    #[error("GStreamer initialisation failed: {0}")]
    Initialization(String),
    #[error("missing GStreamer element: {0}")]
    MissingElement(String),
    #[error("missing parser for {0}")]
    MissingParser(VideoCodec),
    #[error("no decoder is available for {0}")]
    NoDecoder(VideoCodec),
    #[error("failed to create element {name}: {reason}")]
    ElementCreation { name: String, reason: String },
    #[error("failed to configure element {name}: {reason}")]
    ElementConfiguration { name: String, reason: String },
    #[error("failed to link GStreamer elements: {0}")]
    Link(String),
    #[error("failed to set pipeline state: {0}")]
    StateChange(String),
    #[error("wrong stream codec: expected {expected}, received {received}")]
    WrongStreamCodec { expected: VideoCodec, received: VideoCodec },
    #[error("GStreamer pipeline error: {0}")]
    Pipeline(String),
}
