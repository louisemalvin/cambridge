use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use receiver_core::ReceiverError;
use receiver_protocol::{ProblemDetails, V2ErrorCode, V2_PROTOCOL_VERSION};
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum HttpControlError {
    #[error("receiver service lock is unavailable")]
    ServiceUnavailable,
    #[error(transparent)]
    Receiver(#[from] ReceiverError),
    #[error("unauthorized v2 request")]
    V2Unauthorized,
    #[error(transparent)]
    V2Receiver(ReceiverError),
}

#[derive(Debug, Serialize)]
struct ErrorBody {
    error: String,
    code: &'static str,
}

impl HttpControlError {
    fn status_and_code(&self) -> (StatusCode, &'static str) {
        match self {
            Self::ServiceUnavailable => (StatusCode::SERVICE_UNAVAILABLE, "service_unavailable"),
            Self::Receiver(error) => match error {
                ReceiverError::InvalidConfiguration(_)
                | ReceiverError::V2Protocol(_)
                | ReceiverError::NoCompatibleCodec { .. }
                | ReceiverError::UnsupportedCodec(_)
                | ReceiverError::UnsupportedProfile { .. }
                | ReceiverError::WrongStreamCodec { .. } => {
                    (StatusCode::BAD_REQUEST, "invalid_request")
                }
                ReceiverError::SessionConflict => (StatusCode::CONFLICT, "session_conflict"),
                ReceiverError::SessionNotFound(_) => (StatusCode::NOT_FOUND, "session_not_found"),
                ReceiverError::DiagnosticsNotFound => {
                    (StatusCode::NOT_FOUND, "diagnostics_not_found")
                }
                ReceiverError::MediaPreparation(_)
                | ReceiverError::MediaStart(_)
                | ReceiverError::MediaStop(_)
                | ReceiverError::OutputConsumer(_)
                | ReceiverError::PermissionDenied(_)
                | ReceiverError::GStreamer(_) => {
                    (StatusCode::INTERNAL_SERVER_ERROR, "receiver_failure")
                }
            },
            Self::V2Unauthorized => (StatusCode::UNAUTHORIZED, "unauthorized"),
            Self::V2Receiver(error) => match error {
                ReceiverError::SessionConflict => (StatusCode::CONFLICT, "receiver_busy"),
                ReceiverError::NoCompatibleCodec { .. }
                | ReceiverError::UnsupportedCodec(_)
                | ReceiverError::WrongStreamCodec { .. } => {
                    (StatusCode::BAD_REQUEST, "unsupported_codec")
                }
                ReceiverError::UnsupportedProfile { .. } => {
                    (StatusCode::BAD_REQUEST, "unsupported_profile")
                }
                ReceiverError::SessionNotFound(_) | ReceiverError::DiagnosticsNotFound => {
                    (StatusCode::NOT_FOUND, "invalid_session")
                }
                ReceiverError::MediaPreparation(_)
                | ReceiverError::MediaStart(_)
                | ReceiverError::MediaStop(_)
                | ReceiverError::GStreamer(_) => {
                    (StatusCode::SERVICE_UNAVAILABLE, "transport_unavailable")
                }
                ReceiverError::OutputConsumer(_) | ReceiverError::PermissionDenied(_) => {
                    (StatusCode::SERVICE_UNAVAILABLE, "output_unavailable")
                }
                ReceiverError::InvalidConfiguration(_) | ReceiverError::V2Protocol(_) => {
                    (StatusCode::BAD_REQUEST, "invalid_request")
                }
            },
        }
    }
}

impl IntoResponse for HttpControlError {
    fn into_response(self) -> Response {
        let (status, code) = self.status_and_code();
        if matches!(self, Self::V2Unauthorized | Self::V2Receiver(_)) {
            let problem_code = match code {
                "unauthorized" => V2ErrorCode::Unauthorized,
                "receiver_busy" => V2ErrorCode::ReceiverBusy,
                "unsupported_codec" => V2ErrorCode::UnsupportedCodec,
                "unsupported_profile" => V2ErrorCode::UnsupportedProfile,
                "invalid_session" => V2ErrorCode::InvalidSession,
                "output_unavailable" => V2ErrorCode::OutputUnavailable,
                _ => V2ErrorCode::TransportUnavailable,
            };
            return (
                status,
                Json(ProblemDetails {
                    code: problem_code,
                    detail: self.to_string(),
                    protocol_version: V2_PROTOCOL_VERSION,
                }),
            )
                .into_response();
        }
        (status, Json(ErrorBody { error: self.to_string(), code })).into_response()
    }
}

impl HttpControlError {
    pub(crate) fn v2_receiver(error: ReceiverError) -> Self {
        Self::V2Receiver(error)
    }
}

pub(crate) fn lock_service(
    state: &crate::ControlState,
) -> Result<std::sync::MutexGuard<'_, receiver_core::ReceiverService>, HttpControlError> {
    state.service().lock().map_err(|_| HttpControlError::ServiceUnavailable)
}
