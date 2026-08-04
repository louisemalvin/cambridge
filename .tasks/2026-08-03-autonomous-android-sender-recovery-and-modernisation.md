# Autonomous Android sender recovery and modernisation

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Restore the Android sender to a launchable, correctly packaged state, then finish and verify the intended Hilt, destination-based Compose architecture, RootEncoder CameraX integration, and device/runtime behavior described in the attached handoff.

## Context

- Repository: `phone-webcam-project`; Android application ID: `dev.mobilewebcam.sender`.
- Preserve `StreamEngine`, session/negotiation boundaries, HTTP control, MPEG-TS over UDP, and Rust receiver compatibility.
- Current APK previously installed but crashed before Compose startup with `ClassNotFoundException` for `MobileWebcamApplication`.
- Current tree already contains feature/infrastructure migration commits. The verified build uses AGP built-in Kotlin with the KSP source-set compatibility flag required by the current toolchain.
- Existing unrelated untracked file `docs/android-ui-rehaul-plan.html` must be preserved and excluded unless explicitly needed.

## Decisions

- Use AGP built-in Kotlin when the verified AGP/toolchain supports it; do not mix disabled built-in Kotlin with a missing legacy Android Kotlin plugin.
- Use Hilt with its Gradle plugin and KSP, preserving one application-scoped session/stream lifecycle owner.
- Keep the three destinations explicit: Pairing -> Webcam, Webcam <-> Settings; recoverable network loss remains a Webcam state.
- Keep RootEncoder as the encoder/transport boundary and integrate CameraX only through RootEncoder's supported camera source.
- Evaluate RootEncoder 2.8.0 `CameraXSource.openCamera(CameraInfo)` for camera selection before considering any fallback; keep one CameraX source and one stream owner.
- Commit each coherent milestone with Conventional Commit messages and leave unrelated user changes untouched.

## Acceptance Criteria

- Clean Android debug build and unit/instrumentation compilation pass.
- `MobileWebcamApplication` is present in APK DEX and the packaged manifest names the correct class.
- The exact built APK installs and launches on the prepared emulator without an AndroidRuntime crash; Hilt initializes.
- Stable, verified Android toolchain versions are aligned across `app` and `rootencoder-udp`.
- Pairing, Webcam, and Settings are explicit destinations with destination ViewModels and correct back behavior/startup restoration.
- Compose receives immutable presentation-only state mapped from infrastructure state; the previous large sender surface remains decomposed without a replacement god file.
- Technical packages are grouped coherently and RootEncoder, protocol DTO, and receiver boundaries remain intact.
- CameraX runs through RootEncoder's supported source mechanism without a second encoder/camera/session path.
- Exactly one session/stream lifecycle owner remains authoritative; foreground service, wake lock, UDP socket, and RootEncoder lifetimes are not duplicated.
- Existing and new tests, lint, formatting/build checks, emulator smoke tests, and available physical-device/receiver checks are reported with evidence.
- Architecture/runtime documentation and incremental commit history match the implemented state.

## Implementation Plan

1. Audit source sets, Gradle configuration, merged manifest, generated classes, APK DEX, exact installed artifact, and recent history; record evidence.
2. Fix the proven packaging/startup cause, clean-build, install, launch, and commit the launchable baseline.
3. Verify current official stable versions and align Gradle/AGP/JDK/Kotlin/KSP/Hilt/Navigation/CameraX/RootEncoder without unnecessary upgrades; commit.
4. Correct Hilt annotations, scopes, modules, and constructor injection while preserving one session owner; commit.
5. Verify/decompose destination UI, state mappers, ViewModels, Navigation 3 back stack, and package boundaries; commit in coherent increments.
6. Verify RootEncoder CameraX source integration and remove only obsolete fallback code after runtime evidence; commit.
7. Run the full verification matrix, update docs, inspect production literals, and create final test/docs commits.
8. With a physical device connected, validate camera enumeration and lens selection through the existing RootEncoder source, then record hardware results.

## Task Contract

- Scope: Android build recovery, toolchain/DI alignment, feature/navigation/state architecture, package organization, RootEncoder CameraX path, tests, docs, and device/receiver validation.
- Out of scope: Rust receiver or wire-protocol redesign; unrelated iOS work; unrelated existing untracked files.
- Files or areas likely to change: `android/`, Android tests, `docs/android-setup.md`, `docs/architecture.md`, relevant architecture/task evidence.
- Interfaces or behavior contracts: preserve `StreamEngine`, control protocol, MPEG-TS/UDP transport, session negotiation, one active stream owner, and user flow in the acceptance criteria.
- Risks and edge cases: AGP 9 built-in Kotlin/KSP compatibility, Navigation 3 version availability, emulator camera limitations, unavailable privileged v4l2/physical receiver validation, stale APKs, and device-specific CameraX/RootEncoder behavior.
- Open questions: N/A

## Verification Plan

- `work-context`, `task-ready`, and task artifact status updates at workflow boundaries.
- Clean `assembleDebug`, `test`, lint, formatting/check tasks, and relevant dependency graph/insight commands.
- APK DEX and manifest inspection with `apkanalyzer` or equivalent.
- `adb uninstall`, exact APK install, launcher start, filtered logcat, activity/process and Hilt startup checks on `emulator-5554`.
- Compose/unit/instrumentation tests for destination navigation, state mapping, settings, permissions, camera controls, and session ownership.
- Physical-device and desktop receiver checks where available; record unavailable camera/receiver cases explicitly.
- Final `git diff`, changed-production-literal audit, task evidence, and incremental commit history.

## Status

Available-path implementation and verification are complete. Physical-device
validation is now in progress on the connected Vivo V2413. The first physical
run exposed a semantic mismatch: the current adapter enumerates logical CameraX
IDs `0` and `1`, so `Lens 1` is the front camera rather than a rear physical
lens. Vivo logical camera `0` reports physical IDs `2`, `3`, and `4`; the
physical selector must enumerate those IDs and bind them through the same
RootEncoder-owned capture pipeline.

## Handoff Notes

- Next exact step: replace the logical-only selector path with a physical-camera binding that preserves logical rear ID `0`, enumerates its physical IDs `2`, `3`, and `4`, and validates the Vivo zoom lens through the single RootEncoder-owned pipeline. CameraX exposes `CameraInfo.getPhysicalCameraInfos()` and `CameraSelector.Builder.setPhysicalCameraId`, while RootEncoder 2.8.0 `CameraXSource.openCamera(CameraInfo)` does not expose the latter, so the adapter boundary needs a maintainable extension or an equivalent RootEncoder camera source.
- Files changed: Android build/source/test files, `docs/android-ui-rehaul-plan.md`, architecture/setup notes, and this task artifact; preserve pre-existing `docs/android-ui-rehaul-plan.html`.
- Commands run: `work-context`, `task-ready`, clean `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, `:app:lintDebug`, connected Android tests, APK manifest/DEX inspection, fresh uninstall/install, launcher start, receiver CLI, Rust format/tests/clippy, v4l2 capture, filtered logcat checks, physical-device ADB inspection, and RootEncoder source inspection.
- Errors encountered: the original build omitted Kotlin classes because `android.builtInKotlin=false` disabled the active compiler path; after removal, stale package/model references and a Navigation3/Activity dispatcher mismatch were corrected. The emulator exposed a RootEncoder 2.8.0 EGL/Scudo abort on stop; the adapter now uses one ordered stream/preview/source teardown sequence and the exact APK survives three stop actions including a stream restart.
- Verification evidence: clean `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, and `:app:lintDebug` passed; Android unit tests passed with 53 tests; all 9 connected Android tests passed on `codex-phone-webcam-api35` API 35; `/home/ltanaka/github/phone-webcam-project/android/app/build/outputs/apk/debug/app-debug.apk` contains `dev.mobilewebcam.sender.app.MobileWebcamApplication`, `dev.mobilewebcam.sender.app.MainActivity`, `dev.mobilewebcam.sender.session.StreamSessionControllerImpl`, and all three destination ViewModels in DEX, and the packaged manifest names the application, activity, and foreground service correctly; the exact APK SHA-256 is `66844efe8996c9ba40371ea4a83d0e67aa40407c916f835b3673552a128b0790`; after uninstalling the prior package, the exact APK installed successfully and `monkey` launched `dev.mobilewebcam.sender/.app.MainActivity` with no app fatal AndroidRuntime entry. Rust `fmt --check`, workspace tests, and clippy with `-D warnings` also passed. Current pins are AGP 9.3.1, Gradle 9.6.1, Kotlin 2.3.21, Hilt 2.60.1, KSP 2.3.10, Activity 1.13.0, Lifecycle 2.11.0, Navigation3 1.1.4, CameraX 1.6.1, and RootEncoder 2.8.0; both Android modules target JVM 17 through an auto-downloaded toolchain.
- Follow-up coverage: `StartupStateResolver` now resolves from an explicit approved-receiver snapshot and has both restore branches covered; feature tests exercise configured settings selection, unsupported camera presentation, waiting state, recoverable disconnection, and mapped zoom controls. The dependency insight reports CameraX 1.6.1 selected consistently across the atomic CameraX group; RootEncoder 2.8.0 requests CameraX 1.5.3 transitively, which resolves to the app's pinned 1.6.1 set.
- Runtime evidence: the final post-package-move exact APK installed, paired through reverse control, negotiated H.264, prepared the RootEncoder CameraXSource at 1920x1080/30, started MediaCodec, and delivered frames through GStreamer to `/dev/video10`. The final run reached `receiving` for session `cc3444ee-ebdd-4c6d-8873-7574ac808149` and produced ten 1920x1080 YUY2 v4l2loopback frames totaling 41,472,000 bytes; the preceding exact APK run also stopped and restarted successfully across sessions `e2f547eb-43b6-4a5d-9603-a79320578b36` and `cdd6044d-2f51-4049-9520-f01663b7ff9d`. The latest full diagnostic snapshot reported 607 decoded frames, a 1,211 ms first-frame delay, about 1.96 Mbps, and zero pipeline errors; queue pressure and continuity warnings make this functional smoke evidence rather than a latency baseline.
- Latest rebuilt APK runtime evidence: session `b53a0172-6bd3-4003-acec-9e325cbb3c50` reached `receiving` with H.264 and `avdec_h264-0`, reported 809,106 bps, and delivered three 1920x1080 YUY2 frames totaling 12,441,600 bytes. The rebuilt APK then completed `stream_stopping` and `stream_stopped` with no Scudo, abort, or app fatal entry; the receiver session was released. This confirms the current artifact's emulator stream start/stop path, while the longer diagnostic snapshot above remains the functional smoke result rather than a latency baseline.
- Current exact APK runtime evidence: session `495f4985-a4ff-4a21-9521-c1fbb8a2679b` reached `receiving` with H.264 and `avdec_h264-0`, reported 810,002 bps, and delivered three 1920x1080 YUY2 frames totaling 12,441,600 bytes to `/dev/video10`. The APK then completed `stream_stopping` and `stream_stopped`; the receiver returned HTTP 404 for the released session, and no Scudo, abort, or app fatal entry appeared.
- Camera boundary note: RootEncoder 2.8.0 `CameraXSource` remains the only capture path. Its `openCamera(CameraInfo)` method switches logical CameraX entries but cannot set CameraX's physical camera ID. The current physical run showed `Auto`, `Lens 0`, and `Lens 1`; selecting `Lens 1` emitted `camera_lens_selected` with camera ID `1` while the receiver stayed in `receiving`, confirming that this path switches to the front camera, not Vivo's rear zoom module. The Vivo camera service reports logical ID `0` with physical IDs `2`, `3`, and `4`; raw physical-lens selection remains open.
- Hardware availability: `adb devices -l` lists the Vivo V2413 (`adb-10DECJ0JKG0003C-aQ73eT._adb-tls-connect._tcp`) and `emulator-5554`. The Vivo camera service reports three normal/public camera devices; logical ID `0` is rear and exposes physical IDs `2`, `3`, and `4`, while logical ID `1` is front. The exact APK reached `receiving` on the Vivo and delivered three capture buffers after the logical front-camera switch, but the rear physical zoom lens has not yet been validated.
- Final commits: `0470275` launchable packaging, `5da4852` Hilt graph, `d10554f` destination UI, `423e9e1` initial Android toolchain, `899aee8` streaming runtime, `72bcfd8` initial documentation, `d5d5bce` persisted settings and approval routing, `d378b56` RootEncoder teardown, `a04d940` prior validation documentation, `60e3771` package organization, `17c9f8d` pairing navigation effects and focused mapper tests, `987599e` camera package completion, `b9ad785` explicit pairing reset and connected tests, `44de10c` Kotlin/KSP 2.3.21/2.3.10 alignment, `fb40fa0` current validation documentation, `ae94c2f` explicit production validation bounds, `b5e160a` top-level session lifecycle package, `7a1b104` final package smoke-test documentation, `6ab7f2b` startup and destination presentation coverage, `94234d1` destination presentation state split, and `48910d2` destination boundary documentation.
- Environment cleanup: the temporary emulator UDP relay and receiver process are stopped; the API 35 emulator remains running with the exact APK installed and launched.
- Unavailable checks: rear physical lens switching, long-duration thermal behavior, and a repeatable physical latency comparison remain open.
