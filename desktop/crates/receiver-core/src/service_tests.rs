use super::*;
use crate::StaticCapabilityProvider;
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

#[derive(Default)]
struct FakeReceiver {
    state: ReceiverState,
}

impl MediaReceiver for FakeReceiver {
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<u16, ReceiverError> {
        self.state = ReceiverState::Prepared;
        Ok(if config.media_port == 0 { 55_123 } else { config.media_port })
    }

    fn start(&mut self) -> Result<(), ReceiverError> {
        self.state = ReceiverState::WaitingForStream;
        Ok(())
    }

    fn stop(&mut self) -> Result<(), ReceiverError> {
        self.state = ReceiverState::Idle;
        Ok(())
    }

    fn state(&self) -> ReceiverState {
        self.state
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
