# Android physical lens quick snap

## Goal

Add a first, low-risk way to switch among physical cameras in an Android
logical multi-camera while preserving the existing RootEncoder stream object.

## Context

- RootEncoder 2.8.0 `Camera2Source` exposes `physicalCamerasAvailable()` and
  `openPhysicalCamera(id)` on Android P and newer.
- RootEncoder applies the selected physical ID to the existing capture session
  output configuration and reopens that camera session.
- Physical camera IDs are opaque runtime strings and must not be hardcoded.

## Decisions

- Keep the existing `Camera2Source` and UDP encoder path.
- Expose an automatic option plus runtime-discovered physical lens options in
  the Android camera interaction state.
- Use the physical ID only inside the Android camera adapter; the UI receives
  display labels and selection callbacks.
- Start with runtime ID labels for fast device validation. Add focal-length
  classification after observing the first device behavior.
- Do not change the receiver, wire protocol, or platform-neutral session
  contracts.

## Acceptance Criteria

- The app discovers physical camera IDs at runtime without numeric literals.
- The streaming UI offers an automatic option and one quick-snap action per
  discovered physical camera.
- Selecting an option calls RootEncoder `Camera2Source.openPhysicalCamera` on
  Android P and newer without creating a second camera source or UDP stream.
- Unsupported Android versions expose no physical-lens controls and retain the
  current logical-camera behavior.
- Existing zoom, preview lifecycle, stream, and receiver behavior remain
  unchanged.

## Implementation Plan

- Extend the Android camera interaction state and controller with lens options.
- Discover IDs from the retained RootEncoder `Camera2Source` and delegate
  selection through the existing application-scoped engine.
- Add Material 3 quick-snap controls to the streaming screen.
- Add state/UI coverage and update Android documentation.
- Run Android and Rust verification; physical validation remains device-based.

## Task Contract

- Scope: Android camera interaction state, RootEncoder adapter, streaming UI,
  tests, and Android documentation.
- Out of scope: receiver behavior, wire protocol, second camera sessions, and
  manual numeric camera-ID mappings.
- Files or areas likely to change: `android/app/.../camera`,
  `android/app/.../streaming/rootencoder/RootEncoderStreamEngine.kt`,
  streaming UI components, Android tests, and `docs/android-setup.md`.
- Interfaces or behavior contracts: camera lens options remain inside the
  Android camera interaction boundary; session negotiation and media contracts
  remain camera-neutral.
- Risks and edge cases: physical-camera APIs require Android P or newer;
  `openPhysicalCamera` reopens the existing camera session and may briefly
  pause frames; devices may report no physical IDs or reject a requested ID;
  runtime IDs must remain opaque.
- Open questions: None.

## Verification Plan

- `./android/gradlew -p android test lint assembleDebug`
- `./android/gradlew -p android assembleDebugAndroidTest`
- Verify the runtime list on a physical logical multi-camera device and tap
  automatic plus each discovered physical camera while streaming.
- Confirm stream object and receiver session remain active during a snap.

## Status

Implementation is complete. Android unit tests, lint, debug APK assembly, and
instrumentation APK compilation pass. Physical lens behavior remains to be
validated on the user's logical multi-camera device.

## Handoff Notes

- Next exact step: install the debug APK on the device and tap `Auto` plus each
  runtime-labeled physical lens while streaming.
- Files changed: Android camera interaction state/controller, RootEncoder
  adapter, streaming lens controls, Android tests, and `docs/android-setup.md`.
- Commands run: RootEncoder 2.8.0 source inspection, `task-ready`,
  `work-context`, `./android/gradlew -p android test lint assembleDebug`, and
  `./android/gradlew -p android assembleDebugAndroidTest`.
- Errors encountered: none after implementation.
- Verification evidence: `Camera2Source` exposes physical camera discovery and
  `openPhysicalCamera`; its manager sets the physical ID on existing output
  configurations before reopening the logical camera session. Runtime IDs are
  deduplicated and no options are shown when none are reported. Android gates
  pass; physical stream continuity and image-quality behavior are not yet
  verified.
