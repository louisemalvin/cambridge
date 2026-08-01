use serde::{Deserialize, Serialize};

use crate::{ProtocolStatus, ProtocolVersion, PROTOCOL_VERSION};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthResponse {
    pub status: ProtocolStatus,
    pub protocol_version: ProtocolVersion,
}

impl Default for HealthResponse {
    fn default() -> Self {
        Self { status: ProtocolStatus::Ready, protocol_version: PROTOCOL_VERSION }
    }
}
