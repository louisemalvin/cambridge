# CamBridge Protocol

CamBridge uses protocol version **6**. The protocol identity is
`cambridge-stream`.

Protocol versions are strict compatibility boundaries. Version 6 is not
compatible with the version 5 sender or receiver, so both release artifacts
must be upgraded together.

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
capabilities request before starting a stream. The DNS-SD SRV record supplies
the exact control port; discovery does not scan or guess ports. Android probes
every resolved IPv4 address for each service plus the bounded `address<N>` IPv4
unicast candidates in its TXT metadata. The candidates let multi-homed receivers expose
LAN and VPN routes when multicast resolution returns only one interface.
Android deduplicates successful responses by receiver ID and requires an
explicit choice when no saved receiver matches and more than one receiver is
available. The default control port is used only for default or manually
configured endpoints that have no DNS-SD record.

## Session messages

The control exchange uses these message types:

- `probe` and `capabilities` discover receiver readiness, identity, and hard
  geometry limits. Capabilities do not advertise video presets.
- `hello` carries one immutable phone-authored stream session.
- `accepted` returns the negotiated media port and receiver limits.
- `stop` ends the active session.
- `error` reports a rejected or invalid request, or a terminal receiver media
  failure. After a terminal media failure, the receiver sends the error and
  closes the control session so the sender can release its resources.

Each session includes a session ID and monotonic generation. Resolution, frame
rate, codec, bitrate, and rotation are fixed for the session; changing them
requires a new Start after Stop.

## Video configuration ownership

The Android sender selects a camera/encoder-supported resolution, frame rate,
and bitrate. Those exact coded dimensions, FPS, and bitrate are carried in the
v6 `hello` and are immutable for the session. `profileId` remains an opaque,
sender-authored diagnostic mode ID; the receiver never looks it up.

The receiver validates only the wire and configured resource bounds. It either
accepts the exact values or returns an error; it does not negotiate presets or
silently downgrade the stream.

The sender reports clockwise rotation as `0`, `90`, `180`, or `270` degrees.
Rotations of `90` and `270` present portrait geometry; the other rotations
present landscape geometry.

## Media behavior

Media is best-effort and one-way. The sender paces RTP datagrams at the
configured media rate. The receiver uses a bounded reorder window and drops
late or incomplete access units instead of requesting retransmission, media
feedback, or an IDR frame. Terminal receiver media failures end the control
session; there is no automatic reconnect.

The receiver output is an OBS source texture. The wire protocol does not
require OBS or a virtual camera device, but the current supported receiver is
the Linux OBS plugin.
