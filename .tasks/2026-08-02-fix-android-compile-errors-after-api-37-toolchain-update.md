# Fix Android compile errors after API 37 toolchain update

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Make the Android sender compile cleanly after the API 37 and Gradle 9.3.1
toolchain update.

## Context

- The user reports three Kotlin source errors in the HTTP adapter and Compose UI.
- The repository wrapper is already pinned to Gradle 9.3.1, but this must be verified.
- The host now contains Android Studio's JBR and SDK Platform 37, so Android verification is possible with explicit environment variables.

## Decisions

- Add only missing imports or narrowly scoped source fixes.
- Keep the existing AGP 9.1.1, compile SDK 37, Kotlin 2.2.10, and Gradle 9.3.1 pins.
- Preserve the project-owned codec model and Compose UI architecture.

## Acceptance Criteria

- `compileDebugKotlin` passes.
- Android unit tests, lint, and debug assembly pass.
- The wrapper distribution URL is Gradle 9.3.1.
- Rust checks remain green and the repository is clean after the focused commit.

## Implementation Plan

- Inspect the reported source locations and imports.
- Patch the missing codec and Compose imports or equivalent narrow source issue.
- Run the Android wrapper with the discovered JDK and SDK, then run repository checks.
- Update this artifact and create a focused commit.

## Task Contract

- Scope: Android compilation errors introduced or exposed by the API 37 toolchain.
- Out of scope: Runtime streaming behavior, protocol changes, dependency upgrades beyond Gradle verification.
- Files or areas likely to change: `android/app/src/main/java/`, wrapper metadata, this task artifact.
- Interfaces or behavior contracts: Existing HTTP control and Compose screen behavior remains unchanged.
- Risks and edge cases: Compose scope extension resolution under the current BOM and Kotlin compiler.
- Open questions: N/A

## Verification Plan

- Run `JAVA_HOME=/opt/android-studio/jbr ANDROID_SDK_ROOT=$HOME/Android/Sdk android/gradlew -p android test lint assembleDebug`.
- Run Rust fmt, clippy, tests, build, shell syntax, and `git diff --check`.
- Confirm `distributionUrl` ends in `gradle-9.3.1-bin.zip`.

## Status

Complete. Android compilation, tests, lint, debug assembly, and repository checks pass.

## Handoff Notes

- Next exact step: Install the generated debug APK on a physical phone and run the documented receiver session.
- Files changed: Task artifact, HTTP client imports, Compose imports, test logger injection, and camera hardware declaration.
- Commands run: `work-context`, `task-init`, `task-ready`, source inspection, SDK/JBR discovery, `:app:compileDebugKotlin`, targeted session tests, `test lint assembleDebug`, and `scripts/development/check-all.sh`.
- Errors encountered: Initial compile errors for missing `VideoCodec` and `fillMaxWidth`, invalid direct import of the `ColumnScope.weight` member extension, unit tests calling unmocked `android.util.Log`, and a camera manifest lint error. All were fixed.
- Verification evidence: Gradle 9.3.1 wrapper ran successfully with `/opt/android-studio/jbr` and `/home/ltanaka/Android/Sdk`; Android build passed with 31 non-fatal lint warnings; Rust checks passed.
