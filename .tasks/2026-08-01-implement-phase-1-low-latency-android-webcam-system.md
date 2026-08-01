# Implement Phase 1 low-latency Android webcam system

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Build the Phase 1 Android sender, reusable Rust receiver, Linux virtual-camera output, versioned control protocol, tests, scripts, CI, and operational documentation for low-latency H.264/H.265 MPEG-TS over UDP webcam streaming.

## Context

- One monorepo with Android and Rust desktop components.
- Control plane is versioned HTTP/JSON on port 5001; media plane is MPEG-TS over UDP on port 5000.
- H.264 is the compatibility codec; Auto prefers H.265 only when sender, receiver, profile, and preparation all support it.
- Receiver must remain alive across sender disappearance and recover after UDP interruption.
- Linux output is v4l2loopback through GStreamer v4l2sink with bounded leaky queues.
- Phase 1 is video-only and for a trusted local network. No auth, encryption, audio, cloud, discovery, Tauri, or non-Linux virtual-camera backends.
- Current environment has Rust and GStreamer but no Java/Android SDK, physical phone, OBS, or confirmed v4l2loopback device.

## Decisions

- Use RootEncoder only behind an Android adapter and pin it through the Gradle version catalog after compatibility verification.
- Use official Rust GStreamer bindings and decodebin with codec-specific parsers.
- Keep receiver protocol, core, HTTP, GStreamer, Linux output, and CLI responsibilities in separate crates.
- Use task-specific domain models rather than exposing external library enums or DTOs.

## Acceptance Criteria

- Rust workspace formats, checks, tests, and builds where installed dependencies allow.
- Protocol fixtures, schema, Rust models, Kotlin models, and negotiation tests agree.
- Receiver exposes health, capabilities, prepare, session state, and stop endpoints.
- Receiver supports shared H.264/H.265 MPEG-TS input architecture, timeout recovery, bounded queues, and Linux v4l2sink integration.
- Android has typed capability probing, control client, negotiation, lifecycle state, Compose screens, foreground service, and RootEncoder boundary.
- Wi-Fi and USB tethering use the same endpoint workflow and are documented.
- Every implementation milestone has a focused commit, with unavailable hardware/toolchains recorded honestly.

## Implementation Plan

- Scaffold repository, CI, docs, Rust workspace, and Android project.
- Define protocol schema, examples, Rust/Kotlin models, and fixture tests.
- Implement receiver core negotiation/state and HTTP control server.
- Implement GStreamer receiver, Linux output backend, scripts, and synthetic tests.
- Implement Android domain, capability probe, control adapter, session controller, UI, RootEncoder adapter, and foreground lifecycle.
- Add network/reliability documentation, notices, manual validation, and final verification.

## Task Contract

- Scope: All Phase 1 milestones in the user handoff.
- Out of scope: The deferred work listed in the handoff, including audio, security, discovery, Tauri, Windows, and macOS.
- Files or areas likely to change: Repository root, `protocol/`, `android/`, `desktop/`, `docs/`, `scripts/`, `.github/`.
- Interfaces or behavior contracts: `/v1/health`, `/v1/capabilities`, `/v1/sessions/prepare`, `/v1/sessions/{id}`, and `DELETE /v1/sessions/{id}`; stable lowercase protocol IDs.
- Risks and edge cases: Missing Android SDK, missing plugins, missing v4l2loopback, absent hardware decoder, stream codec mismatch, sender restart, and partial preparation cleanup.
- Open questions: N/A
- RootEncoder check: 2.8.0 is the current upstream release and exposes `library` plus the modern `StreamBase`/`UdpStream` API with H.264/H.265, MPEG-TS UDP, surface encoding, bitrate updates, and resource release.
- Environment check: Local Android compilation remains unverified because Java/Android SDK are absent.

## Verification Plan

- Run Rust fmt, clippy, tests, and build; run Android Gradle tests, lint, and assemble when the SDK/toolchain exists.
- Run synthetic H.264 and H.265 GStreamer sender tests against receiver fakesink where available.
- Run protocol fixture and negotiation tests on both platforms where available.
- Document exact v4l2loopback, OBS/browser, Wi-Fi, USB tethering, restart, latency, and physical-device checks that cannot run here.

## Status

In progress. All implementation milestones are committed through `b0369b6`; final verification and handoff reporting remain.

## Handoff Notes

- Next exact step: Run the complete Rust, shell, CLI, protocol, and available Android checks; record skipped hardware/toolchain checks; then mark the task complete.
- Files changed: `AGENTS.md`, repository scaffold, protocol contract, receiver core, HTTP control server, GStreamer receiver, Linux platform backend, Linux scripts, Android sender, receiver CLI, recovery watchdog, documentation, this task artifact.
- Commands run: `agent-init`, `task-init`, `task-ready`, `work-context`, repository/toolchain inspection, upstream RootEncoder source inspection, Rust fmt/test/clippy/build/check, CLI help and print modes, JSON syntax and schema validation, HTTP route test, GStreamer H.264/H.265 construction and parser tests, Linux device test, shell syntax checks.
- Errors encountered: Initial directory was empty and not a Git repository; Android Java tooling is unavailable; local GStreamer installation lacks `udpsrc` and `udpsink`, so live UDP tests are skipped; Android Gradle test/lint/assemble commands cannot run because the wrapper is absent.
- Verification evidence: Upstream RootEncoder README and 2.8.0 sources confirm the selected artifact and API boundary; the local environment reports no Java executable.
