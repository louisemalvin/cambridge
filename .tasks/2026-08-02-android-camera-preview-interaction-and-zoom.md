# Android camera preview interaction and zoom

## Goal

Improve the Android camera surface so the selected stream shape is preserved in
the app preview and receiver output, with live zoom controls that do not restart
the active RootEncoder stream.

## Context

- RootEncoder `2.8.0` `Camera2Source` already exposes zoom range and zoom setter.
- Keep one `Camera2Source`; do not add CameraX or another camera session.
- The receiver and versioned control/media contracts stay unchanged unless a
  concrete compatibility defect is found.
- The application-scoped session and foreground-service ownership must survive
  preview surface destruction and activity recreation.

## Decisions

- Use a separate Android camera-interaction boundary for zoom state and preview
  surface attachment; keep session negotiation and wire DTOs camera-neutral.
- Keep negotiated `VideoProfile.width` and `.height` as encoded output
  dimensions. Display orientation changes the preview layout/rendering, not the
  receiver contract or encoded aspect ratio.
- Use RootEncoder's existing `Camera2Source` and attach/detach its GL preview
  surface independently of stream start/stop.
- Use Material 3 `Slider` and reset action; add pinch zoom only if it remains a
  narrow, testable UI adapter.
- Keep Material 3 on the stable Compose BOM (`2026.06.00`) rather than pinning
  an artifact independently.

## Acceptance Criteria

- Preview dimensions are derived from the selected `VideoProfile` aspect ratio
  and current display orientation; no fixed preview height controls sizing.
- RootEncoder encodes the negotiated profile dimensions and receiver output is
  constrained to the same aspect ratio without stretching.
- Zoom state exposes camera-reported minimum/maximum bounds, starts at 1x,
  clamps requests, supports slider and reset, and updates the active
  `Camera2Source` without stream restart.
- Preview surface attach, detach, recreation, minimize/restore, and activity
  recreation leave the active media session and foreground service running.
- Unit tests cover aspect-ratio/orientation mapping, zoom clamping/reset, and
  camera interaction transitions. Existing session tests remain green.
- Android documentation explains the chosen RootEncoder camera-control and
  aspect-ratio behavior.

## Implementation Plan

- Add pure profile/orientation presentation and zoom-state logic with focused
  tests.
- Split preview/zoom lifecycle from the session start contract and implement it
  in the RootEncoder adapter around its retained `Camera2Source`.
- Replace the fixed-height `SurfaceView` layout with profile-driven sizing and
  Material 3 zoom controls, including reset and pinch handling if clean.
- Add surface lifecycle/state transition coverage and update Android docs.
- Run the Android test, lint, and debug build gates, then audit literals and
  manually validate hardware-dependent behavior when a device is available.

## Task Contract

- Scope: Android preview geometry, RootEncoder camera interaction, zoom UI,
  lifecycle behavior, tests, and Android documentation.
- Out of scope: iOS, Rust receiver refactors, wire protocol changes, new media
  transport, muxer, or encoder implementation.
- Files or areas likely to change: `android/app/.../camera`, UI components,
  `SenderApp`, `SenderViewModel`/state, session/stream adapter interfaces,
  RootEncoder adapter, Android tests, and `docs/android-setup.md` plus relevant
  architecture/testing notes.
- Interfaces or behavior contracts: session negotiation remains profile/codec
  based; camera interaction publishes coarse zoom/orientation state and accepts
  Android preview-surface lifecycle events.
- Risks and edge cases: camera bounds are unavailable before the source opens;
  invalid/destroyed surfaces must be ignored or detached; rotation must not
  cause an encoder dimension swap that violates receiver profile caps; a failed
  zoom request must not tear down streaming.
- Open questions: None.

## Verification Plan

- `./android/gradlew -p android test lint assembleDebug`.
- Focused JVM tests for presentation geometry, orientation, zoom state, and
  interaction transitions; Compose tests for the zoom control where practical.
- Inspect RootEncoder source/API usage and changed production literals.
- On hardware: H.264/H.265 stream, receiver dimensions/aspect ratio, zoom
  min/max/reset, zoom without restart, minimize/lock/restore, surface
  recreation, and stop/restart.

## Status

Implementation changes are complete. The Android JVM, lint, debug build, and
instrumentation APK compilation gates pass. A crash caused by treating Android
surface-rotation enum values as degree values was corrected and covered by
tests. A second crash caused by nested unbounded Compose vertical scroll
containers was corrected by keeping one screen-level scroll owner. Physical
device validation is blocked until Android hardware or an emulator becomes
available.

## Handoff Notes

- Next exact step: validate the stream and preview transitions on physical
  Android hardware once a device or emulator is available.
- Files changed: Android camera boundary, RootEncoder adapter, session preview
  contract, Compose UI/tests, `android/gradle/libs.versions.toml`, and Android
  docs; see the worktree for the complete set.
- Commands run: `work-context`, `task-ready`, RootEncoder source/API
  inspection, `cargo fmt --check`, `cargo clippy`, `cargo test`,
  `./android/gradlew -p android assembleDebugAndroidTest`, and
  `./android/gradlew -p android test lint assembleDebug`.
- Errors encountered: `jar` is unavailable; source jars were inspected with
  `unzip`. The system initially had no JDK, so verification used a temporary
  JDK 17 under `/tmp`. Android surface rotation values were initially passed
  to a degree-based mapper, which could crash on landscape devices; the
  mapper now handles `Surface.ROTATION_*` explicitly.
- Verification evidence: RootEncoder `Camera2Source` exposes `getZoomRange`,
  `setZoom(Float)`, and `getZoom`; `StreamBase.stopPreview` detaches preview
  without stopping sources while streaming. Rust workspace tests, formatting,
  and clippy pass. `./android/gradlew -p android assembleDebugAndroidTest`
  compiles and packages the Compose zoom test, and
  `./android/gradlew -p android test lint assembleDebug` passes, including
  Kotlin compilation, unit tests, lint, and debug APK assembly. The receiver
  GStreamer test confirms decoded output caps use the negotiated width,
  height, format, and frame rate. No emulator or device is available through
  `adb`, so physical stream and lifecycle behavior are not yet verified.
