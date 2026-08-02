# Android video stabilization controls

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Expose one small Android-only stabilization On/Off control next to the existing lens and zoom controls. When enabled, prefer phone-provided optical stabilization and fall back to phone-provided electronic stabilization when optical stabilization is unavailable.

## Context

- RootEncoder 2.8.0 `Camera2Source` exposes optical and electronic stabilization methods and returns whether enabling them succeeded.
- Both stabilization modes are implemented by the phone camera pipeline; the app only requests the mode.
- Physical camera IDs remain opaque runtime values. No camera IDs or receiver changes are in scope.

## Decisions

- Keep the UI as one compact On/Off control, shown only when at least one phone stabilization mode is available.
- When enabled, use Optical first and Electronic only as fallback. Do not enable both together.
- Use RootEncoder's public camera methods. Do not add timing retries, always-on capture callbacks, or custom image stabilization.
- Treat physical-lens persistence as a device-test result rather than adding a fragile reapplication mechanism in this first experiment.

## Acceptance Criteria

- Android state exposes stabilization support and the selected On/Off state without RootEncoder types.
- Runtime Camera2 metadata controls whether the control appears.
- Enabling requests OIS first and falls back to EIS when OIS cannot be enabled.
- Disabling turns off both phone stabilization requests.
- Existing lens-switch behavior remains unchanged.
- No receiver, protocol, stream URL, or UDP behavior changes.
- Physical stabilization effectiveness remains pending device validation.

## Implementation Plan

- Add platform-neutral stabilization state and one controller method.
- Query logical-camera support through Camera2 metadata in the RootEncoder adapter.
- Apply OIS first, EIS fallback, or both off through RootEncoder's public `Camera2Source` API.
- Add the compact Compose control and ViewModel wiring.
- Add state/UI tests, update Android setup notes, and run Android verification gates.

## Task Contract

- Scope: Android phone-provided optical/electronic stabilization On/Off selection for the existing preview and stream.
- Out of scope: receiver changes, protocol changes, physical-lens classification, active-lens metadata display, custom stabilization algorithms, and automatic image-quality claims.
- Files or areas likely to change: `android/app/src/main/java/dev/mobilewebcam/sender/camera`, RootEncoder stream engine, sender ViewModel/streaming UI, Android tests, and `docs/android-setup.md`.
- Interfaces or behavior contracts: the camera boundary accepts a platform-neutral stabilization mode; RootEncoder remains confined to the adapter.
- Risks and edge cases: devices may expose neither mode; a request may fail on a particular stream configuration; unsupported requests must not crash the app.
- Open questions: none.

## Verification Plan

- Run `task-ready` after filling this artifact.
- Run Android unit tests, lint, debug APK assembly, and instrumentation APK assembly.
- On the user's phone, compare Off and On while moving the phone, especially on the tele lens, then repeat after switching physical lenses.

## Status

Implementation complete. Android verification passes; physical stabilization behavior is pending device validation.

## Handoff Notes

- Next exact step: install the debug APK, tap Stabilization On, and compare movement with Off on the tele lens and after lens switching.
- Files changed: camera state/controller, RootEncoder adapter, ViewModel, streaming UI, Android tests, and `docs/android-setup.md`.
- Commands run: `task-ready`; `work-context`; `git diff --check`; `JAVA_HOME=/opt/android-studio/jbr PATH=/opt/android-studio/jbr/bin:$PATH ./android/gradlew -p android test lint assembleDebug assembleDebugAndroidTest`.
- Errors encountered: none affecting the task.
- Verification evidence: Gradle completed successfully with 159 actionable tasks; no physical Android device is available in this environment.
