# Direct Android RTP to OBS contract

The only active protocol is `android-rtp-obs` in
[`direct-stream-contract.json`](direct-stream-contract.json). The JSON Schema
is [`direct-stream.schema.json`](direct-stream.schema.json), and the examples
under `examples/` are small control fixtures.

Protocol v2 is the only active direct version. The control socket is TCP with a
big-endian 32-bit length prefix followed by
UTF-8 JSON. Media is H.264 RTP over UDP unicast using the RFC 6184 payload
format. Control and media share one session ID and generation; stale sessions
are rejected.

The normal profile is 2K30 with a coded `2560x1440` frame. A portrait session
reports display geometry `1440x2560` and its clockwise rotation in the v2 hello;
the native source rotates both NV12 planes during presentation.

The sender sends `hello`, the source returns `accepted`, and either side may
send `status`, `request_idr`, or `stop`. The contract is deliberately video
only. The receiver output is the OBS source texture, not a virtual camera
device.

Transport bounds, profile definitions, protocol version, and message names are
defined in the contract JSON. Implementations keep typed named constants for
those values at their language boundaries and must validate them against the
contract fixtures.
