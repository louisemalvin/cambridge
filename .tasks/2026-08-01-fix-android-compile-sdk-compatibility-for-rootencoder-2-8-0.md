# Fix Android compile SDK compatibility for RootEncoder 2.8.0

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Make the Android sender build with RootEncoder 2.8.0 by aligning its compile SDK,
Android Gradle Plugin, Gradle wrapper, and required Android Studio toolchain.

## Context

- RootEncoder 2.8.0 AAR metadata requires compile SDK 37.
- The app currently uses AGP 8.6.1, compileSdk 35, and an untracked Gradle 9.3.0 wrapper.
- API 37 requires AGP 9.1.1 or newer; AGP 9.1.1 uses Gradle 9.3.1 and JDK 17.
- Preserve targetSdk 35 unless runtime behavior changes are required.

## Decisions

- Use stable AGP 9.1.1 and Gradle 9.3.1 as the smallest toolchain update that supports API 37.
- Set compileSdk to 37 while retaining targetSdk 35.
- Keep RootEncoder 2.8.0; do not downgrade the streaming dependency.
- Keep the generated Gradle wrapper in the repository for reproducible Android builds.

## Acceptance Criteria

- A Gradle metadata check no longer reports RootEncoder compile SDK incompatibility.
- Android unit tests, lint, and debug assembly pass when SDK 37 and JDK 17 are available.
- Rust workspace checks remain unaffected and pass.
- The final repository has no accidental user secrets or unrelated changes.

## Implementation Plan

- Update Android version catalog and app compile SDK.
- Update the Gradle wrapper to the AGP-compatible Gradle version.
- Run Android and repository verification; fix compatibility issues caused by the toolchain update.
- Record the result and commit the focused fix.

## Task Contract

- Scope: Android build-tool compatibility for the existing Phase 1 sender.
- Out of scope: New streaming features, codec behavior changes, UI changes, and runtime target SDK migration.
- Files or areas likely to change: `android/`, this task artifact, Android setup documentation if needed.
- Interfaces or behavior contracts: Existing Android sender architecture and RootEncoder adapter remain unchanged.
- Risks and edge cases: AGP 9 built-in Kotlin behavior, old Android Studio versions, missing SDK 37, and generated wrapper files.
- Open questions: N/A

## Verification Plan

- Run `android/gradlew test`, `android/gradlew lint`, and `android/gradlew assembleDebug`.
- Run Rust formatting, clippy, tests, and build.
- Confirm `git diff --check` and clean status except committed changes.
- Document any physical-device validation that remains unavailable.

## Status

Complete. Build configuration changes are applied; Android verification is blocked only by the local host lacking Java and the Android SDK.

## Handoff Notes

- Next exact step: On a configured host, install SDK Platform 37 and run `android/gradlew test lint assembleDebug`.
- Files changed: Task artifact, Android version catalog, build scripts, Gradle wrapper, CI, and Android setup documentation.
- Commands run: `work-context`, `task-init`, `task-ready`, repository inspection, official Android AGP compatibility lookup, `android/gradlew -p android test lint assembleDebug`, Rust fmt/clippy/test/build, shell syntax checks, and `git diff --check`.
- Errors encountered: RootEncoder AAR metadata requires compile SDK 37; current project is on SDK 35 and AGP 8.6.1.
- Verification evidence: Official Android documentation lists AGP 9.1.1 and Gradle 9.3.1 for API 37. Rust checks passed. Android wrapper stopped before configuration because this host has no `java` command or `JAVA_HOME`.
