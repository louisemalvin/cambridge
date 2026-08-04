# Reliable cross-platform phone-to-virtual-camera streaming architecture

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Replace the fragile custom UDP and reverse-control connection stack with one
sender-initiated, cross-platform SRT path from a mobile video source to the
persistent Linux virtual camera. Prove it using a deterministic Android
emulator video-file camera through the real app and receiver into OBS.

## Context

- Detailed design: `docs/reliable-streaming-v2-plan.md`.
- Visual review: `docs/reliable-streaming-v2-plan.html`.
- Android and iOS are native adapters to one platform-neutral wire contract.
- Camera acquisition, lens work, and camera UI are irrelevant to this task.
- Android Emulator 37.1.11 supports `-camera-back videofile:<path>` and the
  `codex-phone-webcam-api35` AVD exists.
- GStreamer 1.28.5 provides `srtsrc`; `/dev/video10` and OBS are available.
- `PersistentVirtualCameraOutput` already owns stable standby/live v4l2 output.
- RootEncoder 2.8.0 already includes an SRT adapter, but upstream labels SRT
  beta, so compatibility and recovery are a mandatory first gate.
- The worktree contains unrelated in-progress edits. Preserve and reconcile
  them. Never reset or revert them.

## Decisions

- Use video-only H.264 in MPEG-TS over encrypted SRT for the baseline.
- Mobile is SRT caller; receiver is SRT listener with one active session.
- Use a per-session opaque stream ID and AES-256 passphrase. Redact secrets.
- Receiver owns output profile, bitrate, listener port, SRT latency, deadlines,
  retry grace, and output format. The sender uses negotiated values exactly.
- The receiver output profile remains fixed for process lifetime. The initial
  emulator reference is `1280x720` at `30 fps`.
- Preserve the persistent v4l2 writer across ingest disconnects and restarts.
- All normal control is sender-initiated HTTP v2. No subnet scanning,
  phone-hosted server, peer-address inference, or reverse camera activation.
- RootEncoder and a future iOS library are adapter choices, not protocol types.
- H.265, audio, internet traversal, adaptive bitrate, and camera improvements
  are deferred until H.264 reliability is proven.
- Keep v1 only until the Android emulator vertical slice passes, then delete
  custom UDP and reverse-control paths rather than maintaining two stacks.

## Acceptance Criteria

- Android emulator video-file camera reaches the receiver over encrypted SRT.
- The Rust receiver reaches decoded-frame state and publishes changing frames
  to `/dev/video10`.
- OBS displays `Mobile Webcam` and remains open across sender stop/restart.
- Wrong stream ID and wrong passphrase are rejected without live output.
- Controlled loss is recovered within the bounded SRT latency policy.
- A two-second interruption selects standby and resumes the same session within
  reconnect grace without recreating the receiver or OBS source.
- Grace expiry, sender death, pipeline failure, and repeated delete all clean up
  idempotently while the persistent virtual camera stays available.
- At least 20 lifecycle cycles and a 30-minute sustained run show bounded
  queues, stable memory, and no thread/task growth.
- Rust and Android fixture, unit, lint, build, and integration checks pass.
- The iOS adapter conforms to the same DTO and transport boundary. Runtime iOS
  support is claimed only after macOS build and integration evidence.
- Production code has no unexplained numeric literals or duplicated transport
  configuration.
- Production no longer depends on `mpegts-udp`, the project UDP module, subnet
  probing, or the phone control port after the replacement gate.

## Implementation Plan

1. Freeze HTTP/control v2 and typed SRT endpoint fixtures across Rust, Kotlin,
   and Swift models.
2. Centralize receiver-owned output, SRT, timing, retry, and security config.
3. Add a GStreamer `srtsrc` ingest boundary and prove host synthetic video,
   authentication rejection, reconnect, stats, and persistent output.
4. Implement sender-initiated `/v2` session create/status/delete with bearer
   auth, per-session credentials, deterministic expiry, and advertised host.
5. Replace Android `UdpStream` usage with a typed RootEncoder SRT adapter while
   preserving one stream, camera, preview, and lifecycle owner.
6. Generate a moving sample video, start the AVD with video-file camera, build
   and install the exact APK, and run emulator to receiver to v4l2 to OBS.
7. Add isolated network-namespace loss/reorder/outage tests, lifecycle loops,
   sustained-run checks, standby clearing, and diagnostics.
8. Replace reverse control with sender discovery or explicit origin, pairing,
   and sender-owned start/stop. Remove phone listening service and subnet scan.
9. Implement the iOS adapter under `IOSMediaEngine` and run file-source
   conformance on macOS before adding camera acquisition.
10. Delete UDP, dynamic media-port, reverse-control, stale scripts, fixtures,
    and docs. Refresh architecture, setup, testing, and troubleshooting docs.

## Task Contract

- Scope: SRT media v2, sender-initiated control v2, receiver session lifecycle,
  Android adapter, deterministic emulator E2E, persistent v4l2 output,
  reliability tests, iOS conformance boundary, removal of superseded connection
  code, and durable docs.
- Out of scope: camera acquisition changes, lens/stabilization/zoom work, audio,
  WAN/cloud/TURN, non-Linux virtual cameras, H.265 before baseline gates, and
  adaptive bitrate before fixed-bitrate reliability.
- Files or areas likely to change: `protocol/`, all receiver Rust crates and
  apps, Android control/session/media adapters and Gradle configuration, iOS
  control/media/session adapters, `scripts/`, `docs/`, tests, and fixtures.
- Interfaces or behavior contracts: typed transport endpoints; platform-neutral
  v2 DTOs; receiver-authoritative state; one active session; caller/listener
  SRT; persistent output independent of ingest; no secrets in logs.
- Risks and edge cases: RootEncoder SRT beta behavior, iOS dependency behavior,
  emulator host advertisement, SRT latency under loss, stale frames, OBS format
  churn, idempotent cleanup, and overlapping dirty worktree edits.
- Open questions: None.

## Verification Plan

- `cargo fmt --manifest-path desktop/Cargo.toml --all -- --check`
- `cargo test --manifest-path desktop/Cargo.toml --workspace`
- `cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings`
- `android/gradlew -p android test lint assembleDebug`
- `scripts/development/check-all.sh`
- New host SRT, Android emulator SRT, and isolated netns recovery scripts.
- Exact APK SHA-256, receiver logs, redacted session response, Android logcat,
  SRT stats, changing-frame hashes, v4l2 capture, and OBS moving-video evidence.
- Wrong-credential, clean, loss, reorder, two-second outage, grace expiry,
  20-cycle, sender-death, receiver-restart, and 30-minute sustained scenarios.
- Swift unit/build/integration evidence on macOS before iOS runtime claims.

## Status

The v2 protocol, receiver-owned SRT listener, encrypted GStreamer ingest,
persistent Linux output, Android SRT adapter, sender-initiated control, and
receiver mDNS discovery with manual fallback are implemented. Rust and Android
build, test, lint, clippy, host SRT, network-namespace, lifecycle,
sustained-stream, and Android emulator gates have passed. A physical Android
test reaches HTTP health, capabilities, session preparation, and RootEncoder,
but the SRT handshake currently times out. The phone routes receiver traffic
through Tailscale `tun0` with source `100.127.215.26`, so the remaining failure
is a host firewall/routing gate and is not yet a proven physical end-to-end
stream. OBS capture, a full 30-minute sustained run, and macOS/iOS runtime
evidence remain unclaimed.

## Handoff Notes

- Next exact step: realign around the physical Android SRT gate before adding
  more architecture work. The receiver can be started with
  `./desktop/target/release/mobile-webcam-desktop --advertise-host 192.168.1.149 --log-level debug`.
  The phone currently routes `192.168.1.149` through `tun0` with source
  `100.127.215.26`; allow UDP 5000 from that address in UFW or temporarily
  disable the Tailscale VPN, then repeat the physical test. No long-running
  test process is currently active.
- Files changed: protocol v2 and discovery fixtures/schema and DTOs; receiver
  Rust crates and apps; Android control, discovery, session, and RootEncoder
  SRT adapters; iOS DTO/discovery boundary; Linux SRT sender and receiver
  integration scripts; receiver and setup documentation.
- Commands run: `cargo fmt --manifest-path desktop/Cargo.toml --all -- --check`,
  `cargo test --manifest-path desktop/Cargo.toml --workspace`,
  `cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings`,
  `cargo build --manifest-path desktop/Cargo.toml --release -p receiver-cli -p receiver-desktop`,
  `JAVA_HOME=/home/ltanaka/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ./gradlew test lint assembleDebug`,
  `scripts/linux/test-srt-receiver.sh /dev/video10 55001 55000`,
  `scripts/linux/test-srt-netns.sh 55041 55040 /dev/video10`,
  `RECEIVER_BINARY=desktop/target/release/mobile-webcam-receiver scripts/linux/test-srt-lifecycle.sh 20 55011 55010 /dev/video10`,
  `SUSTAINED_SECONDS=60 RECEIVER_BINARY=desktop/target/release/mobile-webcam-receiver scripts/linux/test-srt-sustained.sh 55021 55020 /dev/video10`,
  and `scripts/android/test-emulator-srt.sh`.
- Verification evidence: all Rust workspace unit and protocol tests pass,
  along with formatting, clippy, and release builds. Android tests,
  lint, and debug assembly pass. The host SRT gate proves wrong stream ID and
  passphrase rejection, decoded frames, v4l2 output, reconnect, and
  idempotent cleanup. The network-namespace gate passes. Twenty lifecycle
  cycles pass with idempotent cleanup and bounded post-warm-up RSS. The
  60-second sustained run decodes 1,688 frames with 1,260 KiB RSS growth and
  zero thread growth. The emulator gate reaches receiving with 119 decoded
  frames and redacts secrets in logs.
- Remaining evidence: the local `/dev/video10` device is output-only and OBS
  was not running, so v4l2 capture and OBS moving-frame evidence are missing.
  The 30-minute run was intentionally stopped after 26,652 decoded frames at
  the user's request. No Android device was connected for `adb install -r`,
  and macOS/Xcode is unavailable for iOS build and integration evidence.
