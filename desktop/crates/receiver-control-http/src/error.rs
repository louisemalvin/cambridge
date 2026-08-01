use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use receiver_core::ReceiverError;
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum HttpControlError {
    #[error("receiver service lock is unavailable")]
    ServiceUnavailable,
    #[error(transparent)]
    Receiver(#[from] ReceiverError),
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
                | ReceiverError::NoCompatibleCodec { .. }
                | ReceiverError::UnsupportedCodec(_)
                | ReceiverError::UnsupportedProfile { .. }
                | ReceiverError::WrongStreamCodec { .. } => {
                    (StatusCode::BAD_REQUEST, "invalid_request")
                }
                ReceiverError::SessionConflict => (StatusCode::CONFLICT, "session_conflict"),
                ReceiverError::SessionNotFound(_) => (StatusCode::NOT_FOUND, "session_not_found"),
                ReceiverError::MediaPreparation(_)
                | ReceiverError::MediaStart(_)
                | ReceiverError::MediaStop(_)
                | ReceiverError::OutputConsumer(_)
                | ReceiverError::PermissionDenied(_)
                | ReceiverError::GStreamer(_) => {
                    (StatusCode::INTERNAL_SERVER_ERROR, "receiver_failure")
                }
            },
        }
    }
}

impl IntoResponse for HttpControlError {
    fn into_response(self) -> Response {
        let (status, code) = self.status_and_code();
        (status, Json(ErrorBody { error: self.to_string(), code })).into_response()
    }
}

pub(crate) fn lock_service(
    state: &crate::ControlState,
) -> Result<std::sync::MutexGuard<'_, receiver_core::ReceiverService>, HttpControlError> {
    state.service().lock().map_err(|_| HttpControlError::ServiceUnavailable)
}
