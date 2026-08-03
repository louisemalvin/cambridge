# Control protocols

The receiver control plane is HTTP/JSON over TCP. All paths are versioned
under `/v1/`. The control plane negotiates a session and reports the UDP media
port allocated for that session. It never carries video bytes.

Media uses MPEG-TS over UDP unicast. The normative media-plane contract is
[`docs/media-transport-v1.md`](../docs/media-transport-v1.md). The stable
protocol identifiers are:

- Codecs: `h264`, `h265`.
- Transport: `mpegts-udp`.
- Output formats: `yuy2`, `nv12`, `i420`.

The JSON schema is in `control-v1.schema.json`. Examples are executable test
fixtures and are shared by Rust and Kotlin tests without code generation.

## Endpoints

- `GET /v1/health`
- `GET /v1/capabilities`
- `POST /v1/sessions/prepare`
- `GET /v1/sessions/{sessionId}`
- `DELETE /v1/sessions/{sessionId}`

Unknown optional fields must be ignored. Unknown protocol versions and unknown
required enum values must be rejected with a typed error.

## Sender discovery and reverse control v2

Mobile senders listen on TCP port `53555`. The desktop probes bounded local
IPv4 subnets with the side-effect-free `describe` request and receives a
versioned `describe_result` advertisement. The same service accepts one
newline-delimited `start` or `stop` request and returns one response using
`sender-control-v2.schema.json`.
The sender infers the receiver address from the TCP peer, so users never enter
an IP address.

The first request requires approval on Android. Approval creates a token scoped
to the stable sender and receiver IDs. Later start and stop requests with that
token authenticate automatically.

Every demand activation carries a UUID `streamId`. Retries and approval
retries reuse the same ID, and the corresponding stop must echo it. The
receiver HTTP session ID is a separate identifier. The old
`sender-control-v1.schema.json` is retained as a historical contract only;
implemented desktop and Android code use v2.
