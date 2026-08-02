//! Versioned HTTP control contract for the Mobile Webcam receiver.

mod capabilities;
mod codec;
mod error;
mod health;
mod session;
mod version;

pub use capabilities::{
    MediaCapabilities, MediaPortAssignment, OutputCapabilities, ReceiverCapabilities,
    SessionCapabilities, VideoCodecCapability, MAXIMUM_CONCURRENT_SESSIONS,
};
pub use codec::{DecoderAcceleration, PixelFormat, Transport, VideoCodec};
pub use error::ProtocolError;
pub use health::HealthResponse;
pub use session::{
    BitrateByCodec, MediaResponse, NegotiatedProfile, OutputResponse, PrepareSessionRequest,
    PrepareSessionResponse, ReceiverSessionState, SessionStateResponse, VideoProfile,
};
pub use version::{validate_protocol_version, ProtocolStatus, ProtocolVersion, PROTOCOL_VERSION};

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const FIXTURE_ROOT: &str =
        concat!(env!("CARGO_MANIFEST_DIR"), "/../../../", "/protocol/examples/");
    const SCHEMA: &str =
        concat!(env!("CARGO_MANIFEST_DIR"), "/../../../", "/protocol/control-v1.schema.json");

    fn fixture(name: &str) -> Value {
        let path = format!("{FIXTURE_ROOT}{name}");
        let source = std::fs::read_to_string(&path).expect("fixture should be readable");
        serde_json::from_str(&source).expect("fixture should be valid JSON")
    }

    #[test]
    fn every_example_matches_schema() {
        let schema: Value = serde_json::from_str(
            &std::fs::read_to_string(SCHEMA).expect("schema should be readable"),
        )
        .expect("schema should be valid JSON");
        let validator = jsonschema::validator_for(&schema).expect("schema should compile");
        for name in [
            "health-response.json",
            "capabilities-response.json",
            "prepare-h264-request.json",
            "prepare-h264-response.json",
            "prepare-h265-request.json",
            "prepare-h265-response.json",
            "session-state-response.json",
        ] {
            let value = fixture(name);
            assert!(validator.is_valid(&value), "fixture did not match schema: {name}");
        }
    }

    #[test]
    fn rust_models_round_trip_all_examples() {
        let _: HealthResponse = serde_json::from_value(fixture("health-response.json")).unwrap();
        let _: ReceiverCapabilities =
            serde_json::from_value(fixture("capabilities-response.json")).unwrap();
        let h264_request: PrepareSessionRequest =
            serde_json::from_value(fixture("prepare-h264-request.json")).unwrap();
        let h265_request: PrepareSessionRequest =
            serde_json::from_value(fixture("prepare-h265-request.json")).unwrap();
        let _: PrepareSessionResponse =
            serde_json::from_value(fixture("prepare-h264-response.json")).unwrap();
        let _: PrepareSessionResponse =
            serde_json::from_value(fixture("prepare-h265-response.json")).unwrap();
        let _: SessionStateResponse =
            serde_json::from_value(fixture("session-state-response.json")).unwrap();
        assert_eq!(h264_request.validate(), Ok(()));
        assert_eq!(h265_request.validate(), Ok(()));
    }

    #[test]
    fn unknown_optional_fields_are_ignored() {
        let mut value = fixture("health-response.json");
        value["futureField"] = Value::String("ignored".to_owned());
        let response: HealthResponse = serde_json::from_value(value).unwrap();
        assert_eq!(response, HealthResponse::default());
    }

    #[test]
    fn unknown_protocol_version_is_rejected() {
        assert_eq!(validate_protocol_version(2), Err(ProtocolError::UnsupportedVersion(2)));
    }

    #[test]
    fn unknown_codec_is_rejected() {
        let result = serde_json::from_str::<VideoCodec>("\"av1\"");
        assert!(result.is_err());
    }
}
