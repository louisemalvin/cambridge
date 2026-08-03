//! Discovery metadata and reverse-control messages exposed by a mobile sender.

use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const PROTOCOL_VERSION: u32 = 2;
pub const SENDER_CONTROL_PORT: u16 = 53_555;
pub const PORT_UNASSIGNED: u16 = 0;
pub const MAX_MESSAGE_BYTES: u64 = 16 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SenderControlAction {
    Describe,
    DescribeResult,
    Start,
    StartResult,
    Stop,
    StopResult,
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
    pub action: SenderControlAction,
    pub sender_id: String,
    pub display_name: String,
    pub control_port: u16,
    pub availability: SenderAvailability,
}

impl SenderAdvertisement {
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.protocol_version == PROTOCOL_VERSION
            && self.action == SenderControlAction::DescribeResult
            && !self.sender_id.trim().is_empty()
            && !self.display_name.trim().is_empty()
            && self.control_port != PORT_UNASSIGNED
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SenderAvailability {
    Standby,
    Streaming,
    Busy,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartStreamRequest {
    pub protocol_version: u32,
    pub action: SenderControlAction,
    pub stream_id: Uuid,
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
            && self.action == SenderControlAction::Start
            && !self.stream_id.is_nil()
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
    pub action: SenderControlAction,
    pub stream_id: Uuid,
    pub sender_id: String,
    pub status: StartStreamStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pairing_token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StopStreamRequest {
    pub protocol_version: u32,
    pub action: SenderControlAction,
    pub stream_id: Uuid,
    pub receiver_id: String,
    pub pairing_token: String,
}

impl StopStreamRequest {
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.protocol_version == PROTOCOL_VERSION
            && self.action == SenderControlAction::Stop
            && !self.stream_id.is_nil()
            && !self.receiver_id.trim().is_empty()
            && !self.pairing_token.trim().is_empty()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StopStreamStatus {
    Stopped,
    AlreadyStopped,
    StaleStream,
    Rejected,
    InvalidRequest,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StopStreamResponse {
    pub protocol_version: u32,
    pub action: SenderControlAction,
    pub stream_id: Uuid,
    pub sender_id: String,
    pub status: StopStreamStatus,
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
            &std::fs::read_to_string(format!("{ROOT}sender-control-v2.schema.json")).unwrap(),
        )
        .unwrap();
        let validator = jsonschema::validator_for(&schema).unwrap();

        for name in [
            "sender-describe-request.json",
            "sender-advertisement.json",
            "sender-start-request.json",
            "sender-start-response.json",
            "sender-start-approval-required-response.json",
            "sender-start-busy-response.json",
            "sender-start-permission-required-response.json",
            "sender-start-rejected-response.json",
            "sender-start-invalid-response.json",
            "sender-stop-request.json",
            "sender-stop-stopped-response.json",
            "sender-stop-already-stopped-response.json",
            "sender-stop-stale-response.json",
            "sender-stop-rejected-response.json",
            "sender-stop-invalid-response.json",
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
        let stop: StopStreamRequest = serde_json::from_str(
            &std::fs::read_to_string(format!("{ROOT}examples/sender-stop-request.json")).unwrap(),
        )
        .unwrap();
        assert!(stop.is_valid());
    }

    #[test]
    fn invalid_stream_ids_are_rejected() {
        let request = StartStreamRequest {
            protocol_version: PROTOCOL_VERSION,
            action: SenderControlAction::Start,
            stream_id: Uuid::nil(),
            receiver_id: "receiver".to_owned(),
            receiver_name: "Receiver".to_owned(),
            receiver_control_port: 5_001,
            pairing_token: None,
        };
        assert!(!request.is_valid());
        let decoded = serde_json::from_str::<StartStreamRequest>(
            r#"{"protocolVersion":2,"action":"start","streamId":"not-a-uuid","receiverId":"receiver","receiverName":"Receiver","receiverControlPort":5001}"#,
        );
        assert!(decoded.is_err());
    }
}
