use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};

use receiver_protocol::{
    MediaResponse, OutputResponse, PrepareSessionRequest, PrepareSessionResponse,
    ReceiverCapabilities, ReceiverSessionState, SessionStateResponse, Transport,
};
use uuid::Uuid;

use crate::{
    diagnostic_timestamp_ms, negotiate_codec, select_output_format, validate_config, LatencyConfig,
    MediaSessionConfig, ReceiverCapabilityProvider, ReceiverConfig, ReceiverDiagnostics,
    ReceiverDiagnosticsRun, ReceiverError, ReceiverSession, ReceiverState, DIAGNOSTICS_SCHEMA,
};

// The HTTP watchdog samples every 250 ms, so this retains about two and a half
// minutes while keeping completed-run memory bounded.
const MAX_DIAGNOSTIC_SNAPSHOT_COUNT: usize = 600;

pub trait MediaReceiver: Send {
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<u16, ReceiverError>;

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

    fn diagnostics(&self) -> Option<ReceiverDiagnostics> {
        None
    }
}

pub struct ReceiverService {
    config: ReceiverConfig,
    capabilities: ReceiverCapabilities,
    capability_provider: Box<dyn ReceiverCapabilityProvider>,
    media_receiver: Box<dyn MediaReceiver>,
    session: Option<ReceiverSession>,
    last_session_id: Option<Uuid>,
    timed_out_at: Option<Instant>,
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
            session: None,
            last_session_id: None,
            timed_out_at: None,
            diagnostic_snapshots: VecDeque::new(),
            last_diagnostics: None,
        })
    }

    pub fn capabilities(&mut self) -> ReceiverCapabilities {
        self.refresh_session();
        let mut capabilities = self.capabilities.clone();
        capabilities.session.active = self.session.is_some();
        capabilities
    }

    pub fn prepare_session(
        &mut self,
        request: &PrepareSessionRequest,
    ) -> Result<PrepareSessionResponse, ReceiverError> {
        if self.session.is_some() {
            return Err(ReceiverError::SessionConflict);
        }
        request
            .validate()
            .map_err(|error| ReceiverError::InvalidConfiguration(error.to_string()))?;
        let codec = negotiate_codec(
            &request.preferred_codecs,
            self.capability_provider.as_ref(),
            &request.profile,
        )?;
        let output_format =
            select_output_format(self.config.output_format, &request.profile, &self.capabilities)?;
        let session_id = Uuid::new_v4();
        let media_config = MediaSessionConfig {
            session_id,
            codec,
            profile: request.profile.clone(),
            bitrate_bps: request.bitrate_by_codec.for_codec(codec),
            media_port: self.config.media_port,
            output_format,
            latency: LatencyConfig {
                demux_latency_ms: self.config.latency.demux_latency_ms,
                output_queue_frames: self.config.latency.output_queue_frames,
            },
            udp_timeout_ms: self.config.udp_timeout_ms,
        };
        let media_port = self
            .media_receiver
            .prepare(media_config.clone())
            .map_err(|error| ReceiverError::MediaPreparation(error.to_string()))?;
        if let Err(error) = self.media_receiver.start() {
            let _ = self.media_receiver.stop();
            return Err(ReceiverError::MediaStart(error.to_string()));
        }
        let mut session = ReceiverSession::new(&media_config);
        session.state = self.media_receiver.state();
        self.last_session_id = Some(session_id);
        self.timed_out_at = None;
        self.diagnostic_snapshots.clear();
        self.session = Some(session.clone());
        Ok(PrepareSessionResponse {
            session_id: session_id.to_string(),
            selected_codec: codec,
            media: MediaResponse { transport: Transport::MpegTsUdp, port: media_port },
            profile: receiver_protocol::NegotiatedProfile {
                width: request.profile.width,
                height: request.profile.height,
                fps: request.profile.fps,
                bitrate_bps: media_config.bitrate_bps,
            },
            output: OutputResponse { pixel_format: output_format },
            warnings: Vec::new(),
        })
    }

    pub fn session(&mut self, session_id: &str) -> Result<SessionStateResponse, ReceiverError> {
        self.refresh_session();
        let session = self
            .session
            .as_ref()
            .ok_or_else(|| ReceiverError::SessionNotFound(session_id.to_owned()))?;
        if session.id.to_string() != session_id {
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        Ok(SessionStateResponse {
            session_id: session.id.to_string(),
            state: map_state(session.state),
            selected_codec: session.codec,
            decoder: session.decoder.clone(),
            received_bitrate_bps: session.received_bitrate_bps,
            timeout_count: session.timeout_count,
        })
    }

    pub fn diagnostics(&mut self, session_id: &str) -> Result<ReceiverDiagnostics, ReceiverError> {
        self.refresh_session();
        let session = self
            .session
            .as_ref()
            .ok_or_else(|| ReceiverError::SessionNotFound(session_id.to_owned()))?;
        if session.id.to_string() != session_id {
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        self.media_receiver.diagnostics().ok_or_else(|| {
            ReceiverError::GStreamer("receiver diagnostics are unavailable".to_owned())
        })
    }

    pub fn latest_diagnostics(&mut self) -> Result<ReceiverDiagnosticsRun, ReceiverError> {
        self.refresh_session();
        self.last_diagnostics.clone().ok_or(ReceiverError::DiagnosticsNotFound)
    }

    pub fn stop_session(&mut self, session_id: &str) -> Result<(), ReceiverError> {
        if self.session.is_none() {
            if self.last_session_id.is_some_and(|last_id| last_id.to_string() == session_id) {
                return Ok(());
            }
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        let current_id = self.session.as_ref().map(|session| session.id);
        if current_id.map_or(true, |id| id.to_string() != session_id) {
            return Err(ReceiverError::SessionNotFound(session_id.to_owned()));
        }
        if let Some(session) = self.session.as_mut() {
            session.state = ReceiverState::Stopping;
        }
        if let Some(diagnostics) = self.media_receiver.diagnostics() {
            self.record_diagnostic_snapshot(diagnostics);
        }
        let stop_result = self.media_receiver.stop();
        self.finalize_diagnostics(session_id);
        self.session = None;
        self.timed_out_at = None;
        stop_result.map_err(|error| ReceiverError::MediaStop(error.to_string()))
    }

    pub fn shutdown(&mut self) -> Result<(), ReceiverError> {
        let Some(session_id) = self.session.as_ref().map(|session| session.id.to_string()) else {
            return Ok(());
        };
        self.stop_session(&session_id)
    }

    pub fn state(&mut self) -> ReceiverState {
        self.refresh_session();
        self.session.as_ref().map_or(ReceiverState::Idle, |session| session.state)
    }

    pub fn config(&self) -> &ReceiverConfig {
        &self.config
    }

    fn refresh_session(&mut self) {
        if self.session.is_none() {
            return;
        }
        let media_state = self.media_receiver.state();
        if let Some(diagnostics) = self.media_receiver.diagnostics() {
            self.record_diagnostic_snapshot(diagnostics);
        }
        if media_state == ReceiverState::TimedOut {
            let timed_out_at = self.timed_out_at.get_or_insert_with(Instant::now);
            if timed_out_at.elapsed() >= Duration::from_millis(self.config.session_timeout_grace_ms)
            {
                let session_id = self.session.as_ref().map(|session| session.id.to_string());
                if let Some(session_id) = session_id {
                    let _ = self.stop_session(&session_id);
                }
                return;
            }
        } else {
            self.timed_out_at = None;
        }
        let Some(session) = self.session.as_mut() else {
            return;
        };
        session.state = media_state;
        session.decoder = self.media_receiver.decoder_name();
        session.received_bitrate_bps = self.media_receiver.received_bitrate_bps();
        session.timeout_count = self.media_receiver.timeout_count();
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

fn map_state(state: ReceiverState) -> ReceiverSessionState {
    match state {
        ReceiverState::Idle => ReceiverSessionState::Idle,
        ReceiverState::Prepared => ReceiverSessionState::Prepared,
        ReceiverState::WaitingForStream => ReceiverSessionState::WaitingForStream,
        ReceiverState::Receiving => ReceiverSessionState::Receiving,
        ReceiverState::TimedOut => ReceiverSessionState::TimedOut,
        ReceiverState::Stopping => ReceiverSessionState::Stopping,
        ReceiverState::Failed => ReceiverSessionState::Failed,
    }
}

#[cfg(test)]
#[path = "service_tests.rs"]
mod tests;
