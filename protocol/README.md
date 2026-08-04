# Control protocols

The receiver control plane is HTTP/JSON over TCP. The active contract is v2.
The sender creates sessions and receives a receiver-owned encrypted SRT
endpoint. The control plane never carries video bytes.

Media uses H.264 in MPEG-TS over SRT unicast. The stable protocol identifiers
are:

- Codecs: `h264`, `h265`.
- Transport kind: `srt`.
- Output formats: `yuy2`, `nv12`, `i420`.

The active JSON schema is `control-v2.schema.json`. Examples are executable
test fixtures and are shared by Rust, Kotlin, and Swift adapters without code
generation.

## v2 endpoints

- `GET /v2/health`
- `GET /v2/capabilities`
- `POST /v2/sessions`
- `GET /v2/sessions/{sessionId}`
- `DELETE /v2/sessions/{sessionId}`
- `GET /v2/sessions/{sessionId}/diagnostics`
- `GET /v2/diagnostics/latest`
- `GET /v2/demand/subscribe` - authenticated server-sent demand events for a
  connected sender. Events carry a generation, effective consumer count, and
  `active` or `inactive` demand state.

Unknown optional fields must be ignored. Unknown protocol versions and unknown
required enum values must be rejected with a typed error.

## Receiver origin and authentication

The sender discovers the receiver with platform Bonjour/NSD and uses the
resolved origin. Manual origin entry remains the fallback when multicast
discovery cannot operate. Subnet probing and a phone-side TCP listener are not
part of v2. Protected routes use the receiver's bearer token. Health is
intentionally public so a sender can distinguish an offline receiver from a
rejected credential. See [`discovery.md`](discovery.md) for the DNS-SD
contract.
