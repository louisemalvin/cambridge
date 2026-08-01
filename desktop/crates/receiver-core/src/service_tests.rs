use super::*;
use crate::StaticCapabilityProvider;
use receiver_protocol::{
    DecoderAcceleration, MediaCapabilities, OutputCapabilities, ReceiverSessionState,
    SessionCapabilities, VideoCodec, VideoCodecCapability, VideoProfile,
};
use std::path::PathBuf;

#[derive(Default)]
struct FakeReceiver {
    state: ReceiverState,
}

impl MediaReceiver for FakeReceiver {
    fn prepare(&mut self, _config: MediaSessionConfig) -> Result<(), ReceiverError> {
        self.state = ReceiverState::Prepared;
        Ok(())
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
            default_port: 5000,
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
