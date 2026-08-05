# Remove manual receiver host lookup

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Run the receiver on a normal Linux host without manually looking up or passing
its LAN IP. Automatic discovery and the control connection must provide a
reachable SRT media host, while explicit host configuration remains available
for emulator, NAT, and multi-homed overrides.

## Context

- `receiver-discovery` already advertises `_mobile-webcam._tcp.local.` with
  automatic addresses.
- `ReceiverConfig::advertised_host` and both receiver CLIs currently default to
  `127.0.0.1`; `ReceiverService` copies it into the v2 SRT response.
- Android uses the returned SRT host, so control discovery can succeed while
  media still targets loopback. The emulator gate currently passes
  `--advertise-host 10.0.2.2` as a workaround.
- Preserve the completed connected-standby streaming task and its commits.

## Decisions

- Make the advertised host an optional explicit override rather than a required
  normal-path setting.
- When no override is supplied, derive the media host from the authenticated
  session creation request's reachable control origin. This avoids selecting a
  wrong interface on VPN or multi-homed hosts.
- Reject an absent or unusable origin with an actionable error; never fall back
  to loopback for a network session.
- Keep `--advertise-host` for the Android emulator (`10.0.2.2`), NAT, and
  deployments that intentionally publish a different media address.

## Acceptance Criteria

- Starting either receiver binary with no `--advertise-host` does not require
  the operator to determine a LAN IP.
- A discovered or manually configured control origin produces an SRT response
  whose host is the reachable origin host unless an explicit override is set.
- The default path never returns `127.0.0.1` as a remote phone's SRT host.
- Explicit advertised-host override behavior remains covered and works for the
  emulator gate.
- The emulator harness no longer needs a receiver advertised-host override;
  it still uses the deterministic emulator gateway for its control origin.
- Rust and Android tests cover host derivation, override precedence, invalid
  origin handling, and the existing media lifecycle.
- Setup and troubleshooting documentation describe automatic host selection and
  reserve manual host entry for genuine network exceptions.
- Changed production files contain no unexplained numeric literals or duplicated
  network configuration.

## Implementation Plan

1. Add focused protocol/control tests reproducing loopback and defining host
   extraction and override precedence.
2. Thread the request origin into session creation, make the receiver override
   optional, and remove loopback as an implicit remote fallback.
3. Update CLI defaults, the emulator harness, setup/troubleshooting docs, and
   diagnostics without changing the connected-standby media contract.
4. Run Rust checks, Android checks, and the emulator gate, then commit code,
   tests/docs, and this artifact as separate coherent commits.

## Task Contract

- Scope: receiver configuration, HTTP session-origin propagation, SRT endpoint
  host selection, CLI/harness docs and tests.
- Out of scope: transport redesign, subnet scanning, phone-hosted listeners,
  NAT traversal, and changes to demand-driven media lifecycle.
- Files or areas likely to change: `receiver-core`, `receiver-control-http`,
  receiver CLI/desktop CLI, Android control DTO tests if required, emulator
  script, setup/troubleshooting docs.
- Interfaces or behavior contracts: explicit override wins; otherwise the
  session response uses the reachable control origin host and SRT port remains
  receiver-owned.
- Risks and edge cases: IPv4/IPv6 host headers, host plus port parsing, reverse
  proxies, malformed origins, and direct service callers without HTTP context.
- Open questions: N/A

## Verification Plan

- `cargo fmt --manifest-path desktop/Cargo.toml --all -- --check`
- `cargo test --manifest-path desktop/Cargo.toml --workspace`
- `cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings`
- `JAVA_HOME=... ./android/gradlew -p android testDebugUnitTest lintDebug assembleDebug --console=plain`
- `bash -n scripts/android/test-emulator-srt.sh`
- Run the emulator gate with no receiver `--advertise-host` argument and record
  its serial, sessions, receiver state, and changing V4L2 hashes.
- Verify default and explicit-host session responses through focused tests.

## Status

Implemented and verified. Code, gates, and documentation were committed in
`6ee1425` and `93d87a4`; this artifact update remains to be committed.

## Handoff Notes

- Next exact step: no further work is required for this follow-up; the final
  implementation and handoff are committed.
- Files changed: receiver configuration and service, HTTP route extraction and
  tests, both receiver CLIs, emulator and synthetic gates, README, setup,
  troubleshooting, and lifecycle-plan documentation.
- Commands run: `task-ready`, `work-context`, Rust focused and workspace
  format/tests/Clippy, Android unit tests/lint/debug assembly, shell syntax
  checks, and `scripts/android/test-emulator-srt.sh` without a receiver
  `--advertise-host` argument.
- Errors encountered: the first no-override E2E attempt reached receiving on
  generation 1 but hit the known asynchronous RootEncoder/SRT reopen failure;
  the immediate rerun passed both generations. No host-resolution failure was
  observed.
- Verification evidence: final E2E used AVD `codex-phone-webcam-api35`, serial
  `emulator-5554`, and APK SHA-256
  `49f2644e9014eefdd35548ad39280de906f8a93e6fadd94e1b99fbf01d05b8f4`.
  Android connected to SRT `10.0.2.2:55030` with no receiver host override.
  Sessions `0132d015-a7c7-41f3-a90e-5685648955e7` and
  `7256a611-951d-4d0e-b55d-46fcabf77f37` reached `Receiving`; generations 1
  and 2 completed and returned to `Idle`. Both raw captures were 331776000
  bytes, each hash set had 17 distinct hashes, and standby began with YUY2
  black bytes `16,128,16,128`. The second consumer returned the expected
  v4l2loopback busy status without duplicating media demand.
