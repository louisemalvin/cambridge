# Upgrade Android Gradle wrapper to 9.5.0

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Align the checked-in Android Gradle wrapper with Android Gradle Plugin 9.3.x so Android Studio and terminal builds stop reporting an incompatible Gradle version.

## Context

The wrapper currently points to Gradle 9.3.1. The current Android toolchain warning says AGP 9.3.1 requires Gradle 9.5.0. The repository currently declares AGP 9.1.1, for which Gradle 9.3.1 is the documented minimum, so the warning likely reflects a local plugin upgrade or stale Android Studio sync; Gradle 9.5.0 is compatible with both configurations.

## Decisions

- Update only the wrapper distribution to Gradle 9.5.0 and document the checked-in toolchain version.
- Keep AGP at the repository's declared 9.1.1 unless the build proves an AGP change is required.
- Use the official Android compatibility matrix as the version source.

## Acceptance Criteria

- `android/gradle/wrapper/gradle-wrapper.properties` points to Gradle 9.5.0.
- The Android wrapper reports Gradle 9.5.0.
- Android unit tests, lint, and debug APK assembly pass.
- Setup documentation no longer tells users to use Gradle 9.3.1.

## Implementation Plan

- Update the wrapper distribution URL and setup documentation.
- Run the wrapper version check and Android verification tasks.
- Format/check the diff, update task evidence, and commit.

## Task Contract

- Scope: Android Gradle wrapper version and setup documentation.
- Out of scope: changing AGP, Kotlin, Android Studio, SDK, or application code.
- Files or areas likely to change: `android/gradle/wrapper/gradle-wrapper.properties` and `docs/android-setup.md`.
- Interfaces or behavior contracts: the checked-in wrapper is the source of truth for terminal and Android Studio sync builds.
- Risks and edge cases: wrapper download may require network access; AGP 9.1.1 and Gradle 9.5.0 must remain compatible.
- Open questions: None.

## Verification Plan

- Run `./android/gradlew -p android --version`.
- Run `./android/gradlew -p android test lint assembleDebug` with the configured JDK and SDK.
- Confirm no stale 9.3.1 toolchain reference remains in setup documentation.

## Status

Complete.

## Handoff Notes

- Next exact step: reopen or sync the Android project, then install the newly assembled APK.
- Files changed: `android/gradle/wrapper/gradle-wrapper.properties` and `docs/android-setup.md`.
- Commands run: `./android/gradlew -p android --version`; `./android/gradlew -p android test lint assembleDebug`; `git diff --check`.
- Errors encountered: the wrapper downloaded Gradle 9.5.0 successfully. Android lint retained existing deprecation warnings and an SDK XML version warning, but the build passed.
- Verification evidence: wrapper reports Gradle 9.5.0; Android tests, lint, and debug APK assembly pass with Gradle 9.5.0.
