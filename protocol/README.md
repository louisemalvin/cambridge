# Control protocol v1

The receiver control plane is HTTP/JSON over TCP. All paths are versioned
under `/v1/`. The control plane negotiates a session and reports the UDP media
port allocated for that session. It never carries video bytes.

Media uses MPEG-TS over UDP unicast. The stable protocol identifiers are:

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

## Sender discovery and reverse control

Android senders listen on TCP port `53555`. The desktop probes bounded local
IPv4 subnets with the side-effect-free `describe` request and receives a
versioned `senderAdvertisement`. The same service accepts one newline-delimited
start request and returns one response using `sender-control-v1.schema.json`.
The sender infers the receiver address from the TCP peer, so users never enter
an IP address.

The first request requires approval on Android. Approval creates a token scoped
to the stable sender and receiver IDs. Later requests with that token reconnect
automatically.
