# Direct Webcam contract

Status: active product contract v4. Protocol v3 is frozen and is not extended.

This document describes the supported direct stream connection. It is
platform-neutral at the wire boundary: Android is the current sender and the
native Linux OBS source is the current host, but neither operating system is
part of the connection contract.

The machine-readable values are defined in
[`protocol/direct-stream-contract.json`](../protocol/direct-stream-contract.json).
The JSON Schema and committed examples are validation fixtures for that same
contract. They are not independent configuration sources.

## Connection

The receiver listens for one control connection and one media stream:

```text
sender -> length-prefixed JSON over TCP -> receiver
sender -> RFC 6184 H.264 RTP over UDP -> receiver
```

The v4 contract defines local receiver discovery while the implementation
retains a manually configured receiver address as a fallback. Discovery will
identify a candidate; the side-effect-free control probe will confirm that it
is a compatible OBS receiver.

The control connection uses a big-endian 32-bit message length followed by
UTF-8 JSON. The receiver accepts one active session at a time. The media port
is derived from the contract-backed control port offset.

## Session contract

The sender creates and validates one immutable session contract before opening
the media pipeline. It contains:

- protocol version
- session ID and monotonic generation
- H.264 codec identity
- coded width and height
- clockwise rotation in `0`, `90`, `180`, or `270` degrees
- frame rate
- target bitrate
- receiver media port returned by the control handshake

The contract is locked for the lifetime of the session. Resolution, frame
rate, codec, bitrate, and orientation changes require Stop followed by a new
Start. A 180-degree reverse is presentation behavior within the selected
orientation axis; it is not a renegotiation.

The current contract supports these catalog entries:

| Profile | Dimensions | Frame rate | Bitrate | Availability |
| --- | ---: | ---: | ---: | --- |
| `2k30` | 2560x1440 | 30 fps | 18 Mbps | normal product profile |
| `1080p30` | 1920x1080 | 30 fps | 8 Mbps | normal product profile |
| `720p30` | 1280x720 | 30 fps | 4 Mbps | named AVD smoke profile |

The profile catalog is the extension point for future quality and frame-rate
choices. New profiles must be represented as data, validated against sender
and receiver capabilities, and selected before the session starts. Existing
profile IDs and semantics remain stable. Camera anti-banding and stabilization
are sender-local capture settings and are intentionally not part of the wire
session contract.

## Media behavior

Media is best-effort and one-way. The sender packetizes encoded H.264 access
units as RTP. The receiver validates the active source, reorders within the
bounded window, assembles complete access units, and decodes them.

When work arrives too late or a bound is reached, the receiver drops the
incomplete access unit or stale frame. It does not request retransmission, ask
for a missing frame, request an IDR, grow a queue, or block the capture path.
The presentation mailbox keeps only the newest decoded frame.

## Lifecycle

The only stream lifecycle is:

```text
Idle -> Connecting -> Streaming -> Stopping -> Idle
                         |
                         +------> Failed
```

Start is explicit and produces one connection attempt. Stop is explicit or is
triggered by a terminal control, media, camera, or application lifecycle
failure. A lost session is terminal and requires another explicit Start. There
is no background reconnect loop.

Closing or removing the Android app task stops the active sender session. The
ongoing foreground notification remains the in-session Stop control while the
app is running in the background.

## Platform boundary

The wire contract is reusable across operating systems. The implementation is
intended to be layered as:

```text
portable receiver core
  -> decoded-frame sink
      -> OBS/Linux host adapter
      -> future Windows/macOS/other host adapter
```

The receiver core owns control validation, RTP/H.264 assembly, decoder
coordination, bounded queues, frame metadata, and session state. A host adapter
owns OBS, a preview surface, a virtual-camera output, or another presentation
API. Platform camera APIs belong only to sender adapters.

The current baseline ships only the Android sender and Linux OBS host. iOS and
additional receiver hosts are intentionally outside this goal.

## Compatibility rule

Refactoring the repository or extracting portable modules must not change the
wire behavior described here. A wire incompatibility requires an explicit
protocol version and new fixtures. Internal module moves, platform adapters,
decoder choices, and rendering optimizations do not require a protocol change.
