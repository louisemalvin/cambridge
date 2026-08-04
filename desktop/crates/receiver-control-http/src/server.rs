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
    use receiver_core::VirtualCameraDemand;
    use receiver_core::{
        DiagnosticPhase, FrameIntervalStatistics, MediaReceiver, MediaSessionConfig,
        QueueDiagnostics, ReceiverConfig, ReceiverDiagnostics, ReceiverError, ReceiverService,
        ReceiverState, StaticCapabilityProvider, DIAGNOSTICS_SCHEMA,
    };
    use receiver_protocol::{
        DecoderAcceleration, OutputCapabilities, ReceiverCapabilities, SessionCapabilities,
        VideoCodec, VideoCodecCapability, MAXIMUM_CONCURRENT_SESSIONS,
    };
    use tower::ServiceExt;

    struct FakeReceiver;

    impl MediaReceiver for FakeReceiver {
        fn prepare(&mut self, _config: MediaSessionConfig) -> Result<(), ReceiverError> {
            Ok(())
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
                    width: 1_280,
                    height: 720,
                    fps: 30,
                },
                target_bitrate_bps: 4_000_000,
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
            session: SessionCapabilities {
                maximum_concurrent_sessions: MAXIMUM_CONCURRENT_SESSIONS,
                active: false,
            },
        };
        let provider = StaticCapabilityProvider::new(capabilities);
        ReceiverService::new(ReceiverConfig::default(), Box::new(provider), Box::new(FakeReceiver))
            .unwrap()
    }

    fn create_request() -> receiver_protocol::CreateSessionRequest {
        receiver_protocol::CreateSessionRequest {
            protocol_version: receiver_protocol::V2_PROTOCOL_VERSION,
            preferred_codecs: vec![VideoCodec::H264],
            profile: receiver_protocol::V2VideoProfile { width: 1_280, height: 720, fps: 30 },
            bitrate_by_codec: receiver_protocol::V2BitrateByCodec {
                h264: 4_000_000,
                h265: 7_000_000,
            },
        }
    }

    #[tokio::test]
    async fn v2_health_is_public_and_legacy_routes_are_absent() {
        let app = router(ControlState::new(service()));
        let health = app
            .clone()
            .oneshot(axum::http::Request::builder().uri("/v2/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(health.status(), StatusCode::OK);

        let legacy = app
            .oneshot(
                axum::http::Request::builder().uri("/legacy/health").body(Body::empty()).unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(legacy.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn demand_subscription_starts_with_the_current_standby_snapshot() {
        let state = ControlState::new(service());
        let app = router(state.clone());
        state.publish_demand(VirtualCameraDemand::Active { consumer_count: 1 });
        let response = app
            .oneshot(
                axum::http::Request::builder()
                    .uri("/v2/demand/subscribe")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers().get("content-type").unwrap(), "text/event-stream");
        let frame = response.into_body().frame().await.unwrap().unwrap();
        let data = frame.into_data().unwrap();
        let event = String::from_utf8(data.to_vec()).unwrap();
        assert!(event.contains("\"demand\":\"active\""));
        assert!(event.contains("\"generation\":1"));
    }

    #[tokio::test]
    async fn v2_session_routes_report_diagnostics_and_support_idempotent_delete() {
        let app = router(ControlState::new(service()));
        let request = create_request();
        let created = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .method("POST")
                    .uri("/v2/sessions")
                    .header("content-type", "application/json")
                    .body(Body::from(serde_json::to_vec(&request).unwrap()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(created.status(), StatusCode::CREATED);
        let location = created.headers().get("location").unwrap().to_str().unwrap().to_owned();
        let body = created.into_body().collect().await.unwrap().to_bytes();
        let response: receiver_protocol::CreateSessionResponse =
            serde_json::from_slice(&body).unwrap();
        assert_eq!(location, format!("/v2/sessions/{}", response.session_id));

        let capabilities = app
            .clone()
            .oneshot(
                axum::http::Request::builder().uri("/v2/capabilities").body(Body::empty()).unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(capabilities.status(), StatusCode::OK);

        let diagnostics = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri(format!("/v2/sessions/{}/diagnostics", response.session_id))
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
                    .uri(format!("/v2/sessions/{}", response.session_id))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(stopped.status(), StatusCode::NO_CONTENT);

        let repeated_stop = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .method("DELETE")
                    .uri(format!("/v2/sessions/{}", response.session_id))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(repeated_stop.status(), StatusCode::NO_CONTENT);

        let latest = app
            .oneshot(
                axum::http::Request::builder()
                    .uri("/v2/diagnostics/latest")
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
