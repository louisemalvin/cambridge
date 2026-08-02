# iOS development skeleton

This directory is a native SwiftUI/Xcode skeleton for the future iOS sender.
Open [`MobileWebcamIOS.xcodeproj`](MobileWebcamIOS.xcodeproj) in Xcode and run
the `MobileWebcamIOS` scheme on an iOS 16.0 or newer simulator or device.

The skeleton provides:

- SwiftUI application startup and navigation.
- Camera and local-network permission declarations.
- Platform lifecycle and permission coordinator placeholders.
- `IOSMediaEngine` with `start`, `stop`, `requestKeyframe`, and bitrate update
  boundaries.
- Coarse media events for startup, streaming, stop, permissions, camera,
  encoder, and network failures.
- Receiver control, sender-control listener, pairing, and session coordinator
  integration points.
- Unit tests for configuration validation and the non-functional media stub.

The media stub intentionally does not implement AVFoundation capture,
VideoToolbox encoding, NAL conversion, MPEG-TS muxing, UDP packetisation, or a
Swift/Kotlin per-frame bridge. Those decisions require a focused iOS media
spike and must be validated against
[`docs/media-transport-v1.md`](../docs/media-transport-v1.md) and the existing
Rust receiver compatibility tests.

The Android sender remains the active implementation and continues to use
RootEncoder. The iOS target should only grow into a native media engine after
the spike confirms the platform adapter can produce the same H.264 baseline
MPEG-TS/UDP stream.
