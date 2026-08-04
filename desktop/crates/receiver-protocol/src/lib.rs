//! Versioned HTTP control contract for the Mobile Webcam receiver.

mod capabilities;
mod codec;
mod session;
mod v2;
mod version;

pub use capabilities::{
    OutputCapabilities, ReceiverCapabilities, SessionCapabilities, VideoCodecCapability,
    MAXIMUM_CONCURRENT_SESSIONS,
};
pub use codec::{DecoderAcceleration, PixelFormat, VideoCodec};
pub use session::VideoProfile;
pub use v2::{
    validate_v2_protocol_version, CreateSessionRequest, CreateSessionResponse, DemandEventV2,
    DemandStateV2, HealthResponseV2, OutputConfiguration, ProblemDetails, ProtocolStatusV2,
    ReceiverCapabilitiesV2, SessionMetrics, SessionStateV2, SessionStatusResponse, SrtEndpoint,
    SrtMode, SrtTransportCapabilities, SrtTransportKind, V2BitrateByCodec, V2Container,
    V2ErrorCode, V2ProtocolError, V2VideoConfiguration, V2VideoProfile, INITIAL_DEMAND_GENERATION,
    SRT_KEY_LENGTH_BYTES, SRT_PASSPHRASE_MAX_LENGTH, SRT_PASSPHRASE_MIN_LENGTH,
    V2_PROTOCOL_VERSION,
};
pub use version::ProtocolVersion;

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const FIXTURE_ROOT: &str =
        concat!(env!("CARGO_MANIFEST_DIR"), "/../../../", "/protocol/examples/");
    fn fixture(name: &str) -> Value {
        let path = format!("{FIXTURE_ROOT}{name}");
        let source = std::fs::read_to_string(&path).expect("fixture should be readable");
        serde_json::from_str(&source).expect("fixture should be valid JSON")
    }

    #[test]
    fn v2_examples_match_schema_and_round_trip() {
        let schema_path =
            concat!(env!("CARGO_MANIFEST_DIR"), "/../../../", "/protocol/control-v2.schema.json");
        let schema: Value = serde_json::from_str(
            &std::fs::read_to_string(schema_path).expect("v2 schema should be readable"),
        )
        .expect("v2 schema should be valid JSON");
        let validator = jsonschema::validator_for(&schema).expect("v2 schema should compile");
        for name in [
            "health-v2-response.json",
            "capabilities-v2-response.json",
            "create-session-h264-v2-request.json",
            "create-session-h264-v2-response.json",
            "session-state-v2-response.json",
            "demand-active-v2-event.json",
            "demand-inactive-v2-event.json",
        ] {
            let value = fixture(name);
            assert!(validator.is_valid(&value), "v2 fixture did not match schema: {name}");
        }

        let request: CreateSessionRequest = fixture_value("create-session-h264-v2-request.json");
        let response: CreateSessionResponse = fixture_value("create-session-h264-v2-response.json");
        let status: SessionStatusResponse = fixture_value("session-state-v2-response.json");
        let _: ReceiverCapabilitiesV2 = fixture_value("capabilities-v2-response.json");
        let _: HealthResponseV2 = fixture_value("health-v2-response.json");
        let active_demand: DemandEventV2 = fixture_value("demand-active-v2-event.json");
        let inactive_demand: DemandEventV2 = fixture_value("demand-inactive-v2-event.json");

        assert_eq!(request.validate(), Ok(()));
        assert_eq!(response.protocol_version, V2_PROTOCOL_VERSION);
        assert_eq!(status.state, SessionStateV2::Receiving);
        assert_eq!(response.transport.key_length_bytes, SRT_KEY_LENGTH_BYTES);
        assert!(response.transport.validate().is_ok());
        assert!(active_demand.validate().is_ok());
        assert!(inactive_demand.validate().is_ok());
    }

    fn fixture_value<T: serde::de::DeserializeOwned>(name: &str) -> T {
        serde_json::from_value(fixture(name)).expect("fixture should decode")
    }

    #[test]
    fn v2_rejects_invalid_transport_credentials() {
        let response: CreateSessionResponse = fixture_value("create-session-h264-v2-response.json");
        let mut endpoint = response.transport;
        endpoint.key_length_bytes = 16;
        assert_eq!(endpoint.validate(), Err(V2ProtocolError::InvalidSrtKeyLength(16)));

        endpoint.key_length_bytes = SRT_KEY_LENGTH_BYTES;
        endpoint.passphrase = "short".to_owned();
        assert_eq!(endpoint.validate(), Err(V2ProtocolError::InvalidSrtPassphrase));
    }
}
