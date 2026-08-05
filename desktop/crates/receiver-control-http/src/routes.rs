use axum::{
    routing::{get, post},
    Router,
};

use crate::state::ControlState;

mod handlers {
    use std::convert::Infallible;

    use axum::{
        extract::{Path, State},
        http::{header, uri::Authority, HeaderMap, HeaderValue, StatusCode},
        response::sse::{Event, KeepAlive, Sse},
        Json,
    };
    use receiver_protocol::{
        CreateSessionRequest, CreateSessionResponse, DemandEventV2, HealthResponseV2,
        ReceiverCapabilitiesV2, SessionStatusResponse,
    };
    use tokio_stream::{wrappers::BroadcastStream, StreamExt};

    use crate::{
        error::{lock_service, HttpControlError},
        ControlState,
    };

    pub async fn health_v2() -> Json<HealthResponseV2> {
        Json(HealthResponseV2::default())
    }

    pub async fn demand_subscribe_v2(
        State(state): State<ControlState>,
        headers: HeaderMap,
    ) -> Result<Sse<impl tokio_stream::Stream<Item = Result<Event, Infallible>>>, HttpControlError>
    {
        authorize_v2(&state, &headers)?;
        let (snapshot, receiver) = state.subscribe_demand();
        let events = tokio_stream::once(snapshot)
            .chain(BroadcastStream::new(receiver).map_while(Result::ok))
            .map(|event| Ok::<Event, Infallible>(sse_event(&event)));
        Ok(Sse::new(events).keep_alive(KeepAlive::new().text(DEMAND_KEEPALIVE_TEXT)))
    }

    pub async fn capabilities_v2(
        State(state): State<ControlState>,
        headers: HeaderMap,
    ) -> Result<Json<ReceiverCapabilitiesV2>, HttpControlError> {
        authorize_v2(&state, &headers)?;
        Ok(Json(lock_service(&state)?.capabilities_v2().map_err(HttpControlError::v2_receiver)?))
    }

    pub async fn create_session_v2(
        State(state): State<ControlState>,
        headers: HeaderMap,
        Json(request): Json<CreateSessionRequest>,
    ) -> Result<(StatusCode, HeaderMap, Json<CreateSessionResponse>), HttpControlError> {
        authorize_v2(&state, &headers)?;
        let control_host = request_origin_host(&headers)?;
        let response = lock_service(&state)?
            .create_session_v2_from_control_origin(&request, &control_host)
            .map_err(HttpControlError::v2_receiver)?;
        let location = format!("/v2/sessions/{}", response.session_id);
        let mut response_headers = HeaderMap::new();
        let location_value = HeaderValue::from_str(&location).map_err(|_| {
            HttpControlError::V2Receiver(receiver_core::ReceiverError::InvalidConfiguration(
                "session location could not be encoded".to_owned(),
            ))
        })?;
        response_headers.insert(header::LOCATION, location_value);
        Ok((StatusCode::CREATED, response_headers, Json(response)))
    }

    pub async fn session_v2(
        State(state): State<ControlState>,
        headers: HeaderMap,
        Path(session_id): Path<String>,
    ) -> Result<Json<SessionStatusResponse>, HttpControlError> {
        authorize_v2(&state, &headers)?;
        Ok(Json(
            lock_service(&state)?.session_v2(&session_id).map_err(HttpControlError::v2_receiver)?,
        ))
    }

    pub async fn stop_session_v2(
        State(state): State<ControlState>,
        headers: HeaderMap,
        Path(session_id): Path<String>,
    ) -> Result<StatusCode, HttpControlError> {
        authorize_v2(&state, &headers)?;
        lock_service(&state)?
            .stop_session_v2(&session_id)
            .map_err(HttpControlError::v2_receiver)?;
        Ok(StatusCode::NO_CONTENT)
    }

    pub async fn diagnostics_v2(
        State(state): State<ControlState>,
        headers: HeaderMap,
        Path(session_id): Path<String>,
    ) -> Result<Json<receiver_core::ReceiverDiagnostics>, HttpControlError> {
        authorize_v2(&state, &headers)?;
        Ok(Json(
            lock_service(&state)?
                .diagnostics_v2(&session_id)
                .map_err(HttpControlError::v2_receiver)?,
        ))
    }

    pub async fn latest_diagnostics_v2(
        State(state): State<ControlState>,
        headers: HeaderMap,
    ) -> Result<Json<receiver_core::ReceiverDiagnosticsRun>, HttpControlError> {
        authorize_v2(&state, &headers)?;
        Ok(Json(
            lock_service(&state)?.latest_diagnostics_v2().map_err(HttpControlError::v2_receiver)?,
        ))
    }

    fn authorize_v2(state: &ControlState, headers: &HeaderMap) -> Result<(), HttpControlError> {
        let bearer = headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "));
        if state.v2_authorized(bearer) {
            Ok(())
        } else {
            Err(HttpControlError::V2Unauthorized)
        }
    }

    fn request_origin_host(headers: &HeaderMap) -> Result<String, HttpControlError> {
        let value = headers
            .get(header::HOST)
            .ok_or_else(|| invalid_control_origin("Host header is required"))?;
        let raw_host = value
            .to_str()
            .map_err(|_| invalid_control_origin("Host header must be valid ASCII"))?;
        let authority = raw_host
            .parse::<Authority>()
            .map_err(|_| invalid_control_origin("Host header must contain a valid authority"))?;
        let host = authority.host();
        if host.trim().is_empty() {
            return Err(invalid_control_origin("Host header must contain a host"));
        }
        Ok(host.to_owned())
    }

    fn invalid_control_origin(reason: &str) -> HttpControlError {
        HttpControlError::V2Receiver(receiver_core::ReceiverError::InvalidConfiguration(format!(
            "SRT host could not be derived from the control origin: {reason}"
        )))
    }

    fn sse_event(event: &DemandEventV2) -> Event {
        let data = serde_json::to_string(&event).expect("demand event serialization cannot fail");
        Event::default().event(DEMAND_EVENT_NAME).data(data)
    }

    const DEMAND_EVENT_NAME: &str = "demand";
    const DEMAND_KEEPALIVE_TEXT: &str = "keep-alive";
}

pub fn router(state: ControlState) -> Router {
    Router::new()
        .route("/v2/health", get(handlers::health_v2))
        .route("/v2/demand/subscribe", get(handlers::demand_subscribe_v2))
        .route("/v2/capabilities", get(handlers::capabilities_v2))
        .route("/v2/diagnostics/latest", get(handlers::latest_diagnostics_v2))
        .route("/v2/sessions", post(handlers::create_session_v2))
        .route(
            "/v2/sessions/{session_id}",
            get(handlers::session_v2).delete(handlers::stop_session_v2),
        )
        .route("/v2/sessions/{session_id}/diagnostics", get(handlers::diagnostics_v2))
        .with_state(state)
}
