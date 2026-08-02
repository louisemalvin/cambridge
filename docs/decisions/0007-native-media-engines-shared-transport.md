# ADR 0007: Native media engines with a shared MPEG-TS/UDP boundary

Status: Accepted

## Context

Camera capture and hardware video encoding are controlled by operating-system
APIs. Android and iOS therefore cannot be expected to share one camera or
per-frame media engine. The Rust receiver already handles MPEG-TS over UDP
without depending on the sender platform.

## Decision

- Android remains the primary implementation and keeps Camera2/CameraX,
  MediaCodec, and RootEncoder inside the Android media boundary.
- iOS will eventually use native AVFoundation and VideoToolbox boundaries.
- Both senders conform to the same H.264-baseline, H.265-optional MPEG-TS over
  UDP unicast contract in [`media-transport-v1.md`](../media-transport-v1.md).
- MPEG-TS packets remain 188 bytes and datagrams contain whole packets only.
- The receiver does not branch on the sender platform.
- KMP is optional for control/session behavior and is excluded from the
  per-frame media path.
- The current milestone adds an iOS SwiftUI/Xcode skeleton and does not add
  production AVFoundation capture, VideoToolbox processing, NAL conversion,
  MPEG-TS muxing, UDP packetisation, or a per-frame bridge.

## Consequences

The camera, encoder, muxer, and socket implementations may differ between
platforms without creating receiver variants. Compatibility is tested at the
wire boundary. The iOS media spike must validate timestamps, parameter-set
repetition, orientation, restart behavior, and datagram sizing before the iOS
stub becomes a production engine.
