# phone-webcam-project Agent Guide

## Project

Phase 1 monorepo for a low-latency Android phone webcam sender and reusable Rust desktop receiver. The current stage is initial implementation, with Linux as the first output platform.

## Stack

- Runtime: Android/Kotlin sender; Rust desktop receiver
- Framework: Jetpack Compose, RootEncoder adapter, GStreamer, Axum
- Package manager: Gradle Version Catalog and Cargo workspace
- Database: N/A
- Deployment: Local trusted network; Linux v4l2loopback output

## Commands

- Install: See `docs/android-setup.md` and Linux setup guides
- Dev: `cargo run -p receiver-cli -- --help`; Android Studio or `./gradlew`
- Test: `cargo test --workspace`; `./gradlew test`
- Lint: `cargo clippy --workspace --all-targets -- -D warnings`; `./gradlew lint`
- Typecheck: `cargo check --workspace`; Android compilation via Gradle
- Build: `cargo build --workspace`; `./gradlew assembleDebug`

## Architecture

- `protocol/`: versioned JSON control contract and fixtures
- `android/`: one Kotlin/Compose sender application
- `desktop/crates/receiver-protocol`: protocol DTOs only
- `desktop/crates/receiver-core`: platform-independent session and negotiation logic
- `desktop/crates/receiver-control-http`: HTTP control server only
- `desktop/crates/receiver-gstreamer`: GStreamer media receiver only
- `desktop/crates/receiver-platform-linux`: Linux and v4l2loopback output only
- `desktop/apps/receiver-cli`: thin composition root
- `docs/` and `scripts/`: durable setup, testing, and operational guidance

## Known Traps

- Keep codec, container, and transport terminology distinct: H.264/H.265, MPEG-TS, and UDP unicast.
- Do not let RootEncoder, HTTP DTOs, GStreamer, or Linux APIs cross their declared boundaries.
- Java/Android and privileged v4l2loopback validation may be unavailable in development environments.
