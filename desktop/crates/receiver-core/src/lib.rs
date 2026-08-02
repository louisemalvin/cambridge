//! Platform-independent receiver service and session policy.

mod config;
mod diagnostics;
mod error;
mod event;
mod negotiation;
mod service;
mod session;
mod state;
mod validation;

pub use config::{
    LatencyConfig, OutputFormat, ReceiverConfig, DEFAULT_CONTROL_PORT, DEFAULT_DEMUX_LATENCY_MS,
    DEFAULT_LISTEN_ADDRESS, DEFAULT_OUTPUT_QUEUE_FRAMES, DEFAULT_SESSION_TIMEOUT_GRACE_MS,
    DEFAULT_UDP_TIMEOUT_MS, DEFAULT_VIDEO_DEVICE, MEDIA_PORT_RANGE_END, MEDIA_PORT_RANGE_SIZE,
    MEDIA_PORT_RANGE_START, PORT_UNASSIGNED,
};
pub use diagnostics::{
    diagnostic_timestamp_ms, DiagnosticPhase, FrameIntervalStatistics, QueueDiagnostics,
    ReceiverDiagnosticEvent, ReceiverDiagnosticEventKind, ReceiverDiagnostics,
    ReceiverDiagnosticsRun, DIAGNOSTICS_SCHEMA,
};
pub use error::ReceiverError;
pub use event::ReceiverEvent;
pub use negotiation::{negotiate_codec, ReceiverCapabilityProvider, StaticCapabilityProvider};
pub use receiver_protocol::{ReceiverCapabilities, VideoCodec, VideoProfile};
pub use service::{MediaReceiver, ReceiverService};
pub use session::{MediaSessionConfig, ReceiverSession};
pub use state::ReceiverState;
pub use validation::{select_output_format, validate_config};
