//! Platform-independent receiver service and session policy.

mod config;
mod demand;
mod diagnostics;
mod error;
mod event;
mod negotiation;
mod service;
mod session;
mod state;
mod validation;

pub use config::{
    LatencyConfig, OutputFormat, ReceiverConfig, SrtConfig, DEFAULT_ADVERTISED_HOST,
    DEFAULT_CONTROL_PORT, DEFAULT_DEMUX_LATENCY_MS, DEFAULT_H264_BITRATE_BPS,
    DEFAULT_H265_BITRATE_BPS, DEFAULT_LISTEN_ADDRESS, DEFAULT_OUTPUT_FPS, DEFAULT_OUTPUT_HEIGHT,
    DEFAULT_OUTPUT_PROFILE, DEFAULT_OUTPUT_QUEUE_FRAMES, DEFAULT_OUTPUT_WIDTH,
    DEFAULT_RECEIVER_NAME, DEFAULT_SRT_CONNECT_DEADLINE_MS, DEFAULT_SRT_INACTIVITY_TIMEOUT_MS,
    DEFAULT_SRT_LATENCY_MS, DEFAULT_SRT_LISTEN_PORT, DEFAULT_SRT_RECONNECT_GRACE_MS,
    DEFAULT_VIDEO_DEVICE,
};
pub use demand::VirtualCameraDemand;
pub use diagnostics::{
    diagnostic_timestamp_ms, DiagnosticPhase, FrameIntervalStatistics, QueueDiagnostics,
    ReceiverDiagnosticEvent, ReceiverDiagnosticEventKind, ReceiverDiagnostics,
    ReceiverDiagnosticsRun, ReceiverTransportMetrics, DIAGNOSTICS_SCHEMA,
};
pub use error::ReceiverError;
pub use event::ReceiverEvent;
pub use negotiation::{negotiate_codec, ReceiverCapabilityProvider, StaticCapabilityProvider};
pub use receiver_protocol::{ReceiverCapabilities, VideoCodec, VideoProfile};
pub use service::{MediaReceiver, ReceiverService};
pub use session::{MediaSessionConfig, ReceiverSessionV2};
pub use state::ReceiverState;
pub use validation::{select_output_format, validate_config};
