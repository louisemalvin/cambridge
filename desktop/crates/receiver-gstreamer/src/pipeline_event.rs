use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::Instant,
};

use receiver_core::{
    diagnostic_timestamp_ms, ReceiverDiagnosticEvent, ReceiverDiagnosticEventKind, ReceiverEvent,
    ReceiverState,
};
use receiver_protocol::VideoCodec;
use uuid::Uuid;

const MAX_DIAGNOSTIC_EVENTS: usize = 512;
const FIRST_DIAGNOSTIC_SEQUENCE: u64 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PipelineEvent {
    StreamTimedOut,
    StreamResumed,
    FirstFrame,
    WrongStreamCodec { expected: VideoCodec, received: VideoCodec },
}

#[derive(Debug)]
pub(crate) struct PipelineObserver {
    pub(crate) session_id: Uuid,
    pub(crate) codec: VideoCodec,
    pub(crate) state: Mutex<ReceiverState>,
    pub(crate) events: Mutex<Vec<ReceiverEvent>>,
    started_at: Instant,
    diagnostics: Mutex<VecDeque<ReceiverDiagnosticEvent>>,
    next_sequence: AtomicU64,
    transport_connected: AtomicBool,
}

impl PipelineObserver {
    pub(crate) fn new(session_id: Uuid, codec: VideoCodec) -> Arc<Self> {
        let observer = Arc::new(Self {
            session_id,
            codec,
            state: Mutex::new(ReceiverState::Prepared),
            events: Mutex::new(Vec::new()),
            started_at: Instant::now(),
            diagnostics: Mutex::new(VecDeque::new()),
            next_sequence: AtomicU64::new(FIRST_DIAGNOSTIC_SEQUENCE),
            transport_connected: AtomicBool::new(false),
        });
        observer.set_state(ReceiverState::Prepared);
        observer
    }

    pub(crate) fn set_state(&self, state: ReceiverState) {
        if let Ok(mut current) = self.state.lock() {
            *current = state;
        }
        if let Ok(mut events) = self.events.lock() {
            events.push(ReceiverEvent::StateChanged {
                session_id: self.session_id.to_string(),
                state,
            });
        }
        tracing::info!(
            event = "receiver_state_changed",
            session_id = %self.session_id,
            state = ?state,
            "receiver state changed"
        );
        self.record_diagnostic(ReceiverDiagnosticEventKind::StateChanged { state });
    }

    pub(crate) fn on_timeout(&self, timeout_count: u64) {
        self.transport_connected.store(false, Ordering::Relaxed);
        self.set_state(ReceiverState::TimedOut);
        if let Ok(mut events) = self.events.lock() {
            events.push(ReceiverEvent::StreamTimedOut {
                session_id: self.session_id.to_string(),
                timeout_count,
            });
        }
        tracing::warn!(
            event = "receiver_stream_timed_out",
            session_id = %self.session_id,
            timeout_count,
            "receiver stream timed out"
        );
        self.record_diagnostic(ReceiverDiagnosticEventKind::StreamTimedOut { timeout_count });
    }

    pub(crate) fn on_transport_connected(&self) {
        self.transport_connected.store(true, Ordering::Relaxed);
        tracing::info!(
            event = "receiver_transport_connected",
            session_id = %self.session_id,
            "receiver accepted an SRT caller"
        );
    }

    pub(crate) fn on_transport_rejected(&self) {
        tracing::warn!(
            event = "receiver_transport_rejected",
            session_id = %self.session_id,
            "receiver rejected an SRT caller"
        );
    }

    pub(crate) fn on_frame(&self, resumed: bool) {
        self.transport_connected.store(true, Ordering::Relaxed);
        let was_timed_out = self.state() == ReceiverState::TimedOut;
        let was_receiving = self.state() == ReceiverState::Receiving;
        if !was_receiving {
            self.set_state(ReceiverState::Receiving);
        }
        if let Ok(mut events) = self.events.lock() {
            if resumed || was_timed_out {
                events
                    .push(ReceiverEvent::StreamResumed { session_id: self.session_id.to_string() });
            } else if !was_receiving {
                events.push(ReceiverEvent::FirstFrame {
                    session_id: self.session_id.to_string(),
                    codec: self.codec,
                });
            }
        }
        if resumed || was_timed_out {
            tracing::info!(
                event = "receiver_stream_resumed",
                session_id = %self.session_id,
                "receiver stream resumed"
            );
            self.record_diagnostic(ReceiverDiagnosticEventKind::StreamResumed);
        } else if !was_receiving {
            tracing::info!(
                event = "receiver_first_frame",
                session_id = %self.session_id,
                codec = %self.codec,
                "receiver decoded first frame"
            );
            self.record_diagnostic(ReceiverDiagnosticEventKind::FirstFrame { codec: self.codec });
        }
    }

    pub(crate) fn on_wrong_codec(&self, received: VideoCodec) {
        self.transport_connected.store(false, Ordering::Relaxed);
        self.set_state(ReceiverState::Failed);
        if let Ok(mut events) = self.events.lock() {
            events.push(ReceiverEvent::WrongStreamCodec {
                session_id: self.session_id.to_string(),
                expected: self.codec,
                received,
            });
        }
        tracing::error!(
            event = "receiver_wrong_stream_codec",
            session_id = %self.session_id,
            expected = %self.codec,
            received = %received,
            "receiver rejected an unexpected stream codec"
        );
        self.record_diagnostic(ReceiverDiagnosticEventKind::WrongStreamCodec {
            expected: self.codec,
            received,
        });
    }

    pub(crate) fn on_decoder_selected(&self, decoder: String) {
        tracing::info!(
            event = "receiver_decoder_selected",
            session_id = %self.session_id,
            decoder = %decoder,
            "receiver decoder selected"
        );
        self.record_diagnostic(ReceiverDiagnosticEventKind::DecoderSelected { decoder });
    }

    pub(crate) fn on_pipeline_warning(&self, source: &str, message: &str, continuity: bool) {
        let event = if continuity {
            ReceiverDiagnosticEventKind::ContinuityWarning {
                source: source.to_owned(),
                message: message.to_owned(),
            }
        } else {
            ReceiverDiagnosticEventKind::PipelineWarning {
                source: source.to_owned(),
                message: message.to_owned(),
            }
        };
        tracing::warn!(
            event = if continuity { "receiver_continuity_warning" } else { "receiver_pipeline_warning" },
            session_id = %self.session_id,
            source = %source,
            warning = %message,
            "receiver pipeline warning"
        );
        self.record_diagnostic(event);
    }

    pub(crate) fn on_pipeline_error(&self, source: String, message: String) {
        tracing::error!(
            event = "receiver_pipeline_error",
            session_id = %self.session_id,
            source = %source,
            error = %message,
            "receiver pipeline error"
        );
        self.record_diagnostic(ReceiverDiagnosticEventKind::PipelineError { source, message });
    }

    pub(crate) fn on_queue_pressure(&self, current_frames: u32, maximum_frames: u32) {
        tracing::warn!(
            event = "receiver_queue_pressure",
            session_id = %self.session_id,
            current_frames,
            maximum_frames,
            "receiver output queue reached its high watermark"
        );
        self.record_diagnostic(ReceiverDiagnosticEventKind::QueuePressure {
            current_frames,
            maximum_frames,
        });
    }

    pub(crate) fn state(&self) -> ReceiverState {
        self.state.lock().map_or(ReceiverState::Failed, |state| *state)
    }

    pub(crate) fn transport_connected(&self) -> bool {
        self.transport_connected.load(Ordering::Relaxed)
    }

    pub(crate) fn take_events(&self) -> Vec<ReceiverEvent> {
        self.events.lock().map_or_else(|_| Vec::new(), |mut events| std::mem::take(&mut *events))
    }

    pub(crate) fn diagnostics(&self) -> Vec<ReceiverDiagnosticEvent> {
        self.diagnostics
            .lock()
            .map_or_else(|_| Vec::new(), |events| events.iter().cloned().collect())
    }

    fn record_diagnostic(&self, kind: ReceiverDiagnosticEventKind) {
        let event = ReceiverDiagnosticEvent {
            sequence: self.next_sequence.fetch_add(1, Ordering::Relaxed),
            timestamp_ms: diagnostic_timestamp_ms(),
            elapsed_ms: u64::try_from(self.started_at.elapsed().as_millis()).unwrap_or(u64::MAX),
            kind,
        };
        if let Ok(mut events) = self.diagnostics.lock() {
            if events.len() >= MAX_DIAGNOSTIC_EVENTS {
                events.pop_front();
            }
            events.push_back(event);
        }
    }
}
