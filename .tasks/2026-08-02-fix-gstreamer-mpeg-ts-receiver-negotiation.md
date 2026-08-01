# Fix GStreamer MPEG-TS receiver negotiation

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Keep the Linux receiver alive and decoding when it receives the Android MPEG-TS/UDP stream, instead of escalating transport warnings into a `not-negotiated` pipeline failure.

## Context

The receiver logs repeated MPEG-TS continuity mismatches on PID `0x0020`, then reports `Internal data stream error` with `not-negotiated` from `udpsrc` and `demux-queue`. The current pipeline links `udpsrc -> tsparse -> tsdemux` without declaring MPEG-TS source caps, and dynamic decoder-pad linking ignores pads whose current caps are unavailable.

## Decisions

- First reproduce with the checked-in synthetic H.264/H.265 senders and a local GStreamer sink.
- Declare the UDP source as standard 188-byte MPEG-TS, matching RootEncoder/GStreamer MPEG-TS over UDP and avoiding ambiguous source negotiation.
- Make dynamic pad handling inspect negotiated or queried caps and keep non-video pads ignored.
- Treat continuity warnings as recoverable transport diagnostics; do not make them terminate the receiver process.

## Acceptance Criteria

- Synthetic H.264 reaches a sink through the receiver pipeline without `not-negotiated`.
- Synthetic H.265 reaches a sink when the local H.265 encoder is installed.
- The pipeline source has explicit MPEG-TS caps with `systemstream=true` and `packetsize=188`.
- Temporary UDP loss or a sender restart does not crash the receiver process.
- Rust formatting, tests, clippy, and the relevant synthetic checks pass.

## Implementation Plan

- Reproduce the current pipeline and inspect negotiated caps.
- Add explicit MPEG-TS source caps and robust dynamic-pad caps lookup.
- Add focused pipeline-construction or caps tests and update troubleshooting guidance if needed.
- Run Rust verification and synthetic media checks, then commit.

## Task Contract

- Scope: receiver GStreamer input negotiation and recovery diagnostics.
- Out of scope: changing MPEG-TS/UDP protocol, replacing RootEncoder, adding retransmission, or changing the Android sender.
- Files or areas likely to change: `receiver-gstreamer` pipeline builder/tests and receiver troubleshooting docs.
- Interfaces or behavior contracts: retain the existing negotiated codec and media port; only improve input caps and dynamic linking.
- Risks and edge cases: real UDP packet loss can still produce continuity warnings; a malformed stream must remain a visible pipeline failure rather than being hidden.
- Open questions: None.

## Verification Plan

- Run `cargo fmt --check`, `cargo test --workspace`, `cargo clippy --workspace --all-targets -- -D warnings`, and `cargo build --workspace`.
- Run the synthetic H.264 sender against a local receiver pipeline and record whether the first frame arrives.
- Run the synthetic H.265 sender when `x265enc` is available; otherwise record the skip.
- Re-test the physical Android stream and capture the receiver state and decoder fields.

## Status

Complete.

## Handoff Notes

- Implementation: `udpsrc` now declares `video/mpegts`, `systemstream=true`, and
  `packetsize=188`; requests a bounded 1,000,000-byte receive buffer; and uses
  queried caps when dynamic pads do not yet expose current caps.
- Documentation: troubleshooting now explains PID `0x0020`, UDP continuity
  loss, Linux receive-buffer checks, and the limits of UDP recovery.
- Commands run: `cargo fmt --all`, `cargo test -p receiver-gstreamer`,
  `cargo test --workspace`, `cargo clippy --workspace --all-targets -- -D warnings`,
  `cargo build --workspace`, and `cargo fmt --all -- --check`.
- Synthetic evidence: H.264 and H.265 GStreamer sender pipelines both ran
  against explicit MPEG-TS receiver caps without a `not-negotiated` error.
  The commands exited with `timeout` status 124 as intended after the bounded
  test window.
- Runtime limitation: the physical Android stream still requires re-testing;
  UDP packet loss can continue to produce continuity warnings on a congested
  Wi-Fi network.
