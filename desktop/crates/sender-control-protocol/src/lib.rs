//! Discovery metadata and reverse-control messages exposed by a mobile sender.

use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;
pub const SENDER_CONTROL_PORT: u16 = 53_555;
pub const PORT_UNASSIGNED: u16 = 0;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SenderControlAction {
    Describe,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DescribeSenderRequest {
    pub protocol_version: u32,
    pub action: SenderControlAction,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SenderAdvertisement {
    pub protocol_version: u32,
    pub sender_id: String,
    pub display_name: String,
    pub control_port: u16,
}

impl SenderAdvertisement {
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.protocol_version == PROTOCOL_VERSION
            && !self.sender_id.trim().is_empty()
            && !self.display_name.trim().is_empty()
            && self.control_port != PORT_UNASSIGNED
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartStreamRequest {
    pub protocol_version: u32,
    pub receiver_id: String,
    pub receiver_name: String,
    pub receiver_control_port: u16,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pairing_token: Option<String>,
}

impl StartStreamRequest {
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.protocol_version == PROTOCOL_VERSION
            && !self.receiver_id.trim().is_empty()
            && !self.receiver_name.trim().is_empty()
            && self.receiver_control_port != PORT_UNASSIGNED
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StartStreamStatus {
    Accepted,
    ApprovalRequired,
    Rejected,
    Busy,
    CameraPermissionRequired,
    InvalidRequest,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartStreamResponse {
    pub protocol_version: u32,
    pub sender_id: String,
    pub status: StartStreamStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pairing_token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const ROOT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../../protocol/");

    #[test]
    fn examples_match_the_sender_control_schema() {
        let schema: Value = serde_json::from_str(
            &std::fs::read_to_string(format!("{ROOT}sender-control-v1.schema.json")).unwrap(),
        )
        .unwrap();
        let validator = jsonschema::validator_for(&schema).unwrap();

        for name in [
            "sender-describe-request.json",
            "sender-advertisement.json",
            "sender-start-request.json",
            "sender-start-response.json",
        ] {
            let value: Value = serde_json::from_str(
                &std::fs::read_to_string(format!("{ROOT}examples/{name}")).unwrap(),
            )
            .unwrap();
            assert!(validator.is_valid(&value), "fixture did not match schema: {name}");
        }
    }

    #[test]
    fn request_and_response_examples_round_trip() {
        let _: DescribeSenderRequest = serde_json::from_str(
            &std::fs::read_to_string(format!("{ROOT}examples/sender-describe-request.json"))
                .unwrap(),
        )
        .unwrap();
        let advertisement: SenderAdvertisement = serde_json::from_str(
            &std::fs::read_to_string(format!("{ROOT}examples/sender-advertisement.json")).unwrap(),
        )
        .unwrap();
        let request: StartStreamRequest = serde_json::from_str(
            &std::fs::read_to_string(format!("{ROOT}examples/sender-start-request.json")).unwrap(),
        )
        .unwrap();
        let _: StartStreamResponse = serde_json::from_str(
            &std::fs::read_to_string(format!("{ROOT}examples/sender-start-response.json")).unwrap(),
        )
        .unwrap();
        assert!(advertisement.is_valid());
        assert!(request.is_valid());
    }
}
