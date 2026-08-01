use receiver_protocol::VideoCodec;

use crate::ReceiverState;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReceiverEvent {
    StateChanged { session_id: String, state: ReceiverState },
    StreamTimedOut { session_id: String, timeout_count: u64 },
    StreamResumed { session_id: String },
    FirstFrame { session_id: String, codec: VideoCodec },
    WrongStreamCodec { session_id: String, expected: VideoCodec, received: VideoCodec },
}
