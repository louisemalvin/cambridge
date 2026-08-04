# Vivo physical camera lens selection through the RootEncoder pipeline

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Expose the Vivo's rear physical camera IDs `2`, `3`, and `4` in Settings >
Camera and make each selection affect the existing webcam preview and stream.
Keep one RootEncoder-owned camera/encoder/session pipeline.

## Context

The Vivo V2413 exposes logical camera ID `0` for rear and ID `1` for front.
Logical ID `0` reports physical IDs `2`, `3`, and `4`; the phone's logical
camera switching reaches ultra-wide and normal but not the zoom module.
The current build enumerates logical CameraX IDs and therefore exposes only
`Lens 0` and `Lens 1`, where `Lens 1` is front.

## Decisions

- Physical IDs are the user-visible rear-lens choices; logical ID `0` remains
  the automatic rear choice and logical ID `1` remains the front choice.
- Use a single RootEncoder `VideoSource` and rebind its existing output surface
  when a lens changes. Do not add CameraX `VideoCapture`, a second encoder, or a
  direct parallel camera pipeline.
- Prefer a supported RootEncoder/CameraX physical-camera binding. If the
  RootEncoder 2.8.0 CameraX source cannot expose `CameraSelector`'s physical ID,
  evaluate its existing `Camera2Source.openPhysicalCamera` boundary before
  introducing an app-owned source copy.

## Acceptance Criteria

- Settings > Camera identifies the logical rear/front choices and Vivo physical
  IDs `2`, `3`, and `4` without exposing CameraX or Camera2 objects to Compose.
- Selecting the Vivo zoom physical ID produces a visibly different preview and
  keeps the negotiated receiver session in `receiving`.
- Switching among physical IDs and back to logical rear keeps valid frames on
  the same `/dev/video10` output and does not create a second session.
- Start, switch, and stop on the physical Vivo complete without app fatal,
  Scudo, RootEncoder, or receiver pipeline errors.
- Tests, lint, docs, and the task evidence describe the actual logical versus
  physical behavior.

## Implementation Plan

- Inspect CameraX physical-camera info and RootEncoder source APIs.
- Select the smallest maintainable RootEncoder-owned source extension that can
  set `CameraSelector.Builder.setPhysicalCameraId` or use RootEncoder's existing
  Camera2 physical-camera API.
- Model physical lens descriptors and stable UI labels from camera metadata.
- Add focused state/source tests, build the exact APK, and inspect Settings.
- Run physical Vivo stream start, physical lens switches, frame capture, and
  clean stop; update docs and commit the implementation separately.

## Task Contract

- Scope: Android camera-source adapter, camera state mapping, focused tests,
  architecture/setup/limitation docs, and physical-device evidence.
- Out of scope: Rust receiver or wire-protocol redesign and unrelated UI work.
- Files or areas likely to change: `android/app`, relevant RootEncoder adapter
  files, `docs/android-setup.md`, `docs/architecture.md`, and this artifact.
- Interfaces or behavior contracts: preserve `StreamEngine`, MPEG-TS over UDP,
  one active session owner, and the existing settings destination.
- Risks and edge cases: physical-camera support is API 28+, physical info can be
  unavailable or vendor-specific, CameraX may reject a requested surface
  configuration, and Vivo lens IDs are not portable across devices.
- Open questions: none

## Verification Plan

- `work-context` and `task-ready` at workflow boundaries.
- Android unit/compile/lint checks and connected Android tests on the emulator.
- Install and launch the exact APK on the Vivo; inspect Settings > Camera.
- Pair the Vivo with the existing receiver over the LAN, capture logcat and
  receiver session state for each lens, and capture three output buffers.
- Verify clean stop, released receiver session, and absence of fatal/Scudo logs.
- Run Rust format/tests/clippy for the final repository matrix.

## Status

Implementation in progress; physical-ID API selection is confirmed.

## Handoff Notes

- Next exact step: choose and implement the supported physical-ID binding after
  comparing RootEncoder CameraX and Camera2 source paths.
- Files changed: this artifact; preserve existing unrelated untracked files.
- Commands run: `work-context`, `task-init`, RootEncoder and CameraX source/API
  inspection, Vivo `dumpsys media.camera`, and the logical-camera stream smoke.
- Errors encountered: the first implementation incorrectly treated logical
  CameraX IDs `0` and `1` as physical lenses; the Vivo stream proved `Lens 1`
  selects the front camera.
- Verification evidence: exact APK SHA-256 `c534a69289eb9bf700d33fff1e8fe6542d81bc6546eb8060cca7f314c37113c8`; Vivo session `1086831a-cd97-41bf-9eb2-ef16b2f3ee16` stayed `receiving` after logical front switch and emitted `camera_lens_selected` with camera ID `1`; three output buffers were captured, but the rear physical IDs remain unvalidated.
