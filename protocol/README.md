# Direct Android RTP to OBS contract

The only active protocol is `android-rtp-obs` in
[`direct-stream-contract.json`](direct-stream-contract.json). The JSON Schema
is [`direct-stream.schema.json`](direct-stream.schema.json), and the examples
under `examples/` are small control fixtures.

Protocol v3 is the only active direct version. The control socket is TCP with a
big-endian 32-bit length prefix followed by
UTF-8 JSON. Media is H.264 RTP over UDP unicast using the RFC 6184 payload
format. Control and media share one session ID and generation; stale sessions
are rejected.

The normal profile is 2K30 with a coded `2560x1440` frame. The hello carries
the resolved clockwise rotation. Rotations `0` and `180` present landscape;
rotations `90` and `270` present portrait. The native source rotates both NV12
planes during presentation.

The sender sends `hello`, the source returns `accepted`, and the sender may
send `stop`. Media is best-effort: late or incomplete frames are dropped and
the stream continues. There is no retransmission, media feedback, or automatic
reconnect. The receiver output is the OBS source texture, not a virtual camera
device.

Transport bounds, profile definitions, protocol version, and message names are
defined in the contract JSON. Implementations keep typed named constants for
those values at their language boundaries and must validate them against the
contract fixtures.
