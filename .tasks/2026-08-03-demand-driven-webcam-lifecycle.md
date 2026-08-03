# Demand-Driven Webcam Lifecycle

## Goal

Make the Linux receiver keep a selectable virtual camera in paired standby and start/stop the Android camera only while real V4L2 consumers use it.

## Context

- Android sender-control currently uses v1 start-only messages over TCP 53555.
- Desktop discovery currently starts the phone stream as soon as a sender is reachable.
- v4l2loopback 0.15.4 is loaded on kernel 7.1.5; `/dev/video10` is present.
- The worktree contains unrelated Android, GStreamer, and documentation changes. Preserve them.
- iOS is explicitly out of scope and must remain untouched.

## Decisions

- Replace sender-control with a clean discriminated v2 contract using `streamId`, authenticated `stop`, and standby/streaming/busy advertisement states.
- Keep one request/response per newline-delimited TCP connection on port 53555.
- Use v4l2loopback private client-usage events, not process scanning or `/proc` polling.
- Keep a persistent V4L2 producer with generated black standby frames and bounded newest-frame-wins live output.
- Keep sender reachability, demand, and media lifecycle independent.
- Use the existing receiver HTTP session for Android crash-recovery polling, with named interval/failure-threshold constants.
- Commit task-owned changes by milestone without reverting pre-existing work.

## Acceptance Criteria

- Discovery alone never sends `start` or creates a temporary receiver session.
- Standby leaves the virtual camera visible, emits black frames, and leaves the phone camera off.
- First sustained consumer starts exactly one stream generation; additional consumers do not duplicate it.
- Final consumer release sends an authenticated idempotent stop and returns output to standby without detaching it.
- Stream generations and stale responses/stops cannot affect newer generations.
- Android stop cleans media before receiver-session deletion and leaves the control listener available.
- Android watchdog stops media after bounded receiver-session failures but ignores one transient failure.
- Rust unit/clippy/format checks pass; Android results are reported separately if Java remains unavailable; real Linux event checks are attempted and documented.
- No files under `ios/` change.

## Implementation Plan

1. Stabilize sender-control v2 DTOs, schemas, fixtures, and docs.
2. Add isolated Linux v4l2loopback client-usage event decoding, baseline conversion, debounce, and tests.
3. Add persistent virtual-camera output and standby/live switching without per-session sink ownership.
4. Separate discovery from demand and add a testable desktop stream coordinator with preferred-sender and generation policy.
5. Add Android v2 dispatch, authenticated stop/idempotency, cleanup ordering, and receiver-session watchdog.
6. Add integration scripts/docs and run repository, Linux, and available Android verification.

## Task Contract

- Scope: `protocol/`, desktop Rust receiver and desktop app, Android sender-control/session lifecycle, Linux scripts/docs.
- Out of scope: `ios/`, audio, codec changes, other desktop platforms, major UI redesign, WebSockets, process-name detection.
- Files or areas likely to change: sender-control schemas/fixtures/DTOs; Linux v4l2/output modules; receiver runtime/discovery; Android discovery/session tests; architecture/testing docs.
- Interfaces or behavior contracts: v2 JSON uses camelCase fields and UUID-like `streamId`; one bounded JSON object per TCP connection; existing receiver HTTP/media wire contract remains.
- Risks and edge cases: pre-existing dirty files overlap implementation; private ioctl ABI may be unavailable; Java and privileged device checks may be unavailable; persistent GStreamer output needs an explicit ownership seam.
- Open questions: None.

## Verification Plan

- Baseline and final `cargo fmt --all --check`, `cargo test --workspace`, `cargo clippy --workspace --all-targets -- -D warnings`.
- Android `./gradlew test` and `./gradlew lint` when a JDK is available; otherwise record the environment blocker.
- `scripts/development/check-all.sh`.
- New `scripts/linux/test-demand-driven-webcam.sh` plus existing virtual-camera/setup/synthetic sender checks against `/dev/video10`.
- Unit tests for protocol, demand conversion/debounce, coordinator transitions, persistent-output policy, Android coordinator/watchdog behavior.
- Manual lifecycle evidence where a physical Android device is available.

## Status

Baseline complete. Protocol implementation is next.

## Handoff Notes

- Next exact step: inspect all sender-control fixtures and desktop/Android test seams, then add v2 schema/DTOs without changing iOS.
- Files changed: only this task artifact so far; pre-existing worktree changes remain untouched.
- Commands run: `git status --short --branch`; Rust format/tests/clippy; Android `./gradlew test`; kernel/module/v4l2 inspection; `work-context AGENTS.md`.
- Errors encountered: Android test blocked because no `java` command or `JAVA_HOME`; `work-context AGENTS.md` reports the guide itself is not a task contract.
- Verification evidence: Rust baseline passed; v4l2loopback 0.15.4 loaded and `/dev/video10` listed; private event support still needs probing.
