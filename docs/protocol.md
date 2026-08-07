# CamBridge Protocol

CamBridge uses protocol version **5**. The protocol identity is
`cambridge-stream`.

The authoritative machine-readable definition is
[`protocol/cambridge-stream-contract.json`](../protocol/cambridge-stream-contract.json).
The schema and examples in `protocol/` are validation fixtures for the same
contract.

## Connections

The sender uses two connections to the receiver:

```text
sender → length-prefixed JSON over TCP → receiver
sender → RFC 6184 H.264 RTP over UDP → receiver
```

Control messages use a big-endian 32-bit length followed by UTF-8 JSON. The
default control port is `55031`; the media port is the control port plus the
contract media-port offset, `55032` by default.

When mDNS/Avahi is available, the receiver advertises `_cambridge._tcp`.
Android can discover the service and probe it with a side-effect-free
capabilities request before starting a stream.

## Session messages

The control exchange uses these message types:

- `probe` and `capabilities` discover the receiver and supported profiles.
- `hello` proposes one immutable stream session.
- `accepted` returns the negotiated media port and receiver limits.
- `stop` ends the active session.
- `error` reports a rejected or invalid request.

Each session includes a session ID and monotonic generation. Resolution, frame
rate, codec, bitrate, and rotation are fixed for the session; changing them
requires a new Start after Stop.

## Video profiles

Normal product profiles are:

| Profile | Dimensions | Frame rate | Target bitrate |
| --- | ---: | ---: | ---: |
| `1080p15` | 1920×1080 | 15 fps | 4 Mbps |
| `1080p30` | 1920×1080 | 30 fps | 8 Mbps |
| `2k15` | 2560×1440 | 15 fps | 9 Mbps |
| `2k30` | 2560×1440 | 30 fps | 18 Mbps |

The contract also contains `720p30` for automated runtime testing; it is not
a normal product quality choice.

The sender reports clockwise rotation as `0`, `90`, `180`, or `270` degrees.
Rotations of `90` and `270` present portrait geometry; the other rotations
present landscape geometry.

## Media behavior

Media is best-effort and one-way. The receiver uses a bounded reorder window
and drops late or incomplete access units instead of requesting retransmission,
media feedback, or an IDR frame. There is no automatic reconnect.

The receiver output is an OBS source texture. The wire protocol does not
require OBS or a virtual camera device, but the current supported receiver is
the Linux OBS plugin.
