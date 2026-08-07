# CamBridge Protocol

The active wire protocol is version 5 with identity `cambridge-stream`,
defined in [`cambridge-stream-contract.json`](cambridge-stream-contract.json).
The human-readable specification is [`docs/protocol.md`](../docs/protocol.md).

The JSON Schema and examples in this directory validate the same contract. The
control plane is big-endian length-prefixed JSON over TCP; the media plane is
RFC 6184 H.264 RTP over UDP. The sender and receiver share a session ID and
generation, and stale sessions are rejected.

When the wire protocol changes, update the version, schema, examples, and both
implementations together.
