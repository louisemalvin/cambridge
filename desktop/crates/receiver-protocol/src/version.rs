use serde::{Deserialize, Serialize};

use crate::error::ProtocolError;

pub type ProtocolVersion = u32;

pub const PROTOCOL_VERSION: ProtocolVersion = 1;

/// Validates that a request or response uses the supported protocol version.
///
/// # Errors
///
/// Returns [`ProtocolError::UnsupportedVersion`] for an unknown version.
pub fn validate_protocol_version(version: ProtocolVersion) -> Result<(), ProtocolError> {
    if version == PROTOCOL_VERSION {
        Ok(())
    } else {
        Err(ProtocolError::UnsupportedVersion(version))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProtocolStatus {
    Ready,
}
