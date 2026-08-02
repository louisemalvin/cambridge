# Automatic diagnostics capture for assistant analysis

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

The receiver automatically retains a bounded diagnostic timeline for the most
recent completed stream. After the user runs the Android app, the assistant
can fetch and analyse that run without asking for a session ID, Logcat export,
or manual report construction.

## Context

- Baseline point-in-time receiver diagnostics already exist at
  `/v1/sessions/{sessionId}/diagnostics`.
- The HTTP watchdog already refreshes receiver state while a session is active.
- Android emits structured Logcat events; Logcat remains an optional separate
  input that the assistant may pull with `adb` when available.
- Keep the media protocol and existing session response unchanged.
- Preserve crate boundaries: capture coordination belongs in receiver-core,
  HTTP exposure in receiver-control-http, and report parsing in receiver-cli
  only if required.

## Decisions

- Capture snapshots automatically from the existing receiver refresh path.
- Retain only a bounded recent run in memory and expose it through an
  additive latest-run diagnostics endpoint, so normal receiver startup needs
  no new user flags or manual session discovery.
- The assistant owns post-run collection and analysis. The user only runs and
  stops the Android stream.
- Do not couple Android Logcat or sender data to the media/control protocol.

## Acceptance Criteria

- Every active receiver session accumulates timestamped diagnostics snapshots
  without per-frame logging or an unbounded collection.
- Stopping a session preserves its completed diagnostic run for later access.
- `GET /v1/diagnostics/latest` returns the latest run after a normal stop and
  returns a clear not-found response before any run exists.
- A new session replaces the previous latest run only after it has produced
  its own captured data.
- Tests cover capture, bounded retention, endpoint serialization, and the
  no-run response.
- Documentation tells the user to run the app and then say it is finished;
  it does not require the user to export logs or identify a session.

## Implementation Plan

1. Add a serializable receiver diagnostic-run envelope and bounded snapshot
   retention to `ReceiverService`.
2. Finalize the in-progress run on explicit stop and timeout cleanup, while
   preserving the latest completed run for inspection.
3. Add the additive latest-run HTTP route and focused route/service tests.
4. Update diagnostics and latency-testing guidance for assistant-driven
   collection, including optional automatic `adb` Logcat retrieval.
5. Run Rust formatting, tests, check, clippy, build, and diff validation.

## Task Contract

- Scope: Automatic receiver-side capture and a latest completed-run
  diagnostics API, plus user-facing instructions for assistant analysis.
- Out of scope: Changes to MPEG-TS/UDP, Android media behavior, protocol
  negotiation, persistent databases, cloud upload, or requiring the user to
  export Logcat.
- Files or areas likely to change: `receiver-core` diagnostics/service/error,
  `receiver-control-http` routes/error/tests, `docs/diagnostics.md`, and
  `docs/latency-testing.md`.
- Interfaces or behavior contracts: new
  `GET /v1/diagnostics/latest`; existing endpoints remain compatible; the
  latest run is bounded and JSON uses the existing diagnostics schema.
- Risks and edge cases: process restart loses in-memory history; very long
  sessions must not grow memory; timeout-driven cleanup must finalize the run;
  a session with no available media diagnostics must return a clear error.
- Open questions: None.

## Verification Plan

- Unit-test service retention and replacement behavior with fake receivers.
- Test latest-run JSON and no-run HTTP responses.
- Run `cargo fmt --manifest-path desktop/Cargo.toml --all`.
- Run workspace `cargo check`, `cargo test`, `cargo clippy --all-targets
  -- -D warnings`, `cargo build`, and `git diff --check`.
- After handoff, the user runs one stream; the assistant fetches the latest
  endpoint and pulls `adb logcat` directly when a connected device exposes it.

## Status

Implementation and one end-to-end receiver capture complete; sender-side
Logcat correlation remains unavailable because no ADB device is connected.

## Handoff Notes

- Next exact step: optionally repeat under another network condition or with
  H.264 for comparison; no code action is required for this run.
- Files changed: `desktop/crates/receiver-core/src/{diagnostics.rs,lib.rs,
  error.rs,service.rs,service_tests.rs}`;
  `desktop/crates/receiver-control-http/src/{error.rs,routes.rs,server.rs}`;
  `docs/diagnostics.md`; `docs/latency-testing.md`.
- Commands run: `task-init`, `task-ready`, `work-context`, `cargo fmt`,
  workspace `cargo test`, `cargo check`, `cargo clippy --all-targets --
  -D warnings`, `cargo build`, `cargo fmt -- --check`, and `git diff --check`.
- Errors encountered: first clippy run flagged a test range expression;
  changed it to an inclusive range and reran successfully. The initial
  physical run used an older installed receiver and was discarded. The
  rebuilt receiver captured the completed run; ADB is installed under the
  Android SDK but currently reports no connected devices.
- Verification evidence: all workspace tests passed; all workspace check,
  clippy, and build commands passed; formatting and diff checks passed.
  Captured receiver run `7b1c86aa-3ed4-4d95-a63b-cdee0b320632`: 49.4 seconds,
  H.265 1920x1080 at 7 Mbps, 199 snapshots, first frame at 1.314 seconds,
  final 29.97 FPS, final p95 frame interval 48.39 ms, maximum observed frame
  gap 564.00 ms, zero timeouts/continuity warnings/pipeline errors, software
  decoder `avdec_h265`.
  Follow-up run `e694a63c-5122-489e-b2ca-7ac1510083db`: 53.2 seconds,
  forced H.264 3840x2160 at 32 Mbps, first frame at 1.429 seconds, final
  29.93 FPS, final p95 frame interval 53.40 ms, run-wide maximum frame gap
  802.83 ms, seven MPEG-TS continuity warnings, 183 high-watermark samples,
  and software decoder `avdec_h264`. ADB Logcat correlated the sender
  negotiation to `profile=4k30` and `preference=FORCE_H264` on a vivo V2413.
