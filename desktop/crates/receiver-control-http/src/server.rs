use std::{future::Future, net::SocketAddr, time::Duration};

use axum::serve as axum_serve;
use thiserror::Error;
use tokio::net::TcpListener;

use crate::{router, ControlState};

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
        let mut interval = tokio::time::interval(Duration::from_millis(250));
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
        MediaReceiver, MediaSessionConfig, ReceiverConfig, ReceiverError, ReceiverService,
        ReceiverState, StaticCapabilityProvider,
    };
    use receiver_protocol::{
        DecoderAcceleration, MediaCapabilities, OutputCapabilities, ReceiverCapabilities,
        SessionCapabilities, VideoCodec, VideoCodecCapability,
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
    }

    fn service() -> ReceiverService {
        let capabilities = ReceiverCapabilities {
            protocol_version: 1,
            media: MediaCapabilities {
                transport: receiver_protocol::Transport::MpegTsUdp,
                default_port: 5000,
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

        let stopped = app
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
    }
}
