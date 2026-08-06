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

Protocol v3 is the only accepted direct control version. The sender sends
one `hello` as the first frame after Connect with:

- protocol version
- session ID and generation
- H.264 codec identity
- coded width and height, which remain `2560x1440` for the normal 2K profile
- clockwise rotation in `{0, 90, 180, 270}`
- frame rate and bitrate

The receiver derives presentation dimensions from the coded dimensions and
rotation. Rotations `0` and `180` present landscape; rotations `90` and `270`
present portrait. The sender resolves this transform once before Connect and
does not change it during the session.

The source replies with `accepted`, the same identity, the media port, and
long-edge and short-edge limits. Width and height are deliberately not used as
landscape-only bounds.
The sender may send `stop`. The receiver sends `accepted` during setup and
`error` only when setup fails. There is no status feedback or media recovery
request in the product protocol.

## Media

Media is RTP over UDP using the H.264 payload format from RFC 6184. The sender
uses single-NAL packets and FU-A fragmentation with a fixed payload type and a
90 kHz RTP clock. Each encoded access unit is packetized in presentation order.

The receiver accepts only the configured UDP media port and the source address
bound to the control connection. RTP is best-effort. The receiver uses a
bounded reorder window, drops late or incomplete access units, keeps only the
newest decoded frame, and continues without retransmission or IDR requests.
The encoder's periodic keyframes provide passive decoder recovery.

## Identity and lifecycle

Every media packet belongs to exactly one `(sessionId, generation)` selected by
the current control connection. A new hello invalidates all older media. Media
loss never changes the session identity or starts a reconnect.

Before Connect, Android builds one immutable session contract containing the
profile, orientation, exact rotation, frame rate, bitrate, and camera metadata.
The activity is locked to the selected portrait or landscape axis while still
allowing its 180-degree reverse. The native NV12 shader applies the resolved
rotation to luma and chroma. A rotation during an active session does not
renegotiate; Stop and Connect creates a new generation.

The sender performs one connection attempt for each explicit Connect. A
control disconnect ends the session and reports failure. The sender does not
retry in the background.

The committed JSON examples are intentionally small valid messages for control
contract tests. They are not independent configuration sources.
