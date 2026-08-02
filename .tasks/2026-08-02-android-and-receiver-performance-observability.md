# Android and receiver performance observability

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Create a trustworthy performance baseline and a repeatable inspector for sender-to-receiver smoothness before changing the media pipeline. The inspector must show where instability occurs and make later improvements comparable across runs.

## Context

- The Android sender now supports camera preview interaction, physical-lens selection, and phone-provided stabilization.
- The receiver already exposes `/v1/sessions/{sessionId}` with state, decoder, received bitrate, and timeout count.
- The receiver already has tracing logs, first-decoded-frame tracking, byte accounting, timeout detection, and bounded output queues.
- Existing media and control contracts must remain stable until the measurements prove that a contract change is needed.

## Decisions

- Measure first. Do not optimize based on visual impressions or add buffering before identifying the source of instability.
- Use structured, sampled diagnostics rather than per-frame Android log spam.
- Treat the receiver as authoritative for received smoothness: frame cadence, jitter, missing/late data, decoder, queue, and timeout observations.
- Treat the sender as authoritative for source and lifecycle timing: camera start, encoder start, negotiated profile, target bitrate/FPS, lens, stabilization selection, and failures.
- Correlate both sides with the negotiated session ID and a human-readable run ID.
- Build the first inspector as a receiver-side CLI/reporting tool that polls the existing session endpoint and consumes structured receiver logs. Keep Android Logcat export as a separate input rather than coupling the data plane to diagnostics.
- Do not add a protocol version bump or per-frame wire metadata in the baseline phase.

## Acceptance Criteria

- A single run produces a timestamped report containing run/session identity, device/network/profile conditions, and sender/receiver lifecycle events.
- The report includes first-frame time, target versus observed frame rate, inter-frame interval statistics, bitrate over a recent window, timeout count, continuity/error warnings, decoder, and receiver state transitions.
- The inspector can distinguish startup delay, steady-state jitter, packet/stream interruption, decoder stalls, and output-queue pressure where the underlying component exposes the signal.
- Android logging is structured and sampled, with no per-frame log loop in the hot path.
- Receiver metrics are unit tested, and the inspector has fixture-driven parsing/report tests.
- The existing sender/receiver media behavior and control contract remain unchanged in the measurement phase.
- A documented baseline procedure can be repeated on the user's phone over Wi-Fi and USB tethering.

## Implementation Plan

- Define a small diagnostic event/metric vocabulary and report schema in `docs/`.
- Instrument Android session lifecycle and camera/encoder decisions through the existing `AppLogger`, including periodic aggregate samples only.
- Extend receiver metrics with windowed frame cadence/jitter and relevant pipeline signals without changing MPEG-TS/UDP behavior.
- Expose the receiver diagnostics through a stable inspector-facing surface, preferring existing HTTP session data plus structured logs before changing protocol DTOs.
- Implement a CLI inspector that polls a session, captures a bounded run, and emits both human-readable summary and machine-readable JSON.
- Add fixtures/unit tests, then run baseline measurements before any performance tuning.

## Task Contract

- Scope: Android sender logging, Rust/GStreamer receiver metrics, and a repeatable receiver-side performance inspector/report.
- Out of scope: codec changes, queue-size tuning, transport replacement, CameraX migration, receiver UI redesign, and optimization work based on unmeasured assumptions.
- Files or areas likely to change: Android logging/session/RootEncoder adapter, receiver-gstreamer metrics and pipeline observation, receiver HTTP/CLI diagnostics, `docs/latency-testing.md`, and new inspector tests/fixtures.
- Interfaces or behavior contracts: diagnostics remain out-of-band or additive; MPEG-TS/UDP media behavior and existing control semantics remain unchanged during baseline collection.
- Risks and edge cases: clock domains differ between phone and desktop; per-frame instrumentation can perturb latency; metrics must distinguish no packets, decoder stalls, and output-consumer backpressure; Logcat availability varies by device.
- Open questions: none.

## Verification Plan

- Run Android unit/lint/build gates and Rust format/test/clippy gates.
- Run inspector tests against deterministic receiver/session/log fixtures.
- Execute repeatable 60-second baseline runs at 1080p30 over Wi-Fi and USB tethering, recording codec, profile, lens, stabilization state, phone model/API, receiver host, decoder, and output consumer.
- Repeat each condition enough to report median, p95, maximum, and timeout/continuity counts.
- Compare the inspector report with existing `/v1/sessions/{sessionId}` output and confirm no media/control regression.

## Status

Ready for implementation in a new session. No observability implementation has started.

## Handoff Notes

- Next exact step: read this artifact, define the diagnostic vocabulary/report schema, then instrument the receiver metrics before changing pipeline behavior.
- Files changed: this task artifact only after the implementation commit.
- Commands run: `task-init`; repository logging/metrics/latency-path inspection; prior Android and Rust verification gates are recorded in the preceding implementation task artifacts.
- Errors encountered: none.
- Verification evidence: implementation snapshot committed as `cc70897`; worktree was clean before this handoff artifact was created.
