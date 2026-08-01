# Fix Android Ktor runtime mismatch and add copyable diagnostics

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Make Android receiver health checks run without the `ByteChannel` runtime crash and give users a single button to copy full technical failure details for troubleshooting.
One or two sentences describing the user-visible outcome or concrete engineering result.

## Context

The Android app directly pins Ktor `2.3.12`, while RootEncoder's current dependency graph resolves Ktor `3.5.1` and kotlinx.coroutines `1.11.0`. The resulting mixed runtime caused the reported `No static method ByteChannel` health-check failure. The Compose failure screen currently shows only a short message and has no clipboard action.
Only the constraints and facts needed to implement safely.

## Decisions

- Align the direct Ktor client dependencies with Ktor `3.5.1`, the current release verified in official Ktor documentation and already present in the resolved graph.
- Align the direct coroutines version with `1.11.0`, the current release resolved by the app and verified from the official project release.
- Keep CIO behind the existing HTTP adapter boundary.
- Add a copy action for technical diagnostics without displaying raw stack traces in the normal UI.

## Acceptance Criteria

- Android health requests no longer fail from mixed Ktor binary APIs.
- The dependency graph contains one Ktor version and one coroutines version for the debug runtime classpath.
- Failed connection state offers a visible `Copy error details` action.
- Copied diagnostics include user-facing failure, receiver endpoint/profile context, exception causes, and stack trace text.
- Android unit tests, lint, and debug APK build pass.

## Implementation Plan

- Update the version catalog to current aligned Ktor/coroutines versions.
- Add technical diagnostic text to UI state and generate it at the ViewModel boundary.
- Add clipboard handling and copy feedback to the Connect screen.
- Add focused tests for diagnostic formatting and run dependency insight to confirm alignment.
- Format, test, lint, assemble, and commit the focused fix.

## Task Contract

- Scope: Android HTTP dependency alignment and failure-diagnostic copy UX.
- Out of scope: changing the receiver protocol, replacing the HTTP engine, RootEncoder streaming behavior, or adding accounts/telemetry.
- Files or areas likely to change: `android/gradle/libs.versions.toml`, sender UI state/ViewModel/screens, and Android tests.
- Interfaces or behavior contracts: technical details remain app-local and are copied only after an explicit user action.
- Risks and edge cases: long stack traces, clipboard privacy, Ktor 3 API differences, and stale APKs on the physical phone.
- Open questions: None.

## Verification Plan

- Run dependency insight for Ktor and coroutines.
- Run `./gradlew test lint assembleDebug` with the configured JDK/SDK.
- Verify no Ktor 2.x artifacts remain in the debug runtime classpath.
- Verify the copied diagnostic includes the reported exception shape through unit tests.

## Status

Complete.

## Handoff Notes

- Next exact step: install the rebuilt APK on a physical phone and retry the receiver health check. If it still fails, tap `Copy error details` and paste the clipboard contents into the chat.
- Files changed: Android version catalog, sender UI state/ViewModel/screens, diagnostic formatter, and diagnostic unit test.
- Commands run: Gradle `test`, `lint`, `assembleDebug`, dependency insight for Ktor and coroutines, `scripts/development/check-all.sh`, and `git diff --check`.
- Errors encountered: the original dependency graph mixed direct Ktor 2.3.12 with RootEncoder-resolved Ktor 3.5.1 APIs; the direct dependencies are now aligned. No physical-device runtime test was available in this environment.
- Verification evidence: Android tests, lint, debug APK assembly, Rust workspace checks in `check-all.sh`, and dependency inspection passed. The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.
