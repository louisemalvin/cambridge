# ADR 0007: Native media engines with a shared MPEG-TS/SRT boundary

Status: Accepted

## Context

Camera capture and hardware video encoding are controlled by operating-system
APIs. Android and iOS therefore cannot be expected to share one camera or
per-frame media engine. The Rust receiver handles MPEG-TS over SRT
without depending on the sender platform.

## Decision

- Android remains the primary implementation and keeps Camera2/CameraX,
  MediaCodec, and RootEncoder inside the Android media boundary.
- iOS will eventually use native AVFoundation and VideoToolbox boundaries.
- Both senders conform to the same H.264-baseline, H.265-optional MPEG-TS over
  encrypted SRT caller/listener contract in [`protocol.md`](../protocol.md).
- The receiver does not branch on the sender platform.
- KMP is optional for control/session behavior and is excluded from the
  per-frame media path.
- The current milestone adds an iOS SwiftUI/Xcode skeleton and does not add
  production AVFoundation capture, VideoToolbox processing, NAL conversion,
  MPEG-TS muxing, SRT integration, or a per-frame bridge.

## Consequences

The camera, encoder, muxer, and socket implementations may differ between
platforms without creating receiver variants. Compatibility is tested at the
wire boundary. The iOS media spike must validate timestamps, parameter-set
repetition, orientation, restart behavior, and datagram sizing before the iOS
stub becomes a production engine.
