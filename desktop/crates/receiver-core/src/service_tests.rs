use super::*;
use crate::{
    DiagnosticPhase, FrameIntervalStatistics, QueueDiagnostics, ReceiverDiagnostics,
    StaticCapabilityProvider, DIAGNOSTICS_SCHEMA,
};
use receiver_protocol::{
    DecoderAcceleration, MediaCapabilities, MediaPortAssignment, OutputCapabilities,
    ReceiverSessionState, SessionCapabilities, VideoCodec, VideoCodecCapability, VideoProfile,
};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};
use uuid::Uuid;

#[derive(Default)]
struct FakeReceiver {
    state: ReceiverState,
    session_id: Option<Uuid>,
}

impl MediaReceiver for FakeReceiver {
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<u16, ReceiverError> {
        self.state = ReceiverState::Prepared;
        self.session_id = Some(config.session_id);
        Ok(if config.media_port == 0 { 55_123 } else { config.media_port })
    }

    fn start(&mut self) -> Result<(), ReceiverError> {
        self.state = ReceiverState::WaitingForStream;
        Ok(())
    }

    fn stop(&mut self) -> Result<(), ReceiverError> {
        self.state = ReceiverState::Idle;
        self.session_id = None;
        Ok(())
    }

    fn state(&self) -> ReceiverState {
        self.state
    }

    fn diagnostics(&self) -> Option<ReceiverDiagnostics> {
        Some(ReceiverDiagnostics {
            schema: DIAGNOSTICS_SCHEMA.to_owned(),
            session_id: self.session_id?.to_string(),
            started_at_ms: 1,
            captured_at_ms: 2,
            elapsed_ms: 1,
            state: self.state,
            selected_codec: VideoCodec::H264,
            target_profile: VideoProfile { width: 1920, height: 1080, fps: 30 },
            target_bitrate_bps: 10_000_000,
            output_pixel_format: receiver_protocol::PixelFormat::Yuy2,
            decoder: None,
            first_frame_elapsed_ms: None,
            last_network_age_ms: None,
            last_decoded_frame_age_ms: None,
            observed_fps: None,
            frame_intervals: FrameIntervalStatistics::default(),
            received_bitrate_bps: 0,
            recent_received_bitrate_bps: 0,
            received_bytes: 0,
            decoded_frames: 0,
            timeout_count: 0,
            continuity_warning_count: 0,
            pipeline_warning_count: 0,
            pipeline_error_count: 0,
            output_queue: QueueDiagnostics::default(),
            phase: DiagnosticPhase::WaitingForPackets,
            events: Vec::new(),
        })
    }
}

fn capabilities(h264: bool, h265: bool) -> ReceiverCapabilities {
    ReceiverCapabilities {
        protocol_version: 1,
        media: MediaCapabilities {
            transport: receiver_protocol::Transport::MpegTsUdp,
            port_assignment: MediaPortAssignment::PerSession,
        },
        video_codecs: vec![
            VideoCodecCapability {
                codec: VideoCodec::H264,
                supported: h264,
                decoder_acceleration: DecoderAcceleration::Unknown,
            },
            VideoCodecCapability {
                codec: VideoCodec::H265,
                supported: h265,
                decoder_acceleration: DecoderAcceleration::Unknown,
            },
        ],
        output: OutputCapabilities {
            device: "/dev/video10".to_owned(),
            pixel_formats: vec![
                receiver_protocol::PixelFormat::Yuy2,
                receiver_protocol::PixelFormat::Nv12,
                receiver_protocol::PixelFormat::I420,
            ],
        },
        session: SessionCapabilities { maximum_concurrent_sessions: 1, active: false },
    }
}

fn request(preferred_codecs: Vec<VideoCodec>) -> PrepareSessionRequest {
    PrepareSessionRequest {
        protocol_version: 1,
        preferred_codecs,
        profile: VideoProfile { width: 1920, height: 1080, fps: 30 },
        bitrate_by_codec: receiver_protocol::BitrateByCodec { h264: 10_000_000, h265: 7_000_000 },
    }
}

fn service(h264: bool, h265: bool) -> ReceiverService {
    let provider = StaticCapabilityProvider::new(capabilities(h264, h265));
    ReceiverService::new(
        ReceiverConfig { device: PathBuf::from("/dev/video10"), ..ReceiverConfig::default() },
        Box::new(provider),
        Box::new(FakeReceiver::default()),
    )
    .unwrap()
}

#[test]
fn auto_preference_selects_h265_then_falls_back_to_h264() {
    let mut both = service(true, true);
    assert_eq!(
        both.prepare_session(&request(vec![VideoCodec::H265, VideoCodec::H264]))
            .unwrap()
            .selected_codec,
        VideoCodec::H265
    );

    let mut h264_only = service(true, false);
    assert_eq!(
        h264_only
            .prepare_session(&request(vec![VideoCodec::H265, VideoCodec::H264]))
            .unwrap()
            .selected_codec,
        VideoCodec::H264
    );
}

#[test]
fn no_compatible_codec_is_an_error() {
    let mut receiver = service(false, true);
    let error = receiver.prepare_session(&request(vec![VideoCodec::H264])).unwrap_err();
    assert!(matches!(error, ReceiverError::NoCompatibleCodec { .. }));
}

#[test]
fn session_conflict_and_idempotent_stop_are_handled() {
    let mut receiver = service(true, true);
    let prepared = receiver.prepare_session(&request(vec![VideoCodec::H264])).unwrap();
    assert!(matches!(
        receiver.prepare_session(&request(vec![VideoCodec::H264])),
        Err(ReceiverError::SessionConflict)
    ));
    receiver.stop_session(&prepared.session_id).unwrap();
    receiver.stop_session(&prepared.session_id).unwrap();
    assert_eq!(receiver.state(), ReceiverState::Idle);
}

#[test]
fn latest_diagnostics_is_retained_after_stop() {
    let mut receiver = service(true, true);
    let prepared = receiver.prepare_session(&request(vec![VideoCodec::H264])).unwrap();
    let _ = receiver.session(&prepared.session_id);

    receiver.stop_session(&prepared.session_id).unwrap();

    let run = receiver.latest_diagnostics().unwrap();
    assert_eq!(run.session_id, prepared.session_id);
    assert_eq!(run.schema, DIAGNOSTICS_SCHEMA);
    assert!(!run.snapshots.is_empty());
}

#[test]
fn latest_diagnostics_is_unavailable_before_a_completed_run() {
    let mut receiver = service(true, true);

    assert!(matches!(receiver.latest_diagnostics(), Err(ReceiverError::DiagnosticsNotFound)));
}

#[test]
fn diagnostic_snapshot_retention_is_bounded() {
    let mut receiver = service(true, true);
    let prepared = receiver.prepare_session(&request(vec![VideoCodec::H264])).unwrap();
    for _ in 0..=MAX_DIAGNOSTIC_SNAPSHOT_COUNT {
        let _ = receiver.session(&prepared.session_id);
    }

    receiver.stop_session(&prepared.session_id).unwrap();

    let run = receiver.latest_diagnostics().unwrap();
    assert_eq!(run.snapshots.len(), MAX_DIAGNOSTIC_SNAPSHOT_COUNT);
}

#[test]
fn receiver_state_starts_waiting_for_stream() {
    let mut receiver = service(true, true);
    let prepared =
        receiver.prepare_session(&request(vec![VideoCodec::H265, VideoCodec::H264])).unwrap();
    let state = receiver.session(&prepared.session_id).unwrap();
    assert_eq!(state.state, ReceiverSessionState::WaitingForStream);
}

#[test]
fn prolonged_timeout_releases_the_session_without_stopping_the_service() {
    let state = Arc::new(Mutex::new(ReceiverState::Idle));
    let receiver = SharedStateReceiver { state: state.clone() };
    let provider = StaticCapabilityProvider::new(capabilities(true, true));
    let config = ReceiverConfig {
        device: PathBuf::from("/dev/video10"),
        udp_timeout_ms: 1,
        session_timeout_grace_ms: 1,
        ..ReceiverConfig::default()
    };
    let mut service = ReceiverService::new(config, Box::new(provider), Box::new(receiver)).unwrap();
    let prepared = service.prepare_session(&request(vec![VideoCodec::H264])).unwrap();
    *state.lock().unwrap() = ReceiverState::TimedOut;

    let _ = service.session(&prepared.session_id);
    thread::sleep(Duration::from_millis(3));

    assert_eq!(service.state(), ReceiverState::Idle);
    assert!(!service.capabilities().session.active);
}

struct SharedStateReceiver {
    state: Arc<Mutex<ReceiverState>>,
}

impl MediaReceiver for SharedStateReceiver {
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<u16, ReceiverError> {
        *self.state.lock().unwrap() = ReceiverState::Prepared;
        Ok(if config.media_port == 0 { 55_124 } else { config.media_port })
    }

    fn start(&mut self) -> Result<(), ReceiverError> {
        *self.state.lock().unwrap() = ReceiverState::WaitingForStream;
        Ok(())
    }

    fn stop(&mut self) -> Result<(), ReceiverError> {
        *self.state.lock().unwrap() = ReceiverState::Idle;
        Ok(())
    }

    fn state(&self) -> ReceiverState {
        *self.state.lock().unwrap()
    }
}
