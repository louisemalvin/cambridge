# Control protocol

The active versioned control API is HTTP/JSON on TCP port `5001`. v2 routes use
sender-initiated session creation. Video is not sent over this connection.

The media plane is H.264 in MPEG-TS over an encrypted SRT caller/listener
session. It applies equally to Android and iOS adapters and deliberately does
not expose camera or encoder implementation details through the wire protocol.

The machine-readable source of truth is
[`protocol/control-v2.schema.json`](../protocol/control-v2.schema.json). Shared
v2 request and response examples are in [`protocol/examples`](../protocol/examples).

## Stable v2 identifiers

Use these lowercase values on the wire:

| Concept | Values |
| --- | --- |
| Codec | `h264`, `h265` |
| Transport | `srt` |
| Pixel format | `yuy2`, `nv12`, `i420` |
| Session state | `idle`, `allocating`, `listening`, `connected`, `receiving`, `reconnecting`, `stopping`, `failed`, `expired` |

Kotlin and Rust models map these values into platform-specific types. Library
enum names never become part of the protocol contract.

## Endpoints

`GET /v2/health` returns the service status and `protocolVersion` without
authentication.

`GET /v2/capabilities` returns supported codecs, the receiver-owned output
profile, SRT listener configuration, output pixel format, and the one-session
limit. Protected v2 routes require the configured bearer token.

`POST /v2/sessions` accepts an ordered codec preference, the fixed receiver
profile, and bitrate values for both codecs. The receiver selects the first
compatible codec, prepares the SRT listener, and returns a session ID plus a
per-session stream ID and AES-256 passphrase.

`GET /v2/sessions/{sessionId}` returns receiver-authoritative state, decoder,
transport metrics, decoded frame count, output FPS, queue depth, and reconnect
count.

`DELETE /v2/sessions/{sessionId}` stops ingest and returns the persistent output
to standby. Repeating DELETE for the most recently stopped session is safe.

`GET /v2/sessions/{sessionId}/diagnostics` and
`GET /v2/diagnostics/latest` expose bounded receiver diagnostic snapshots and
use the same bearer authentication as other protected v2 routes.

Errors are JSON objects with an HTTP status, stable `code`, and human-readable
`error` field. Unknown protocol versions and unknown codec values are rejected.
Unknown optional JSON fields are ignored for forward compatibility.

## Session sequence

```text
Sender -> GET health
Sender -> GET capabilities
Sender -> POST sessions
Receiver -> selected codec, fixed profile, SRT listener, output format
Sender -> prepare local encoder
Sender -> start encrypted MPEG-TS/SRT to the returned endpoint
Sender -> DELETE session on stop
```

The receiver may keep its input pipeline waiting for packets while the sender
prepares its encoder. A temporary packet interruption is a recoverable state,
not an instruction to terminate the receiver process.

## Automatic receiver discovery

Receivers advertise the DNS-SD service _mobile-webcam._tcp.local. The SRV port
is the receiver HTTP control port and TXT metadata identifies the v2 contract,
display name, and whether a bearer token is required. Discovery never carries
session secrets or the SRT media port. Clients probe the discovered origin
with v2 health and capabilities before attempting a session.

Manual origin entry remains the fallback for networks where multicast discovery
is unavailable.

## Receiver origin

The sender discovers the receiver with Bonjour/NSD and uses the resolved
origin for health, capabilities, create, status, and delete requests. The
Android pairing screen keeps manual origin entry as a fallback for networks
where multicast discovery cannot operate. The sender stores the optional token
in Android Keystore-backed storage. Subnet probing and a phone-side control
listener are not v2 dependencies.

## Network modes

Wi-Fi and USB tethering use the same discovered-or-manual origin, control, and
SRT media contracts. See [USB tethering](usb-tethering.md) for interface
details.

Bearer authentication and SRT encryption protect the v2 control and media
paths, but the initial deployment still assumes a trusted local network. Do
not expose the services to the public internet.
