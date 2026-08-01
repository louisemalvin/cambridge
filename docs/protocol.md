# Control protocol

The versioned control API is HTTP/JSON on TCP port `5001`. All routes start
with `/v1/`. Video is not sent over this connection.

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
can be identified, the media port, output device, output pixel formats, and
the one-session limit.

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
Sender -> start MPEG-TS/UDP to receiver:5000
Sender -> DELETE session on stop
```

The receiver may keep its input pipeline waiting for packets while the phone
prepares its encoder. A temporary packet interruption is a recoverable state,
not an instruction to terminate the receiver process.

## Network modes

Wi-Fi and USB tethering use the same control and media routes. The phone must
send both connections to the Linux host address reachable on the selected
network. See [USB tethering](usb-tethering.md) for interface discovery.

Phase 1 deliberately provides no authentication or encryption. Do not expose
the control port or media port to an untrusted network.
