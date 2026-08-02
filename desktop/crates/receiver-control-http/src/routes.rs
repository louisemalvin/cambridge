use axum::{
    routing::{get, post},
    Router,
};

use crate::state::ControlState;

mod handlers {
    use axum::{
        extract::{Path, State},
        http::StatusCode,
        Json,
    };
    use receiver_protocol::{HealthResponse, PrepareSessionRequest};

    use crate::{
        error::{lock_service, HttpControlError},
        ControlState,
    };

    pub async fn health() -> Json<HealthResponse> {
        Json(HealthResponse::default())
    }

    pub async fn capabilities(
        State(state): State<ControlState>,
    ) -> Result<Json<receiver_protocol::ReceiverCapabilities>, HttpControlError> {
        Ok(Json(lock_service(&state)?.capabilities()))
    }

    pub async fn prepare_session(
        State(state): State<ControlState>,
        Json(request): Json<PrepareSessionRequest>,
    ) -> Result<Json<receiver_protocol::PrepareSessionResponse>, HttpControlError> {
        Ok(Json(lock_service(&state)?.prepare_session(&request)?))
    }

    pub async fn session(
        State(state): State<ControlState>,
        Path(session_id): Path<String>,
    ) -> Result<Json<receiver_protocol::SessionStateResponse>, HttpControlError> {
        Ok(Json(lock_service(&state)?.session(&session_id)?))
    }

    pub async fn diagnostics(
        State(state): State<ControlState>,
        Path(session_id): Path<String>,
    ) -> Result<Json<receiver_core::ReceiverDiagnostics>, HttpControlError> {
        Ok(Json(lock_service(&state)?.diagnostics(&session_id)?))
    }

    pub async fn latest_diagnostics(
        State(state): State<ControlState>,
    ) -> Result<Json<receiver_core::ReceiverDiagnosticsRun>, HttpControlError> {
        Ok(Json(lock_service(&state)?.latest_diagnostics()?))
    }

    pub async fn stop_session(
        State(state): State<ControlState>,
        Path(session_id): Path<String>,
    ) -> Result<StatusCode, HttpControlError> {
        lock_service(&state)?.stop_session(&session_id)?;
        Ok(StatusCode::NO_CONTENT)
    }
}

pub fn router(state: ControlState) -> Router {
    Router::new()
        .route("/v1/health", get(handlers::health))
        .route("/v1/capabilities", get(handlers::capabilities))
        .route("/v1/sessions/prepare", post(handlers::prepare_session))
        .route("/v1/diagnostics/latest", get(handlers::latest_diagnostics))
        .route("/v1/sessions/{session_id}/diagnostics", get(handlers::diagnostics))
        .route("/v1/sessions/{session_id}", get(handlers::session).delete(handlers::stop_session))
        .with_state(state)
}
