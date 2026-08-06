# Direct stream protocol

The machine-readable contract is in
[`protocol/direct-stream-contract.json`](../protocol/direct-stream-contract.json)
and its validation schema is in
[`protocol/direct-stream.schema.json`](../protocol/direct-stream.schema.json).
The Android and native implementations use the same protocol version and
bounded transport values.

## Control

Control is a TCP stream of big-endian 32-bit length-prefixed UTF-8 JSON
messages. A frame must be positive and within the configured control-message
limit.

Protocol v2 is the only accepted direct control version. The sender sends
one `hello` as the first frame after Connect with:

- protocol version
- session ID and generation
- H.264 codec identity
- coded width and height, which remain `2560x1440` for the normal 2K profile
- display width and height, which become `1440x2560` for portrait
- clockwise rotation in `{0, 90, 180, 270}`
- frame rate and bitrate

The source replies with `accepted`, the same identity, the media port, and
long-edge and short-edge limits. Width and height are deliberately not used as
landscape-only bounds.
During the session, either side may send `status`, `request_idr`, or `stop`.
Unknown message types are ignored only after common identity fields validate.

## Media

Media is RTP over UDP using the H.264 payload format from RFC 6184. The sender
uses single-NAL packets and FU-A fragmentation with a fixed payload type and a
90 kHz RTP clock. Each encoded access unit is packetized in presentation order.

The receiver accepts only the configured UDP media port and the source address
bound to the control connection. The RTP layer reorders a bounded window, waits
for a bounded deadline, and requests an IDR after loss or an incomplete access
unit.

## Identity and recovery

Every media packet belongs to exactly one `(sessionId, generation)` selected by
the current control connection. A new hello invalidates all older media. The
native source requests an IDR when the frame mailbox becomes stale, when RTP
loss is detected, or when a decoder queue bound is reached.

Android derives one immutable session transform from display rotation, camera
sensor orientation, and lens facing when Connect is pressed. The native NV12
shader applies the same texture-coordinate rotation to luma and chroma. A
rotation during an active session does not renegotiate; Stop and Connect
creates a new generation.

The committed JSON examples are intentionally small valid messages for control
contract tests. They are not independent configuration sources.
