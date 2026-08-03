# Control protocol

The versioned control API is HTTP/JSON on TCP port `5001`. All routes start
with `/v1/`. Video is not sent over this connection.

The media-plane contract is documented separately in
[`media-transport-v1.md`](media-transport-v1.md). It applies equally to
Android and iOS senders and deliberately does not expose camera or encoder
implementation details through the wire protocol.

The machine-readable source of truth is
[`protocol/control-v1.schema.json`](../protocol/control-v1.schema.json). Shared
request and response examples are in [`protocol/examples`](../protocol/examples).

## Stable identifiers

Use these lowercase values on the wire:

| Concept | Values |
| --- | --- |
| Codec | `h264`, `h265` |
| Transport | `mpegts-udp` |
| Pixel format | `yuy2`, `nv12`, `i420` |
| Session state | `idle`, `prepared`, `waiting_for_stream`, `receiving`, `timed_out`, `stopping`, `failed` |

Kotlin and Rust models map these values into platform-specific types. Library
enum names never become part of the protocol contract.

## Endpoints

`GET /v1/health` returns the service status and `protocolVersion`.

`GET /v1/capabilities` returns supported codecs, decoder acceleration when it
can be identified, per-session media-port assignment, output device, output
pixel formats, and the one-session limit. The Linux receiver allocates media
ports from `50000-50099` so its firewall exposure stays bounded.

`POST /v1/sessions/prepare` accepts an ordered codec preference, a profile, and
bitrate values for both codecs. The receiver selects the first compatible
codec for the requested profile, prepares the media pipeline, and returns a
session ID and selected output format.

`GET /v1/sessions/{sessionId}` returns the current session state, decoder name
when available, received bitrate estimate, and timeout count.

`DELETE /v1/sessions/{sessionId}` stops the media pipeline. Repeating DELETE
for the most recently stopped session is safe.

Errors are JSON objects with an HTTP status, stable `code`, and human-readable
`error` field. Unknown protocol versions and unknown codec values are rejected.
Unknown optional JSON fields are ignored for forward compatibility.

## Session sequence

```text
Sender -> GET health
Sender -> GET capabilities
Sender -> POST sessions/prepare
Receiver -> selected codec, profile, UDP port, output format
Sender -> prepare local encoder
Sender -> start MPEG-TS/UDP to the returned session port
Sender -> DELETE session on stop
```

The receiver may keep its input pipeline waiting for packets while the sender
prepares its encoder. A temporary packet interruption is a recoverable state,
not an instruction to terminate the receiver process.

## Discovery and reverse control

Mobile senders listen on TCP port `53555`. The desktop probes local IPv4 hosts with a
side-effect-free `describe` request, then sends one newline-delimited JSON `start`
or `stop` request to the selected sender. The sender infers the receiver address
from the TCP peer. The shared contract is
[`protocol/sender-control-v2.schema.json`](../protocol/sender-control-v2.schema.json).

The first request returns `approval_required`. Mobile sender approval creates a
pairing token. A later request with that token starts or stops automatically
while still showing the camera foreground notification during media activity.
Each demand activation creates a new UUID `streamId`. Start retries, approval
retries, and the matching stop use the same ID. A receiver HTTP `sessionId` is
not a stream ID and is never substituted for it.

## Network modes

Wi-Fi and USB tethering use the same discovery, control, and media contracts.
See [USB tethering](usb-tethering.md) for interface details.

Pairing authenticates automatic sender activation, but Phase 1 does not encrypt
control or media. Do not expose the services to an untrusted network.
