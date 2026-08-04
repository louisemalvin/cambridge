# Receiver discovery

Receiver discovery is a convenience layer around the v2 HTTP control
contract. It does not replace authentication, session creation, or SRT
negotiation.

## DNS-SD service

Receivers advertise:

```
_mobile-webcam._tcp.local.
```

The SRV port is the receiver's HTTP control port. Clients must treat the
advertised host and port as an origin candidate and call GET /v2/health before
attempting a session.

TXT records are:

| Key | Values | Meaning |
| --- | --- | --- |
| version | 2 | Supported control contract |
| name | UTF-8 display name | Name shown to the sender |
| auth | required or none | Whether the receiver expects a bearer token |

The advertisement never contains bearer tokens, SRT passphrases, or the SRT
listener port. The sender obtains the per-session SRT endpoint only through
authenticated v2 session creation.

## Client behavior

1. Browse for _mobile-webcam._tcp.local.
2. Resolve each service and discard unsupported protocol versions.
3. Probe /v2/health and /v2/capabilities.
4. Let the user select a receiver and subscribe to authenticated demand events.
5. Create a v2 session only after an active demand generation is received.
6. Keep manual receiver-origin entry available when multicast discovery is
   blocked, such as restricted Wi-Fi, some USB-tethering configurations, or
   emulators.

Discovery is receiver advertisement only. It does not scan subnets, infer
peer addresses, open a phone-side control server, or start a camera session
without sender-side intent.
