use receiver_core::{ReceiverEvent, ReceiverState};
use receiver_protocol::VideoCodec;
use std::sync::{Arc, Mutex};
use uuid::Uuid;

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
}

impl PipelineObserver {
    pub(crate) fn new(session_id: Uuid, codec: VideoCodec) -> Arc<Self> {
        Arc::new(Self {
            session_id,
            codec,
            state: Mutex::new(ReceiverState::Prepared),
            events: Mutex::new(Vec::new()),
        })
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
    }

    pub(crate) fn on_timeout(&self, timeout_count: u64) {
        self.set_state(ReceiverState::TimedOut);
        if let Ok(mut events) = self.events.lock() {
            events.push(ReceiverEvent::StreamTimedOut {
                session_id: self.session_id.to_string(),
                timeout_count,
            });
        }
    }

    pub(crate) fn on_frame(&self, resumed: bool) {
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
    }

    pub(crate) fn on_wrong_codec(&self, received: VideoCodec) {
        self.set_state(ReceiverState::Failed);
        if let Ok(mut events) = self.events.lock() {
            events.push(ReceiverEvent::WrongStreamCodec {
                session_id: self.session_id.to_string(),
                expected: self.codec,
                received,
            });
        }
    }

    pub(crate) fn state(&self) -> ReceiverState {
        self.state.lock().map_or(ReceiverState::Failed, |state| *state)
    }

    pub(crate) fn take_events(&self) -> Vec<ReceiverEvent> {
        self.events.lock().map_or_else(|_| Vec::new(), |mut events| std::mem::take(&mut *events))
    }
}
