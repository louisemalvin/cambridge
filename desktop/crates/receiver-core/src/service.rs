use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};

use receiver_protocol::{
    CreateSessionRequest, CreateSessionResponse, OutputConfiguration, ReceiverCapabilities,
    ReceiverCapabilitiesV2, SessionMetrics, SessionStateV2, SessionStatusResponse, SrtEndpoint,
    SrtMode, SrtTransportCapabilities, SrtTransportKind, V2Container, V2VideoConfiguration,
    V2VideoProfile, SRT_KEY_LENGTH_BYTES, V2_PROTOCOL_VERSION,
};
use uuid::Uuid;

use crate::{
    diagnostic_timestamp_ms, negotiate_codec, select_output_format, validate_config, LatencyConfig,
    MediaSessionConfig, ReceiverCapabilityProvider, ReceiverConfig, ReceiverDiagnostics,
    ReceiverDiagnosticsRun, ReceiverError, ReceiverSessionV2, ReceiverState,
    ReceiverTransportMetrics, DIAGNOSTICS_SCHEMA,
};

// The HTTP watchdog samples every 250 ms, so this retains about two and a half
// minutes while keeping completed-run memory bounded.
const MAX_DIAGNOSTIC_SNAPSHOT_COUNT: usize = 600;

pub trait MediaReceiver: Send {
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<(), ReceiverError>;

    fn start(&mut self) -> Result<(), ReceiverError>;

    fn stop(&mut self) -> Result<(), ReceiverError>;

    fn state(&self) -> ReceiverState;

    fn decoder_name(&self) -> Option<String> {
        None
    }

    fn received_bitrate_bps(&self) -> u32 {
        0
    }

    fn timeout_count(&self) -> u64 {
        0
    }

    fn transport_metrics(&self) -> Option<ReceiverTransportMetrics> {
        None
    }

    fn transport_connected(&self) -> bool {
        false
    }

    fn diagnostics(&self) -> Option<ReceiverDiagnostics> {
        None
    }
}

pub struct ReceiverService {
    config: ReceiverConfig,
    capabilities: ReceiverCapabilities,
    capability_provider: Box<dyn ReceiverCapabilityProvider>,
    media_receiver: Box<dyn MediaReceiver>,
    v2_session: Option<ReceiverSessionV2>,
    last_v2_session_id: Option<Uuid>,
    diagnostic_snapshots: VecDeque<ReceiverDiagnostics>,
    last_diagnostics: Option<ReceiverDiagnosticsRun>,
}

impl ReceiverService {
    pub fn new(
        config: ReceiverConfig,
        capability_provider: Box<dyn ReceiverCapabilityProvider>,
        media_receiver: Box<dyn MediaReceiver>,
    ) -> Result<Self, ReceiverError> {
        validate_config(&config)?;
        let capabilities = capability_provider.capabilities();
        Ok(Self {
            config,
            capabilities,
            capability_provider,
            media_receiver,
            v2_session: None,
            last_v2_session_id: None,
            diagnostic_snapshots: VecDeque::new(),
            last_diagnostics: None,
        })
    }

    pub fn capability_snapshot(&mut self) -> ReceiverCapabilities {
        let mut capabilities = self.capabilities.clone();
        capabilities.session.active = self.v2_session.is_some();
        capabilities
    }

    pub fn capabilities_v2(&mut self) -> Result<ReceiverCapabilitiesV2, ReceiverError> {
        self.refresh_v2_session();
        let output_format = select_output_format(
            self.config.output_format,
            &self.config.output_profile,
            &self.capabilities,
        )?;
        Ok(ReceiverCapabilitiesV2 {
            protocol_version: V2_PROTOCOL_VERSION,
            transport: SrtTransportCapabilities {
                kind: SrtTransportKind::Srt,
                modes: vec![SrtMode::Caller],
                key_length_bytes: SRT_KEY_LENGTH_BYTES,
            },
            video_codecs: self
                .capabilities
                .video_codecs
                .iter()
                .filter(|codec| codec.supported)
                .map(|codec| codec.codec)
                .collect(),
            output_profile: V2VideoProfile {
                width: self.config.output_profile.width,
                height: self.config.output_profile.height,
                fps: self.config.output_profile.fps,
            },
            output: OutputConfiguration { pixel_format: output_format },
            maximum_concurrent_sessions: receiver_protocol::MAXIMUM_CONCURRENT_SESSIONS,
            active: self.v2_session.is_some(),
        })
    }

    pub fn create_session_v2(
        &mut self,
        request: &CreateSessionRequest,
    ) -> Result<CreateSessionResponse, ReceiverError> {
        self.refresh_v2_session();
        if self.v2_session.is_some() {
            return Err(ReceiverError::SessionConflict);
        }
        request.validate().map_err(|error| ReceiverError::V2Protocol(error.to_string()))?;
        let requested_profile = receiver_protocol::VideoProfile {
            width: request.profile.width,
            height: request.profile.height,
            fps: request.profile.fps,
        };
        if requested_profile != self.config.output_profile {
            return Err(ReceiverError::UnsupportedProfile {
                codec: request.preferred_codecs[0],
                profile: requested_profile,
            });
        }
        let codec = negotiate_codec(
            &request.preferred_codecs,
            self.capability_provider.as_ref(),
            &self.config.output_profile,
        )?;
        let output_format = select_output_format(
            self.config.output_format,
            &self.config.output_profile,
            &self.capabilities,
        )?;
        let session_id = Uuid::new_v4();
        let endpoint = SrtEndpoint {
            kind: SrtTransportKind::Srt,
            mode: SrtMode::Caller,
            host: self.config.advertised_host.clone(),
            port: self.config.srt.listen_port,
            stream_id: format!("srt-{}", Uuid::new_v4().simple()),
            latency_ms: self.config.srt.latency_ms,
            key_length_bytes: SRT_KEY_LENGTH_BYTES,
            passphrase: generate_srt_passphrase(),
        };
        endpoint.validate().map_err(|error| ReceiverError::V2Protocol(error.to_string()))?;
        let video = V2VideoConfiguration {
            codec,
            container: V2Container::Mpegts,
            width: self.config.output_profile.width,
            height: self.config.output_profile.height,
            fps: self.config.output_profile.fps,
            bitrate_bps: receiver_bitrate(&self.config, codec),
        };
        let media_config = MediaSessionConfig {
            session_id,
            codec,
            profile: self.config.output_profile.clone(),
            bitrate_bps: video.bitrate_bps,
            output_format,
            latency: LatencyConfig {
                demux_latency_ms: self.config.latency.demux_latency_ms,
                output_queue_frames: self.config.latency.output_queue_frames,
            },
            transport_timeout_ms: self.config.srt.inactivity_timeout_ms,
            srt_endpoint: endpoint.clone(),
        };
        self.media_receiver
            .prepare(media_config)
            .map_err(|error| ReceiverError::MediaPreparation(error.to_string()))?;
        if let Err(error) = self.media_receiver.start() {
            let _ = self.media_receiver.stop();
            return Err(ReceiverError::MediaStart(error.to_string()));
        }
        let mut session =
            ReceiverSessionV2::new(session_id, video.clone(), output_format, endpoint.clone());
        session.state =
            map_v2_state(self.media_receiver.state(), self.media_receiver.transport_connected());
        self.last_v2_session_id = Some(session_id);
        self.diagnostic_snapshots.clear();
        self.v2_session = Some(session);
        Ok(CreateSessionResponse {
            protocol_version: V2_PROTOCOL_VERSION,
            session_id: session_id.to_string(),
            connect_deadline_ms: self.config.srt.connect_deadline_ms,
            reconnect_grace_ms: self.config.srt.reconnect_grace_ms,
            video,
            transport: endpoint,
            output: OutputConfiguration { pixel_format: output_format },
        })
    }

    pub fn session_v2(&mut self, session_id: &str) -> Result<SessionStatusResponse, ReceiverError> {
        self.refresh_v2_session();
        let session = self
            .v2_session
            .as_ref()
            .ok_or_else(|| ReceiverError::SessionNotFound(session_id.to_owned()))?;
        if session.id.to_string() != session_id {
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        Ok(SessionStatusResponse {
            protocol_version: V2_PROTOCOL_VERSION,
            session_id: session.id.to_string(),
            state: session.state,
            decoder: session.decoder.clone(),
            metrics: self.v2_metrics(session),
        })
    }

    pub fn diagnostics_v2(
        &mut self,
        session_id: &str,
    ) -> Result<ReceiverDiagnostics, ReceiverError> {
        self.refresh_v2_session();
        let session = self
            .v2_session
            .as_ref()
            .ok_or_else(|| ReceiverError::SessionNotFound(session_id.to_owned()))?;
        if session.id.to_string() != session_id {
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        self.media_receiver.diagnostics().ok_or_else(|| {
            ReceiverError::GStreamer("receiver diagnostics are unavailable".to_owned())
        })
    }

    pub fn latest_diagnostics_v2(&mut self) -> Result<ReceiverDiagnosticsRun, ReceiverError> {
        self.refresh_v2_session();
        self.last_diagnostics.clone().ok_or(ReceiverError::DiagnosticsNotFound)
    }

    pub fn stop_session_v2(&mut self, session_id: &str) -> Result<(), ReceiverError> {
        if self.v2_session.is_none() {
            if self.last_v2_session_id.is_some_and(|last_id| last_id.to_string() == session_id) {
                return Ok(());
            }
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        let current_id = self.v2_session.as_ref().map(|session| session.id);
        if current_id.map_or(true, |id| id.to_string() != session_id) {
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        if let Some(session) = self.v2_session.as_mut() {
            session.state = SessionStateV2::Stopping;
        }
        if let Some(diagnostics) = self.media_receiver.diagnostics() {
            self.record_diagnostic_snapshot(diagnostics);
        }
        let stop_result = self.media_receiver.stop();
        self.finalize_diagnostics(session_id);
        self.v2_session = None;
        stop_result.map_err(|error| ReceiverError::MediaStop(error.to_string()))
    }

    pub fn stop_active_session(&mut self) -> Result<(), ReceiverError> {
        if let Some(session_id) = self.v2_session.as_ref().map(|session| session.id.to_string()) {
            return self.stop_session_v2(&session_id);
        }
        Ok(())
    }

    pub fn shutdown(&mut self) -> Result<(), ReceiverError> {
        if let Some(session_id) = self.v2_session.as_ref().map(|session| session.id.to_string()) {
            return self.stop_session_v2(&session_id);
        }
        Ok(())
    }

    pub fn state(&mut self) -> ReceiverState {
        self.refresh_v2_session();
        self.v2_session.as_ref().map_or(ReceiverState::Idle, |session| match session.state {
            SessionStateV2::Idle | SessionStateV2::Expired => ReceiverState::Idle,
            SessionStateV2::Allocating => ReceiverState::Prepared,
            SessionStateV2::Listening | SessionStateV2::Connected => {
                ReceiverState::WaitingForStream
            }
            SessionStateV2::Receiving => ReceiverState::Receiving,
            SessionStateV2::Reconnecting => ReceiverState::TimedOut,
            SessionStateV2::Stopping => ReceiverState::Stopping,
            SessionStateV2::Failed => ReceiverState::Failed,
        })
    }

    pub fn config(&self) -> &ReceiverConfig {
        &self.config
    }

    fn refresh_v2_session(&mut self) {
        let Some(session_id) = self.v2_session.as_ref().map(|session| session.id) else {
            return;
        };
        if let Some(diagnostics) = self.media_receiver.diagnostics() {
            self.record_diagnostic_snapshot(diagnostics);
        }
        let media_state = self.media_receiver.state();
        let transport_connected = self.media_receiver.transport_connected();
        let now = Instant::now();
        let mut should_expire = false;
        if let Some(session) = self.v2_session.as_mut() {
            let previous_state = session.state;
            let next_state = map_v2_state(media_state, transport_connected);
            if next_state == SessionStateV2::Receiving {
                session.last_receiving_at = Some(now);
            }
            if matches!(next_state, SessionStateV2::Reconnecting)
                && !matches!(previous_state, SessionStateV2::Reconnecting)
            {
                session.reconnect_count = session.reconnect_count.saturating_add(1);
            }
            let deadline = if session.last_receiving_at.is_some() {
                self.config.srt.reconnect_grace_ms
            } else {
                self.config.srt.connect_deadline_ms
            };
            let since_activity = session.last_receiving_at.unwrap_or(session.created_at).elapsed();
            should_expire = matches!(
                next_state,
                SessionStateV2::Listening
                    | SessionStateV2::Connected
                    | SessionStateV2::Reconnecting
            ) && since_activity >= Duration::from_millis(deadline);
            session.state = if should_expire { SessionStateV2::Expired } else { next_state };
            session.decoder = self.media_receiver.decoder_name();
        }
        if should_expire {
            let _ = self.stop_session_v2(&session_id.to_string());
        }
    }

    fn v2_metrics(&self, session: &ReceiverSessionV2) -> SessionMetrics {
        let Some(diagnostics) = self.media_receiver.diagnostics() else {
            return SessionMetrics {
                reconnect_count: Some(session.reconnect_count),
                ..SessionMetrics::default()
            };
        };
        let transport_metrics = self.media_receiver.transport_metrics();
        SessionMetrics {
            bytes_received: Some(diagnostics.received_bytes),
            packets_received: transport_metrics
                .as_ref()
                .and_then(|metrics| metrics.packets_received),
            packets_lost: transport_metrics.as_ref().and_then(|metrics| metrics.packets_lost),
            packets_retransmitted: transport_metrics
                .as_ref()
                .and_then(|metrics| metrics.packets_retransmitted),
            packets_dropped: transport_metrics.as_ref().and_then(|metrics| metrics.packets_dropped),
            rtt_ms: transport_metrics.as_ref().and_then(|metrics| metrics.rtt_ms),
            decoded_frames: Some(diagnostics.decoded_frames),
            output_fps: diagnostics.observed_fps.and_then(rounded_fps),
            output_queue_depth: Some(diagnostics.output_queue.current_frames),
            reconnect_count: Some(session.reconnect_count),
        }
    }

    fn record_diagnostic_snapshot(&mut self, diagnostics: ReceiverDiagnostics) {
        if self.diagnostic_snapshots.len() == MAX_DIAGNOSTIC_SNAPSHOT_COUNT {
            self.diagnostic_snapshots.pop_front();
        }
        self.diagnostic_snapshots.push_back(diagnostics);
    }

    fn finalize_diagnostics(&mut self, session_id: &str) {
        if self.diagnostic_snapshots.is_empty() {
            return;
        }
        let snapshots: Vec<ReceiverDiagnostics> = self.diagnostic_snapshots.drain(..).collect();
        let started_at_ms = snapshots.first().map_or(0, |snapshot| snapshot.started_at_ms);
        self.last_diagnostics = Some(ReceiverDiagnosticsRun {
            schema: DIAGNOSTICS_SCHEMA.to_owned(),
            session_id: session_id.to_owned(),
            started_at_ms,
            completed_at_ms: diagnostic_timestamp_ms(),
            snapshots,
        });
    }
}

fn receiver_bitrate(config: &ReceiverConfig, codec: receiver_protocol::VideoCodec) -> u32 {
    match codec {
        receiver_protocol::VideoCodec::H264 => config.h264_bitrate_bps,
        receiver_protocol::VideoCodec::H265 => config.h265_bitrate_bps,
    }
}

#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn rounded_fps(fps: f64) -> Option<u32> {
    if !fps.is_finite() || fps < 0.0 || fps > f64::from(u32::MAX) {
        return None;
    }
    Some(fps.round() as u32)
}

fn generate_srt_passphrase() -> String {
    format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple())
}

fn map_v2_state(state: ReceiverState, transport_connected: bool) -> SessionStateV2 {
    match state {
        ReceiverState::Idle => SessionStateV2::Expired,
        ReceiverState::Prepared | ReceiverState::WaitingForStream => {
            if transport_connected {
                SessionStateV2::Connected
            } else {
                SessionStateV2::Listening
            }
        }
        ReceiverState::Receiving => SessionStateV2::Receiving,
        ReceiverState::TimedOut => SessionStateV2::Reconnecting,
        ReceiverState::Stopping => SessionStateV2::Stopping,
        ReceiverState::Failed => SessionStateV2::Failed,
    }
}

#[cfg(test)]
#[path = "service_tests.rs"]
mod tests;
