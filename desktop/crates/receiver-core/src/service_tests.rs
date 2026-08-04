use super::*;
use crate::SrtConfig;
use crate::{
    DiagnosticPhase, FrameIntervalStatistics, QueueDiagnostics, ReceiverDiagnostics,
    StaticCapabilityProvider, DIAGNOSTICS_SCHEMA,
};
use receiver_protocol::{
    DecoderAcceleration, OutputCapabilities, ReceiverCapabilities, SessionCapabilities, SrtMode,
    V2BitrateByCodec, V2VideoProfile, VideoCodec, VideoCodecCapability, VideoProfile,
    MAXIMUM_CONCURRENT_SESSIONS, SRT_KEY_LENGTH_BYTES, V2_PROTOCOL_VERSION,
};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};
use uuid::Uuid;

const TEST_BITRATE_BPS: u32 = 4_000_000;

#[derive(Default)]
struct FakeReceiver {
    state: ReceiverState,
    session_id: Option<Uuid>,
}

impl MediaReceiver for FakeReceiver {
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<(), ReceiverError> {
        self.state = ReceiverState::Prepared;
        self.session_id = Some(config.session_id);
        Ok(())
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
            target_profile: VideoProfile { width: 1_280, height: 720, fps: 30 },
            target_bitrate_bps: TEST_BITRATE_BPS,
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
        session: SessionCapabilities {
            maximum_concurrent_sessions: MAXIMUM_CONCURRENT_SESSIONS,
            active: false,
        },
    }
}

fn v2_request(preferred_codecs: Vec<VideoCodec>) -> receiver_protocol::CreateSessionRequest {
    receiver_protocol::CreateSessionRequest {
        protocol_version: V2_PROTOCOL_VERSION,
        preferred_codecs,
        profile: V2VideoProfile { width: 1_280, height: 720, fps: 30 },
        bitrate_by_codec: V2BitrateByCodec { h264: TEST_BITRATE_BPS, h265: 7_000_000 },
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
fn codec_preference_selects_h265_then_falls_back_to_h264() {
    let mut both = service(true, true);
    assert_eq!(
        both.create_session_v2(&v2_request(vec![VideoCodec::H265, VideoCodec::H264]))
            .unwrap()
            .video
            .codec,
        VideoCodec::H265
    );

    let mut h264_only = service(true, false);
    assert_eq!(
        h264_only
            .create_session_v2(&v2_request(vec![VideoCodec::H265, VideoCodec::H264]))
            .unwrap()
            .video
            .codec,
        VideoCodec::H264
    );
}

#[test]
fn no_compatible_codec_is_an_error() {
    let mut receiver = service(false, true);
    let error = receiver.create_session_v2(&v2_request(vec![VideoCodec::H264])).unwrap_err();
    assert!(matches!(error, ReceiverError::NoCompatibleCodec { .. }));
}

#[test]
fn session_conflict_and_idempotent_stop_are_handled() {
    let mut receiver = service(true, true);
    let created = receiver.create_session_v2(&v2_request(vec![VideoCodec::H264])).unwrap();
    assert!(matches!(
        receiver.create_session_v2(&v2_request(vec![VideoCodec::H264])),
        Err(ReceiverError::SessionConflict)
    ));
    receiver.stop_session_v2(&created.session_id).unwrap();
    receiver.stop_session_v2(&created.session_id).unwrap();
    assert_eq!(receiver.state(), ReceiverState::Idle);
}

#[test]
fn latest_diagnostics_is_retained_after_stop() {
    let mut receiver = service(true, true);
    let created = receiver.create_session_v2(&v2_request(vec![VideoCodec::H264])).unwrap();
    let _ = receiver.session_v2(&created.session_id);

    receiver.stop_session_v2(&created.session_id).unwrap();

    let run = receiver.latest_diagnostics_v2().unwrap();
    assert_eq!(run.session_id, created.session_id);
    assert_eq!(run.schema, DIAGNOSTICS_SCHEMA);
    assert!(!run.snapshots.is_empty());
}

#[test]
fn latest_diagnostics_is_unavailable_before_a_completed_run() {
    let mut receiver = service(true, true);

    assert!(matches!(receiver.latest_diagnostics_v2(), Err(ReceiverError::DiagnosticsNotFound)));
}

#[test]
fn diagnostic_snapshot_retention_is_bounded() {
    let mut receiver = service(true, true);
    let created = receiver.create_session_v2(&v2_request(vec![VideoCodec::H264])).unwrap();
    for _ in 0..=MAX_DIAGNOSTIC_SNAPSHOT_COUNT {
        let _ = receiver.session_v2(&created.session_id);
    }

    receiver.stop_session_v2(&created.session_id).unwrap();

    let run = receiver.latest_diagnostics_v2().unwrap();
    assert_eq!(run.snapshots.len(), MAX_DIAGNOSTIC_SNAPSHOT_COUNT);
}

#[test]
fn v2_session_uses_receiver_owned_srt_endpoint_and_is_idempotently_stoppable() {
    let mut receiver = service(true, false);
    let created = receiver.create_session_v2(&v2_request(vec![VideoCodec::H264])).unwrap();

    assert_eq!(created.protocol_version, V2_PROTOCOL_VERSION);
    assert_eq!(created.video.width, 1_280);
    assert_eq!(created.video.height, 720);
    assert_eq!(created.transport.mode, SrtMode::Caller);
    assert_eq!(created.transport.port, receiver.config().srt.listen_port);
    assert_eq!(created.transport.key_length_bytes, SRT_KEY_LENGTH_BYTES);
    assert!(created.transport.validate().is_ok());
    assert_eq!(
        receiver.session_v2(&created.session_id).unwrap().state,
        receiver_protocol::SessionStateV2::Listening
    );
    assert!(matches!(
        receiver.create_session_v2(&v2_request(vec![VideoCodec::H264])),
        Err(ReceiverError::SessionConflict)
    ));

    receiver.stop_session_v2(&created.session_id).unwrap();
    receiver.stop_session_v2(&created.session_id).unwrap();
    assert!(!receiver.capabilities_v2().unwrap().active);
}

#[test]
fn v2_rejects_a_profile_that_would_change_the_persistent_output() {
    let mut receiver = service(true, false);
    let mut request = v2_request(vec![VideoCodec::H264]);
    request.profile.width = 1_920;

    assert!(matches!(
        receiver.create_session_v2(&request),
        Err(ReceiverError::UnsupportedProfile { .. })
    ));
}

#[test]
fn expired_srt_connection_releases_the_session_without_stopping_the_service() {
    let state = Arc::new(Mutex::new(ReceiverState::Idle));
    let receiver = SharedStateReceiver { state: state.clone() };
    let provider = StaticCapabilityProvider::new(capabilities(true, true));
    let config = ReceiverConfig {
        device: PathBuf::from("/dev/video10"),
        srt: SrtConfig { connect_deadline_ms: 1, reconnect_grace_ms: 1, ..SrtConfig::default() },
        ..ReceiverConfig::default()
    };
    let mut service = ReceiverService::new(config, Box::new(provider), Box::new(receiver)).unwrap();
    let created = service.create_session_v2(&v2_request(vec![VideoCodec::H264])).unwrap();
    *state.lock().unwrap() = ReceiverState::TimedOut;

    let _ = service.session_v2(&created.session_id);
    thread::sleep(Duration::from_millis(3));

    assert_eq!(service.state(), ReceiverState::Idle);
    assert!(!service.capabilities_v2().unwrap().active);
}

struct SharedStateReceiver {
    state: Arc<Mutex<ReceiverState>>,
}

impl MediaReceiver for SharedStateReceiver {
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<(), ReceiverError> {
        *self.state.lock().unwrap() = ReceiverState::Prepared;
        let _ = config;
        Ok(())
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
