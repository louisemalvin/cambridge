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
- Sender-initiated receiver control and session coordinator boundaries
  integration points.
- Bonjour receiver discovery with the shared DNS-SD metadata contract; the
  eventual pairing flow keeps manual receiver-origin entry as a fallback.
- Unit tests for configuration validation and the non-functional media stub.

The media stub intentionally does not implement AVFoundation capture,
VideoToolbox encoding, NAL conversion, MPEG-TS muxing, SRT packetisation, or a
Swift/Kotlin per-frame bridge. The adapter boundary follows the shared v2
control DTOs and encrypted SRT endpoint contract.

The Android sender remains the active implementation and continues to use
RootEncoder. The iOS target should only grow into a native media engine after
macOS build and file-source conformance evidence confirms the platform adapter
can produce the same H.264 baseline MPEG-TS/SRT stream.
