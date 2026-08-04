# Solidify emulator-to-virtual-camera connected-idle streaming

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Prove one reliable vertical slice from a deterministic Android emulator video
through the real sender app and control/media protocols into a generic Linux
virtual-camera preview. Keep the phone connected in standby while media is off,
start media only for sustained virtual-camera demand, and stop it after the final
consumer closes without disconnecting the phone.

## Context

- The current dirty worktree is the implementation under review. It changes or
  deletes 173 tracked files and adds 31 untracked files relative to `main`.
- Committed `main` already contains demand-driven work in commits `56bc549`,
  `eddddb2`, `a95cbc8`, `2d31d5c`, and `4bd95bd`. The dirty worktree removes its
  sender-control protocol, desktop coordinator, Android control server, and
  focused lifecycle tests. Inspect and reconcile that code rather than blindly
  restoring or discarding either implementation.
- The dirty worktree's current HTTP v2 plus encrypted MPEG-TS/SRT media path is
  functional. A 2026-08-05 live run of the current APK on `emulator-5554`
  reached receiver state `receiving` with H.264, `avdec_h264`, 30 output FPS,
  and 299 decoded frames.
- Connection is currently coupled to streaming: selecting or restoring a
  receiver immediately creates a session, prepares `Camera2Source`, and starts
  SRT. There is no connected-standby domain state.
- Linux demand events are still detected, but the dirty desktop runtime only
  reacts to inactive demand by selecting standby. Active demand is not delivered
  to Android and does not start media.
- `/dev/video10` currently advertises only V4L2 output capability while the
  receiver is running. A blind ffmpeg capture fails with `Not a video capture
  device`, so generic consumer access is not working.
- The existing emulator harness can inject a generated video with
  `-camera-back videofile:<path>`. The test source does not need to be embedded
  in the APK.
- The installed AVD name is `codex-phone-webcam-api35`. It normally appears as
  `emulator-5554`, but automation must resolve and target the launched emulator
  explicitly because a physical Android device may also be connected.
- Preserve all unrelated user changes in the dirty worktree. Do not reset,
  revert, or commit broad unrelated files.

## Decisions

- Use the installed Android emulator as the sender. Physical Android camera and
  physical-device validation are not required for this task.
- Launch AVD `codex-phone-webcam-api35` for the E2E gate and direct every ADB
  command to its resolved emulator serial. Never use an unqualified `adb`
  command when another Android device is attached.
- Use one generated moving video as the emulator back camera and exercise the
  real Android application, not a host process impersonating Android media.
- Keep H.264, MPEG-TS, encrypted SRT, receiver-owned 1280x720 at 30 FPS output,
  and the existing v2 session negotiation for this baseline.
- Model receiver reachability/pairing, virtual-camera demand, and media session
  lifecycle as independent state dimensions.
- Keep normal network direction phone-to-receiver. Deliver receiver-authoritative
  demand changes through an authenticated, reconnectable phone-originated
  control subscription. Do not restore subnet scanning, peer inference, or a
  receiver connection to a phone-hosted listener.
- Give each demand-driven media start a generation identifier so duplicate or
  stale start/stop events cannot affect a newer generation.
- Keep the persistent virtual-camera producer attached in standby. Generic
  consumers must see one stable `Mobile Webcam` capture device across media
  start, stop, and restart.
- A blind generic V4L2 preview/capture is the consumer gate. OBS-specific work
  and automation are unnecessary.

## Acceptance Criteria

- Starting the receiver exposes `Mobile Webcam` as a V4L2 capture device that a
  generic consumer can enumerate and open while media is in standby.
- Launching the Android app with a configured receiver reaches an explicit
  connected-standby state without opening the video source, preparing the
  encoder, starting the foreground streaming service, or creating an SRT media
  session.
- Capability enumeration and short-lived probe opens do not start Android media.
- The first sustained consumer open produces one demand generation and exactly
  one Android media start. Additional consumers do not duplicate the start.
- The emulator's generated moving video reaches receiver state `receiving`, and
  a blind consumer reads at least two distinct decoded frame hashes from
  `Mobile Webcam`.
- Closing the final consumer selects black standby, stops and releases the
  Android video/encoder/foreground-service resources, and deletes receiver media
  state while leaving the Android app connected and ready for later demand.
- Reopening the consumer starts a new generation and displays moving frames
  without restarting the emulator app, receiver, or virtual-camera device.
- Duplicate stop, stale generation messages, sender death, receiver death, and
  reconnect produce bounded, idempotent cleanup and no frozen last-live frame.
- Android and Rust state-machine tests cover connected standby, first demand,
  duplicate demand, final release, stale generations, and reconnect cleanup.
- Changed production files comply with the project rule against unexplained
  literals and duplicated configuration.

## Implementation Plan

1. Inventory the committed demand-driven implementation and dirty SRT v2 rewrite;
   write a small ownership/state map before editing and preserve the working SRT
   media path.
2. Fix the persistent v4l2loopback producer/configuration so `/dev/video10`
   remains a stable capture device that generic consumers can open in standby
   and live modes.
3. Define receiver-authoritative demand state and generation semantics in the
   control contract, including an authenticated reconnectable subscription from
   Android to the receiver.
4. Add a testable desktop demand coordinator that converts debounced consumer
   transitions into generation-scoped start/stop signals without media side
   effects during discovery or capability enumeration.
5. Split Android connection ownership from media ownership. Connected standby
   keeps control alive; demand starts the existing v2 session and RootEncoder SRT
   engine; final release performs ordered media cleanup but preserves control.
6. Restore focused Rust and Kotlin lifecycle tests around the new state machine,
   stale generations, idempotency, and reconnect behavior.
7. Make the emulator E2E harness self-contained: generated video-file camera,
   receiver readiness, exact APK install, connected-standby assertion, blind
   V4L2 open, changing frame hashes, close/standby assertion, reopen/resume, and
   deterministic teardown.
8. Remove or correct documentation that claims demand-driven behavior not
   present in executable code. Keep documentation limited to the proven Android
   emulator and Linux generic-consumer slice.

## Task Contract

- Scope: Android connected-standby and media lifecycle; receiver demand
  coordination; v2 demand/control contract; existing H.264 SRT media path;
  persistent Linux virtual camera; emulator and blind-consumer integration test;
  focused documentation.
- Out of scope: physical camera acquisition, physical phones, OBS integration,
  iOS, H.265 proof, lens selection, zoom, stabilization, camera UI redesign,
  audio, recording, cloud/WAN traversal, Windows, and macOS.
- Files or areas likely to change: `protocol/`; Android connection/session/media
  ownership and tests; `receiver-core`, `receiver-control-http`,
  `receiver-platform-linux`, and desktop runtime/coordinator; emulator/Linux test
  scripts; lifecycle/testing documentation.
- Interfaces or behavior contracts: receiver-authoritative debounced demand;
  authenticated phone-originated control subscription; generation-scoped
  idempotent transitions; separate connected and media states; persistent fixed
  V4L2 capture identity; existing typed SRT session endpoint.
- Risks and edge cases: very dirty overlapping worktree; v4l2loopback
  `exclusive_caps` behavior; producer opens being mistaken for consumers;
  capability-probe opens; reconnect races; Android process/background limits;
  stale demand after reconnect; RootEncoder asynchronous success callbacks;
  cleanup ordering between encoder, foreground service, and receiver session.
- Open questions: N/A

## Verification Plan

- `cargo fmt --manifest-path desktop/Cargo.toml --all -- --check`
- `cargo test --manifest-path desktop/Cargo.toml --workspace`
- `cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings`
- `JAVA_HOME=/home/ltanaka/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ./android/gradlew -p android testDebugUnitTest lintDebug assembleDebug`
- Run the demand-monitor real-device check against `/dev/video10` and prove
  enumeration does not activate demand while sustained capture does.
- Run a self-contained emulator E2E gate using the generated video-file camera
  on AVD `codex-phone-webcam-api35` and the exact APK. Record APK SHA-256,
  resolved emulator serial, Android run/session identifiers,
  receiver state/decoded-frame count, and cleanup state without logging secrets.
- Use a generic command such as ffmpeg `framemd5`, GStreamer, or a minimal V4L2
  preview to open the enumerated device without receiver-specific knowledge.
  Require successful capture and at least two distinct frame hashes.
- Assert connected standby before open, receiving after open, standby plus camera
  release after close, and a fresh successful generation after reopen.
- Verify no unrelated iOS, lens, zoom, stabilization, or UI files changed.

## Status

Implemented and verified. Production work was committed incrementally from
`67fcb63` through `a3ac38f`; only this task artifact is uncommitted.

## Handoff Notes

- Initial checkout was `bc0aaae` with a clean worktree plus this untracked
  artifact; it did not match the dirty-worktree inventory in Context. No reset,
  restore, or unrelated file changes were made.
- Ownership after implementation: receiver-core owns generation semantics;
  receiver-control-http owns authenticated demand streaming; the Linux CLI
  owns the persistent V4L2 producer and demand monitor; Android connection
  coordination owns connected standby and demand/reconnect state; the stream
  session controller and RootEncoder adapter own media resources; the receiver
  service owns negotiated session state.
- The implementation adds generation-scoped v2 demand events, debounced
  V4L2 consumer detection, a persistent `Mobile Webcam` capture pipeline with
  black standby/live switching, ordered Android media release, repeatable
  media restart cooldown, stale-generation filtering, and an explicit-serial
  emulator gate. The persistent output no longer stops when a temporary clone
  is released.
- Rust verification passed: `cargo fmt --manifest-path desktop/Cargo.toml
  --all -- --check`, workspace tests, and workspace Clippy with `-D warnings`.
  Android verification passed:
  `JAVA_HOME=/home/ltanaka/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2
  ./android/gradlew -p android testDebugUnitTest lintDebug assembleDebug
  --console=plain`. The emulator script also passes `bash -n`.
- Final E2E command:
  `JAVA_HOME=/home/ltanaka/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2
  scripts/android/test-emulator-srt.sh`.
  It used AVD `codex-phone-webcam-api35`, resolved serial `emulator-5554`,
  and APK SHA-256
  `3a1ac4e1947bb6ff52142275516623f67a8014e61e9b587c61f4aab07782d00e`.
- E2E evidence: demand events were generation 0 inactive, generation 1
  active/inactive, and generation 2 active/inactive. Android logged connected
  standby before the first start, exactly two start requests and two stops.
  Sessions `0ecacd48-5724-4040-bad2-296d25d0752a` and
  `e17556d6-4e9e-4b8b-9986-0559a43e4b83` both reached receiver `Receiving`
  with `avdec_h264`, then returned to `Idle`; decoded-frame counts were 81 and
  66. First and reopened captures were each 331776000 bytes, standby was
  1843200 bytes with YUY2 black bytes `16,128,16,128`, and each live hash file
  contained 16 distinct hashes.
- The second generic streaming consumer returned status 240 with
  `Device or resource busy`, which is expected from this v4l2loopback
  configuration's single simultaneous mmap streaming owner. A second
  `v4l2-ctl --all` open succeeded, and no duplicate demand generation or media
  start occurred. The documented test accepts this driver limitation.
- Rust and Android state tests cover connected standby, first and duplicate
  demand, final release, stale generations, idempotent cleanup, demand-stream
  loss, receiver failure, and reconnect behavior. Generated E2E logs and raw
  captures remain local under `.artifacts/reliable-streaming-v2` and are not
  part of the commit; physical phones, OBS, and long-duration hardware tests
  remain out of scope.
