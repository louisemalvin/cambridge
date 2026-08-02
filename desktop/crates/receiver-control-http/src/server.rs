use std::{future::Future, net::SocketAddr, time::Duration};

use axum::serve as axum_serve;
use thiserror::Error;
use tokio::net::TcpListener;

use crate::{router, ControlState};

const WATCHDOG_INTERVAL: Duration = Duration::from_millis(250);

#[derive(Debug, Error)]
pub enum HttpServerError {
    #[error("control listener failed: {0}")]
    Listener(#[source] std::io::Error),
    #[error("control server failed: {0}")]
    Serve(#[source] std::io::Error),
}

pub async fn serve<F>(
    state: ControlState,
    listen_addr: SocketAddr,
    shutdown: F,
) -> Result<(), HttpServerError>
where
    F: Future<Output = ()> + Send + 'static,
{
    let listener = TcpListener::bind(listen_addr).await.map_err(HttpServerError::Listener)?;
    serve_listener(listener, state, shutdown).await
}

pub async fn serve_listener<F>(
    listener: TcpListener,
    state: ControlState,
    shutdown: F,
) -> Result<(), HttpServerError>
where
    F: Future<Output = ()> + Send + 'static,
{
    let watchdog_state = state.clone();
    let watchdog = tokio::spawn(async move {
        let mut interval = tokio::time::interval(WATCHDOG_INTERVAL);
        loop {
            interval.tick().await;
            watchdog_state.refresh();
        }
    });
    let result = axum_serve(listener, router(state))
        .with_graceful_shutdown(shutdown)
        .await
        .map_err(HttpServerError::Serve);
    watchdog.abort();
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::StatusCode;
    use http_body_util::BodyExt;
    use receiver_core::{
        DiagnosticPhase, FrameIntervalStatistics, MediaReceiver, MediaSessionConfig,
        QueueDiagnostics, ReceiverConfig, ReceiverDiagnostics, ReceiverError, ReceiverService,
        ReceiverState, StaticCapabilityProvider, DIAGNOSTICS_SCHEMA,
    };
    use receiver_protocol::{
        DecoderAcceleration, MediaCapabilities, MediaPortAssignment, OutputCapabilities,
        ReceiverCapabilities, SessionCapabilities, VideoCodec, VideoCodecCapability,
    };
    use tower::ServiceExt;

    struct FakeReceiver;

    impl MediaReceiver for FakeReceiver {
        fn prepare(&mut self, config: MediaSessionConfig) -> Result<u16, ReceiverError> {
            Ok(if config.media_port == 0 { 55_125 } else { config.media_port })
        }
        fn start(&mut self) -> Result<(), ReceiverError> {
            Ok(())
        }
        fn stop(&mut self) -> Result<(), ReceiverError> {
            Ok(())
        }
        fn state(&self) -> ReceiverState {
            ReceiverState::WaitingForStream
        }

        fn diagnostics(&self) -> Option<ReceiverDiagnostics> {
            Some(ReceiverDiagnostics {
                schema: DIAGNOSTICS_SCHEMA.to_owned(),
                session_id: "fake".to_owned(),
                started_at_ms: 1,
                captured_at_ms: 2,
                elapsed_ms: 1,
                state: ReceiverState::WaitingForStream,
                selected_codec: VideoCodec::H264,
                target_profile: receiver_protocol::VideoProfile {
                    width: 320,
                    height: 240,
                    fps: 30,
                },
                target_bitrate_bps: 500_000,
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

    fn service() -> ReceiverService {
        let capabilities = ReceiverCapabilities {
            protocol_version: 1,
            media: MediaCapabilities {
                transport: receiver_protocol::Transport::MpegTsUdp,
                port_assignment: MediaPortAssignment::PerSession,
            },
            video_codecs: vec![
                VideoCodecCapability {
                    codec: VideoCodec::H264,
                    supported: true,
                    decoder_acceleration: DecoderAcceleration::Unknown,
                },
                VideoCodecCapability {
                    codec: VideoCodec::H265,
                    supported: true,
                    decoder_acceleration: DecoderAcceleration::Unknown,
                },
            ],
            output: OutputCapabilities {
                device: "/dev/video10".to_owned(),
                pixel_formats: vec![receiver_protocol::PixelFormat::Yuy2],
            },
            session: SessionCapabilities { maximum_concurrent_sessions: 1, active: false },
        };
        let provider = StaticCapabilityProvider::new(capabilities);
        ReceiverService::new(ReceiverConfig::default(), Box::new(provider), Box::new(FakeReceiver))
            .unwrap()
    }

    #[tokio::test]
    async fn health_endpoint_returns_protocol_version() {
        let app = router(ControlState::new(service()));
        let response = app
            .oneshot(axum::http::Request::builder().uri("/v1/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let health: receiver_protocol::HealthResponse = serde_json::from_slice(&body).unwrap();
        assert_eq!(health.protocol_version, 1);
    }

    #[tokio::test]
    async fn session_endpoints_prepare_report_and_stop_a_session() {
        let app = router(ControlState::new(service()));
        let missing_diagnostics = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/v1/diagnostics/latest")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(missing_diagnostics.status(), StatusCode::NOT_FOUND);

        let capabilities = app
            .clone()
            .oneshot(
                axum::http::Request::builder().uri("/v1/capabilities").body(Body::empty()).unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(capabilities.status(), StatusCode::OK);

        let request = receiver_protocol::PrepareSessionRequest {
            protocol_version: 1,
            preferred_codecs: vec![VideoCodec::H264],
            profile: receiver_protocol::VideoProfile { width: 1920, height: 1080, fps: 30 },
            bitrate_by_codec: receiver_protocol::BitrateByCodec {
                h264: 10_000_000,
                h265: 7_000_000,
            },
        };
        let response = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .method("POST")
                    .uri("/v1/sessions/prepare")
                    .header("content-type", "application/json")
                    .body(Body::from(serde_json::to_vec(&request).unwrap()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let prepared: receiver_protocol::PrepareSessionResponse =
            serde_json::from_slice(&body).unwrap();

        let state = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri(format!("/v1/sessions/{}", prepared.session_id))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(state.status(), StatusCode::OK);

        let diagnostics = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri(format!("/v1/sessions/{}/diagnostics", prepared.session_id))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(diagnostics.status(), StatusCode::OK);
        let body = diagnostics.into_body().collect().await.unwrap().to_bytes();
        let report: receiver_core::ReceiverDiagnostics = serde_json::from_slice(&body).unwrap();
        assert_eq!(report.schema, DIAGNOSTICS_SCHEMA);

        let stopped = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .method("DELETE")
                    .uri(format!("/v1/sessions/{}", prepared.session_id))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(stopped.status(), StatusCode::NO_CONTENT);

        let latest = app
            .oneshot(
                axum::http::Request::builder()
                    .uri("/v1/diagnostics/latest")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(latest.status(), StatusCode::OK);
        let body = latest.into_body().collect().await.unwrap().to_bytes();
        let report: receiver_core::ReceiverDiagnosticsRun = serde_json::from_slice(&body).unwrap();
        assert_eq!(report.schema, DIAGNOSTICS_SCHEMA);
        assert!(!report.snapshots.is_empty());
    }
}
