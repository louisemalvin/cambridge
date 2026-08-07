# Architecture

The current product has one Android sender, one configured receiver, one
active session, and one direct OBS host. The connection is deliberately boring
and remains frozen while the implementation is organized around reusable
boundaries.

## Frozen connection

```text
Android camera
  -> H.264 encoder
  -> RTP/H.264 over UDP
  -> portable receiver responsibilities
  -> Linux/OBS presentation
```

The control plane is length-prefixed JSON over TCP. The media plane is
best-effort RFC 6184 H.264 RTP over UDP. The sender makes one explicit
connection attempt after the user confirms the session contract. A lagging
pipeline drops work; it does not reconnect or request recovery.

This goal does not replace TCP, UDP, RTP, H.264, the manual endpoint, or the
direct OBS source.

## Ownership boundaries

### Session coordinator

The session coordinator is the only application component that composes the
camera, encoder, and transport ports. It owns the explicit lifecycle and the
immutable session contract. UI code observes typed state and events; it does
not call sockets, codecs, or camera devices directly.

### Camera port

The camera boundary owns camera discovery, capture surfaces, preview surfaces,
camera capabilities, and live controls such as lens selection, zoom,
stabilization, focus, and exposure.

It does not know about RTP, UDP, receiver addresses, session IDs, or OBS. A
capture-format change is a new session contract; live camera controls remain
independent of transport.

### Encoder port

The encoder boundary accepts a locked capture configuration and publishes
encoded H.264 access units. It owns codec configuration, keyframe policy, and
bounded access-unit production. It does not own receiver addressing or host
presentation.

### Transport port

The transport boundary consumes encoded access units and the session endpoint.
It owns control framing, the TCP handshake, RTP packetization, UDP I/O, and
transport events. It does not own Camera2, AVFoundation, UI state, or OBS.

### Receiver core

The receiver core is the reusable part of a receiver implementation:

- control message validation and session identity
- RTP parsing, ordering, and H.264 access-unit assembly
- decoder coordination and frame metadata
- bounded queues and newest-frame presentation mailbox
- terminal lifecycle and diagnostics

The current native plugin contains these responsibilities in one build target.
The next structural step is to extract them without changing the wire
behavior.

### Host adapter

The host adapter turns decoded frames into a platform output. The current
adapter owns libobs graphics objects, VAAPI/DRM PRIME import, CPU NV12 upload,
texture lifetime, and OBS properties. Those details must not leak into the
receiver core.

## Data flow and backpressure

```text
camera -> encoder input surface -> bounded encoded queue -> RTP sender
                                                |
                                                v
receiver UDP -> bounded reorder -> decoder queue -> newest-frame mailbox
                                                        |
                                                        v
                                                   host adapter
```

Every queue has a contract-backed bound. The media path is asynchronous and
uses background workers. Camera controls, UI rendering, and diagnostics must
remain responsive when transport or decoding is slow.

## Cross-platform reuse

Reusable artifacts are the protocol schema, typed session/profile models,
capability rules, RTP/H.264 test vectors, lifecycle semantics, frame metadata,
drop policy, and diagnostics events.

Platform adapters provide Android Camera2/MediaCodec and Linux
VAAPI/DRM/libobs. iOS and other host operating systems are future roadmap work
and are not part of the current baseline.

## Repository direction

The repository is organized around four roles:

```text
protocol/              machine-readable wire contract and fixtures
android/               Android sender and platform adapters
desktop/hosts/obs/      Linux OBS source and presentation adapter
docs/                  contract, architecture, platform, and operations docs
```

The current native source is buildable at
`desktop/hosts/obs/direct-webcam-source/`. Future platform adapters must be
added behind the release contract and their own compatibility and integration
tests.
