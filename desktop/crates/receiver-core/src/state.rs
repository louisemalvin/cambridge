use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReceiverState {
    #[default]
    Idle,
    Prepared,
    WaitingForStream,
    Receiving,
    TimedOut,
    Stopping,
    Failed,
}

impl ReceiverState {
    pub const fn is_active(self) -> bool {
        !matches!(self, Self::Idle | Self::Failed)
    }
}
