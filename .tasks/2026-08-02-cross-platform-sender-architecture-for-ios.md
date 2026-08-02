# Cross-platform sender architecture for iOS

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Fully implement and stabilize the Android camera-to-laptop sender path, add a
clean non-functional iOS development skeleton, and document the MPEG-TS over UDP
wire contract shared by both platforms.

## Context

- `protocol/` is the wire-contract source of truth for receiver control and sender reverse control.
- The Rust receiver already separates protocol, session policy, GStreamer, and Linux output.
- The Android sender is currently one Gradle application plus a RootEncoder UDP module.
- Android-specific types currently leak into sender models and public engine interfaces (`MediaFormat`, `Surface`, `Context`).
- RootEncoder currently combines Android capture/encoding with MPEG-TS/UDP behavior, so the portable media seam needs an implementation spike.
- The current worktree contains uncommitted implementation changes. Preserve them while planning.
- The immediate milestone prioritizes Android. KMP is optional future work, not a prerequisite for the iOS skeleton.

## Decisions

- Proposed: keep the existing HTTP/JSON and MPEG-TS/UDP contracts as the interoperability boundary.
- Platform-specific media production and platform-agnostic media transport are separate concerns.
- Android keeps RootEncoder, MediaCodec, and the current camera path. No Android media rewrite is required.
- Both platforms must produce the documented H.264/H.265 over MPEG-TS over UDP wire format. The Rust receiver remains sender-platform agnostic.
- H.264 is the required baseline codec. H.265 is optional and negotiated.
- The transport specification must cover UDP unicast, MPEG-TS packet/datagram alignment, profile/level, keyframes, SPS/PPS/VPS repetition, timestamps, orientation, discontinuities, session identity, and stream epochs.
- KMP may be introduced later for low-throughput control/session behavior. It must not be placed in the per-frame media path.
- The iOS milestone is a native Swift/Xcode skeleton with interfaces and stubs only. It must not implement AVFoundation capture, VideoToolbox processing, NAL conversion, MPEG-TS muxing, or UDP packetization yet.
- The iOS skeleton uses native SwiftUI for startup/navigation and targets iOS 16.0 or later for this milestone.
- Refactor incrementally. Do not move the existing Android tree or perform a broad cleanup solely for future iOS support.

## Acceptance Criteria

- Android camera-to-laptop streaming works through RootEncoder with H.264 baseline behavior stabilized and H.265 behavior preserved where supported.
- The transport specification documents all agreed compatibility fields and behavioral rules.
- A buildable iOS application skeleton exists with startup/navigation, permission declarations, lifecycle placeholders, an `IOSMediaEngine` boundary, configuration/events, and stub adapters.
- The iOS skeleton exposes integration points for discovery, receiver control, pairing, and session orchestration without pretending to stream media.
- No per-frame media data crosses a Swift/Kotlin bridge.
- Existing Rust receiver tests remain green, and Android tests/builds cover the active sender path when the toolchain is available.
- The Rust receiver requires no iOS-specific media or control code for a conforming future iOS sender.

## Implementation Plan

1. Inspect the current Android implementation and identify only boundary fixes that improve the active path without a broad refactor.
2. Stabilize Android build/test behavior and preserve RootEncoder as the media engine.
3. Write the MPEG-TS over UDP transport specification and add/refresh receiver-side compatibility fixtures and tests.
4. Add the iOS application/Xcode skeleton with a clear module layout and native startup/navigation.
5. Add iOS permission declarations, lifecycle placeholders, configuration/event models, and `IOSMediaEngine` protocol with stub implementations.
6. Add iOS integration points for sender discovery, pairing, receiver HTTP control, and placeholder session orchestration.
7. Add build instructions and architecture documentation for the Android implementation, iOS skeleton, and future media spike.
8. Validate Android/Rust tests and static iOS skeleton structure. Defer full iOS media implementation to a separate technical spike.

## Task Contract

- Scope: Android stabilization, MPEG-TS/UDP contract documentation, and a non-functional native iOS skeleton.
- Out of scope: full iOS media production, Android media rewrite, KMP implementation, broad repository relocation, audio, encryption, or new discovery transports.
- Files or areas likely to change: `android/`, new `ios/`, `protocol/`, `docs/architecture.md`, `docs/protocol.md`, `docs/codecs.md`, and tests.
- Interfaces or behavior contracts: `IOSMediaEngine.start(configuration, destination)`, `stop()`, `requestKeyframe()`, `updateBitrate()`, coarse lifecycle/failure events, existing control endpoints, sender reverse-control messages, and MPEG-TS/UDP compatibility.
- Risks and edge cases: current Android worktree changes, unavailable Java/Xcode toolchains, iOS project generation on Linux, implicit H.264/H.265 bitstream assumptions, keyframes, timestamps, rotation, local-network permission, iOS background execution, IPv4-only discovery, and USB/p2p interface handling.
- Open questions: none

## Verification Plan

- `cargo test --manifest-path desktop/Cargo.toml --workspace` remains green.
- Android unit tests, lint, and debug build pass when the Android toolchain is available.
- Protocol fixtures and receiver compatibility tests cover H.264 baseline and optional H.265 transport behavior.
- The iOS skeleton builds in Xcode when tested on macOS; Linux validation is limited to source/layout checks.
- iOS skeleton tests cover configuration, event mapping, and stub lifecycle behavior without media frames.
- Synthetic H.264 and H.265 MPEG-TS senders validate receiver interoperability independently of camera platforms.
- Physical Android end-to-end validation remains the current media milestone. Physical iOS streaming is deferred.

## Status

Scoped implementation is complete. Android remains the active RootEncoder media
target. The transport contract and ADR, receiver compatibility assertions,
Android codec-boundary cleanup, and non-functional native iOS skeleton are
present.
Android and iOS builds remain unverified in this Linux environment because the
required Java/Android and Swift/Xcode toolchains are unavailable.

## Handoff Notes

- Next exact step: on a macOS development host, open `ios/MobileWebcamIOS.xcodeproj`, build the app and unit tests, then run the Android Gradle checks and physical Android H.264 end-to-end stability matrix on a Java-enabled host.
- Files changed: `README.md`, `android/app/.../VideoCodec.kt`, `android/app/.../MediaCodecCapabilityProbe.kt`, new `MediaCodecMimeTypes.kt`, `desktop/apps/receiver-desktop/src/discovery.rs`, `desktop/crates/receiver-gstreamer/src/codec_branch.rs`, `desktop/crates/sender-control-protocol/src/lib.rs`, `docs/architecture.md`, `docs/codecs.md`, `docs/decisions/0007-native-media-engines-shared-transport.md`, `docs/media-transport-v1.md`, `docs/protocol.md`, `docs/testing.md`, `protocol/README.md`, and new `ios/` skeleton/project/tests.
- Commands run: `work-context`, `task-ready`, `cargo fmt --manifest-path desktop/Cargo.toml --all -- --check`, `cargo test --manifest-path desktop/Cargo.toml --workspace`, `cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings`, `cargo build --manifest-path desktop/Cargo.toml --workspace`, `xmllint --noout ios/MobileWebcamIOS/Info.plist`, `git diff --check`, and static Xcode-project/source-boundary checks.
- Errors encountered: `./android/gradlew -p android test lint assembleDebug` could not start because `JAVA_HOME` is unset and no `java` executable is available. Swift, `swiftc`, and `xcodebuild` are also unavailable, so the iOS project was validated by source/layout inspection only.
- Verification evidence: Rust formatting, tests, clippy, and workspace build passed; GStreamer tests cover 188-byte MPEG-TS caps and both codec parser branches; Android UDP tests already assert the derived six-packet/1128-byte datagram limit; the iOS Info.plist is valid XML, the Xcode project delimiters and source-phase references are structurally valid, no iOS media-pipeline symbols are present, and the skeleton contains the requested engine, lifecycle, permission, control, pairing, discovery, session, and stub tests.
