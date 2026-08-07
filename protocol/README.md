# CamBridge Protocol

The active wire protocol is version 4. Its compatibility name is
`direct-webcam`, defined in [`direct-stream-contract.json`](direct-stream-contract.json).
The human-readable specification is [`docs/protocol.md`](../docs/protocol.md).

The JSON Schema and examples in this directory are validation fixtures for the
same contract. The control plane is big-endian length-prefixed JSON over TCP;
the media plane is RFC 6184 H.264 RTP over UDP. The sender and receiver share a
session ID and generation, and stale sessions are rejected.

The wire identifier, service type, message names, and persisted compatibility
values must remain stable unless the protocol version and fixtures change
together.
