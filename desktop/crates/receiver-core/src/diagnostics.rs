use serde::{Deserialize, Serialize};

use receiver_protocol::{PixelFormat, VideoCodec, VideoProfile};

use crate::ReceiverState;

pub const DIAGNOSTICS_SCHEMA: &str = "mobile-webcam-diagnostics-v1";
const UNAVAILABLE_TIMESTAMP_MS: u64 = 0;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverTransportMetrics {
    pub bytes_received: Option<u64>,
    pub packets_received: Option<u64>,
    pub packets_lost: Option<u64>,
    pub packets_retransmitted: Option<u64>,
    pub packets_dropped: Option<u64>,
    pub rtt_ms: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverDiagnostics {
    pub schema: String,
    pub session_id: String,
    pub started_at_ms: u64,
    pub captured_at_ms: u64,
    pub elapsed_ms: u64,
    pub state: ReceiverState,
    pub selected_codec: VideoCodec,
    pub target_profile: VideoProfile,
    pub target_bitrate_bps: u32,
    pub output_pixel_format: PixelFormat,
    pub decoder: Option<String>,
    pub first_frame_elapsed_ms: Option<u64>,
    pub last_network_age_ms: Option<u64>,
    pub last_decoded_frame_age_ms: Option<u64>,
    pub observed_fps: Option<f64>,
    pub frame_intervals: FrameIntervalStatistics,
    pub received_bitrate_bps: u32,
    pub recent_received_bitrate_bps: u32,
    pub received_bytes: u64,
    pub decoded_frames: u64,
    pub timeout_count: u64,
    pub continuity_warning_count: u64,
    pub pipeline_warning_count: u64,
    pub pipeline_error_count: u64,
    pub output_queue: QueueDiagnostics,
    pub phase: DiagnosticPhase,
    pub events: Vec<ReceiverDiagnosticEvent>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverDiagnosticsRun {
    pub schema: String,
    pub session_id: String,
    pub started_at_ms: u64,
    pub completed_at_ms: u64,
    pub snapshots: Vec<ReceiverDiagnostics>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameIntervalStatistics {
    pub sample_count: u64,
    pub min_ms: Option<f64>,
    pub mean_ms: Option<f64>,
    pub p50_ms: Option<f64>,
    pub p95_ms: Option<f64>,
    pub max_ms: Option<f64>,
    pub mean_absolute_jitter_ms: Option<f64>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueDiagnostics {
    pub configured_max_frames: u32,
    pub current_frames: u32,
    pub maximum_observed_frames: u32,
    pub high_watermark_samples: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticPhase {
    Starting,
    WaitingForPackets,
    SteadyState,
    PacketInterruption,
    DecoderStall,
    OutputBackpressure,
    PipelineError,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverDiagnosticEvent {
    pub sequence: u64,
    pub timestamp_ms: u64,
    pub elapsed_ms: u64,
    #[serde(flatten)]
    pub kind: ReceiverDiagnosticEventKind,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "event", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum ReceiverDiagnosticEventKind {
    #[serde(rename = "receiver_state_changed")]
    StateChanged { state: ReceiverState },
    #[serde(rename = "receiver_first_frame")]
    FirstFrame { codec: VideoCodec },
    #[serde(rename = "receiver_stream_timed_out")]
    StreamTimedOut { timeout_count: u64 },
    #[serde(rename = "receiver_stream_resumed")]
    StreamResumed,
    #[serde(rename = "receiver_wrong_stream_codec")]
    WrongStreamCodec { expected: VideoCodec, received: VideoCodec },
    #[serde(rename = "receiver_decoder_selected")]
    DecoderSelected { decoder: String },
    #[serde(rename = "receiver_continuity_warning")]
    ContinuityWarning { source: String, message: String },
    #[serde(rename = "receiver_pipeline_warning")]
    PipelineWarning { source: String, message: String },
    #[serde(rename = "receiver_pipeline_error")]
    PipelineError { source: String, message: String },
    #[serde(rename = "receiver_queue_pressure")]
    QueuePressure { current_frames: u32, maximum_frames: u32 },
}

impl ReceiverDiagnostics {
    #[must_use]
    pub fn schema() -> String {
        DIAGNOSTICS_SCHEMA.to_owned()
    }
}

pub fn diagnostic_timestamp_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(UNAVAILABLE_TIMESTAMP_MS, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}
