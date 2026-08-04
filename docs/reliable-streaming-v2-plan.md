# Reliable cross-platform streaming v2

Status: implemented v2 design. The receiver-owned SRT path, sender-initiated
control, Bonjour/NSD discovery with manual fallback, Android adapter, persistent
output, and host/emulator gates are in the repository. OBS capture and macOS/iOS
runtime evidence remain environment gates.

## Mission

Build one dependable path from a mobile video source to the Linux virtual
camera:

```text
Android or iOS video source
  -> native encoder
  -> H.264 in MPEG-TS
  -> encrypted SRT caller
  -> Rust/GStreamer SRT listener
  -> decode and normalize
  -> persistent v4l2loopback output
  -> OBS
```

The first proof uses a deterministic video file as the Android emulator's back
camera. Camera acquisition quality, physical lens selection, and camera UI are
not part of this task.

The final media and control contracts must not contain Android, Camera2,
RootEncoder, iOS, AVFoundation, or HaishinKit types. Android and iOS are native
adapters to the same wire contract.

## Definition of done

The task is complete when all of these are true:

- The Android emulator sends a visibly moving, deterministic sample video to
  the Rust receiver through SRT.
- The receiver writes live frames to the existing persistent
  `v4l2loopback` output, and OBS can display the `Mobile Webcam` source.
- Normal packet loss is recovered within the configured SRT latency budget.
- A short network interruption reconnects without restarting the receiver,
  recreating the virtual camera, or requiring OBS to reopen its source.
- A disconnected or failed sender makes the virtual camera return to standby
  instead of freezing the last live frame.
- A wrong session stream ID or passphrase cannot feed the active output.
- The normal connection direction is phone to receiver. The desktop does not
  scan subnets, call a phone-hosted server, infer a receiver address from a TCP
  peer, or depend on Android-specific background behavior. The sender browses
  the receiver's DNS-SD advertisement and retains manual origin entry as a
  fallback.
- The Android adapter and iOS adapter expose the same source-neutral media and
  session boundaries.
- The custom Android UDP fork, sender-control listener, reverse-control
  protocol, and UDP-only receiver path are removed. They are not retained as a
  permanent compatibility mode.
- Rust and Android automated checks pass. iOS code and tests pass on a macOS
  runner before the project claims runtime iOS support.

## Confirmed current facts

- The receiver already separates protocol, core state, HTTP control,
  GStreamer, Linux output, and app composition into Rust crates.
- `PersistentVirtualCameraOutput` already keeps `/dev/video10` alive, publishes
  standby frames, and accepts decoded live samples.
- The current media source is `srtsrc`, followed by MPEG-TS parse, demux,
  codec parse, decode, raw conversion, and bounded raw output.
- The current Android engine wraps RootEncoder behind `StreamEngine` and uses
  RootEncoder's SRT adapter.
- RootEncoder 2.8.0 is already present and includes `SrtStream`, MPEG-TS
  packetization, loss retransmission, stream IDs, configurable latency, and
  AES passphrases.
- GStreamer 1.28.5 on the development machine provides `srtsrc`, `tsdemux`,
  H.264 parse/decode, `appsink`, and `v4l2sink`.
- `/dev/video10` is a working `v4l2loopback` device named `Mobile Webcam`.
  Capture-consumer and OBS evidence depend on the host environment.
- Android SDK binaries exist under the SDK path in `android/local.properties`.
  Emulator 37.1.11 and the `codex-phone-webcam-api35` AVD are installed.
- Android Emulator supports `-camera-back videofile:<path>`, which gives the
  existing camera adapter a deterministic source without adding a second media
  input path.
- The worktree contains unrelated in-progress Android and receiver edits. The
  implementation must preserve them and reconcile overlapping files instead of
  resetting or reverting them.

## Architecture decision

Use SRT in caller/listener mode with MPEG-TS payloads.

- Mobile sender: SRT caller.
- Desktop receiver: SRT listener.
- Required codec: H.264.
- Deferred codec: H.265, enabled only after the H.264 reliability gate.
- Container: one video-only MPEG-TS program.
- Media encryption: per-session AES-256 SRT passphrase.
- Access binding: per-session opaque SRT stream ID checked by the listener.
- Connection scope: one selected sender and one active session.
- Reference video profile: receiver-owned `1280x720` at `30 fps` for the
  emulator gate. The output profile is configurable only at receiver startup
  and remains stable while a consumer has the virtual camera open.
- Reference SRT latency: one named receiver configuration value, initially
  `120 ms`. The receiver returns that value to the sender. It must not be
  duplicated as an Android default.
- Default ports: the receiver control port and one named SRT listener port are
  defined once in receiver core and exposed through capabilities and setup
  scripts.

SRT is selected because this product is one-way contribution streaming to a
known receiver, not a peer-to-peer call. SRT supplies selective retransmission,
bounded latency, jitter handling, connection state, encryption, and stream IDs
without adding ICE, SDP, DTLS, TURN, or a custom reliability layer.

### Alternatives

| Option | Decision | Reason |
| --- | --- | --- |
| Raw MPEG-TS over UDP | Reject | No retransmission, congestion response, peer authentication, or connection state. The current implementation has already required MTU and routing workarounds. |
| WebRTC with WHIP | Defer | Strong real-time behavior and a standard ingest protocol, but it adds ICE, SDP, DTLS, RTCP, candidate handling, and another GStreamer plugin or custom `webrtcbin` integration. It is a better fit if internet traversal or browser senders become a requirement. |
| RTSP over TCP | Reject | Simple interoperability, but TCP head-of-line blocking can turn packet loss into growing latency. |
| Custom QUIC or RTP protocol | Reject | It recreates session, recovery, congestion, security, and interoperability work already provided by SRT or WebRTC. |
| SRT with MPEG-TS | Select | Matches one-way mobile ingest, works with GStreamer, has Android and iOS implementation options, keeps the current codec/container boundary, and directly addresses packet loss and connection state. |

## Target topology

```text
┌──────────────────────────── mobile sender ──────────────────────────────┐
│                                                                         │
│  VideoFrameSource -> VideoEncoder -> MpegTsMuxer -> ReliableTransport    │
│       native            native          adapter          SRT caller      │
│                                                                         │
│  SessionClient ------------------------------------------------------┐   │
│     HTTP v2, bearer token, create/status/delete                     │   │
└─────────────────────────────────────────────────────────────────────┼───┘
                                                                      │
                                   outbound control and media          │
                                                                      v
┌──────────────────────────── Linux receiver ─────────────────────────────┐
│  Axum control API -> receiver-core session owner                        │
│                          |                                               │
│                          v                                               │
│  GStreamer srtsrc -> tsparse -> tsdemux -> h264parse -> decodebin       │
│                                                        |                 │
│                                                        v                 │
│                                    appsink / decoded-frame boundary      │
│                                                        |                 │
│                                                        v                 │
│                         PersistentVirtualCameraOutput                    │
│                         standby/live -> appsrc -> v4l2sink               │
└───────────────────────────────────────────────────────┼─────────────────┘
                                                        v
                                           /dev/video10 -> OBS
```

The persistent output pipeline and the ingest pipeline have separate
lifetimes. Starting, stopping, timing out, or rebuilding ingest must not close
the v4l2 writer. There is one writer for the lifetime of the receiver process.

## Ownership boundaries

### Shared wire contract

The wire contract owns:

- protocol version;
- control resource shapes and stable error codes;
- codec, container, transport, dimensions, frame rate, and bitrate;
- SRT listener host, port, mode, stream ID, latency, and key length;
- session ID, state, connect deadline, and reconnect grace;
- stable metrics names needed for diagnostics.

The wire contract does not own:

- camera APIs or camera identifiers;
- platform encoder names;
- RootEncoder, HaishinKit, AVFoundation, MediaCodec, or GStreamer objects;
- UI state;
- v4l2 device implementation details.

### Android

```text
Camera2Source supplied by Android Emulator video-file camera
  -> RootEncoder encoder and MPEG-TS muxer
  -> RootEncoder SrtStream adapter
  -> SRT session described by the receiver
```

`StreamEngine.start` accepts a typed negotiated transport endpoint. It does not
accept a host and bare numeric port. The SRT implementation applies the stream
ID, latency, AES key length, and passphrase through adapter APIs. Secrets never
appear in a logged URI.

RootEncoder SRT is currently documented upstream as beta. Treat its use as an
adapter choice, not as the architecture. The first milestone is a compatibility
and recovery spike. If the spike fails a required behavior, replace only the
Android SRT adapter with an official `libsrt` binding. Do not return to raw UDP
or change the receiver contract to match a library defect.

### iOS

```text
VideoFrameSource
  -> AVFoundation or deterministic file source
  -> VideoToolbox H.264 encoder
  -> MPEG-TS and SRT adapter
```

Use an iOS SRT implementation with Swift Package Manager support and active
maintenance. HaishinKit is the first spike candidate. Its adapter must pass the
same MPEG-TS/SRT contract fixtures as Android. If it cannot accept pre-encoded
frames or satisfy stream ID, passphrase, latency, reconnect, and video-only
requirements, bind official `libsrt` behind `IOSMediaEngine` instead.

No Kotlin Multiplatform or shared native frame pipeline is required. Shared
behavior is the protocol and conformance suite, not cross-language frame
objects.

### Rust receiver

| Area | Responsibility |
| --- | --- |
| `receiver-protocol` | Versioned v2 DTOs, schema fixtures, stable enums and errors |
| `receiver-core` | Configuration, one-session state machine, credentials, deadlines, recovery policy |
| `receiver-control-http` | Authenticated sender-initiated v2 routes and request mapping |
| `receiver-gstreamer` | `srtsrc`, stream-ID validation hook, MPEG-TS decode, SRT and frame metrics |
| `receiver-platform-linux` | Persistent configured v4l2loopback writer and standby/live switching |
| `receiver-cli` | Configuration and headless composition |
| `receiver-desktop` | UI composition, discovery advertisement, preview, and status only |

Core stays independent of Axum, GStreamer, Linux, Android, and iOS.

## Control plane v2

All normal requests originate from the sender. The receiver does not expose a
dependency on a phone-hosted server.

### Bootstrap and receiver origin

The receiver advertises `_mobile-webcam._tcp.local.` with its control port,
display name, protocol version, and authentication requirement. The sender
browses and resolves that service, probes the v2 health and capabilities
routes, and lets the user select it. Manual host, port, name, and token entry
populate the same origin model when multicast discovery is unavailable. Subnet
scanning, a phone-side TCP listener, and reverse camera activation are not part
of v2.

### Session API

Minimum resources:

| Method | Resource | Behavior |
| --- | --- | --- |
| `GET` | `/v2/health` | Process and protocol availability, no side effects |
| `GET` | `/v2/capabilities` | Stable output profile, supported codecs, transport, and one-session limit |
| `POST` | `/v2/sessions` | Authenticates sender, validates request, creates listener, returns negotiated media and SRT credentials |
| `GET` | `/v2/sessions/{sessionId}` | Receiver-authoritative session state and metrics |
| `DELETE` | `/v2/sessions/{sessionId}` | Idempotently stops ingest and returns output to standby |

Session creation returns `201 Created` and a `Location` header. Repeating delete
for the most recently stopped session succeeds. A second sender receives a
stable `receiver_busy` error while a session is active.

Representative response shape:

```json
{
  "protocolVersion": 2,
  "sessionId": "opaque-session-id",
  "connectDeadlineMs": 10000,
  "reconnectGraceMs": 30000,
  "video": {
    "codec": "h264",
    "container": "mpegts",
    "width": 1280,
    "height": 720,
    "fps": 30,
    "bitrateBps": 4000000
  },
  "transport": {
    "kind": "srt",
    "mode": "caller",
    "host": "10.0.2.2",
    "port": 5000,
    "streamId": "opaque-stream-id",
    "latencyMs": 120,
    "keyLengthBytes": 32,
    "passphrase": "per-session-secret"
  },
  "output": {
    "pixelFormat": "yuy2"
  }
}
```

The example values are protocol fixtures, not extra production constants.
Receiver configuration is the source for profile, bitrate ceiling, port,
deadlines, latency, key length, and output format. The sender uses the returned
values exactly.

### Error contract

Return RFC 9457-style JSON problem details with stable codes:

- `unsupported_protocol_version`
- `unauthorized`
- `receiver_busy`
- `unsupported_codec`
- `unsupported_profile`
- `invalid_session`
- `session_expired`
- `transport_unavailable`
- `output_unavailable`

Human-readable detail may change. Tests and clients branch only on code and
HTTP status.

## Session state machines

### Receiver

```text
idle
  -> allocating
  -> listening
  -> connected
  -> receiving
  -> reconnecting -> connected -> receiving
  -> stopping
  -> idle

allocating/listening/connected/receiving/reconnecting
  -> failed -> idle after cleanup

listening past connect deadline -> expired -> idle
reconnecting past grace period -> expired -> idle
```

Rules:

- Only the core session owner changes state.
- `connected` requires an accepted SRT caller.
- `receiving` requires a decoded output frame, not only socket activity.
- `reconnecting` keeps the same session ID, stream ID, passphrase, ingest
  pipeline, and virtual camera writer.
- Leaving `receiving` clears the live sample and selects standby immediately.
- Expiry releases ingest resources but not the persistent virtual camera.
- Delete is safe from every non-idle state.

### Sender

```text
idle
  -> creating_session
  -> preparing_media
  -> connecting
  -> streaming
  -> reconnecting -> connecting -> streaming
  -> stopping
  -> idle

creating_session/preparing_media/connecting/streaming/reconnecting
  -> failed -> stopping -> idle
```

The sender does not report itself as streaming until RootEncoder or the iOS
adapter reports a connected SRT transport. Receiver status remains authoritative
for whether frames are decoded and available to OBS.

## Media pipeline contract

```text
srtsrc
  -> tsparse
  -> tsdemux
  -> h264parse
  -> decodebin
  -> videoconvert
  -> videoscale
  -> videorate
  -> fixed receiver output caps
  -> bounded downstream-leaky raw queue
  -> appsink
  -> PersistentVirtualCameraOutput
```

- There is no leaky queue before decode.
- SRT owns packet ordering and retransmission. Application code does not add
  custom packet sequence numbers, ACKs, retry frames, or datagram sizing.
- MPEG-TS must include PAT and PMT at stream start and on recovery-friendly
  intervals.
- The H.264 encoder repeats SPS/PPS at keyframes when supported.
- The reference maximum keyframe interval is receiver-owned and sent as part of
  negotiated video configuration.
- Raw branches use bounded latest-frame behavior. Compressed data is not
  dropped as a latency strategy.
- The output profile stays stable for the lifetime of the receiver process.
- H.265 remains disabled until H.264 passes clean, loss, reconnect, and OBS
  gates.

## Reliability policy

### SRT settings

- Receiver listener and sender caller use the same negotiated latency.
- Too-late packet drop is enabled so recovery remains bounded by the latency
  budget.
- The listener keeps listening during the reconnect grace period.
- Stream ID is validated before accepting a caller.
- AES-256 passphrase is generated with a cryptographically secure random source
  for each session and never persisted.
- Secrets are redacted in structured logs, diagnostics, URLs, exceptions, and
  screenshots.
- The sender uses bounded exponential reconnect backoff with jitter. Delay,
  maximum attempts, and grace period are named configuration values.
- A reconnect requests an encoder keyframe and makes MPEG-TS program metadata
  available again.

### Backpressure

- Encoder or transport queues have explicit capacity.
- Sustained queue pressure emits a typed diagnostic event.
- The initial vertical slice keeps a fixed negotiated bitrate.
- Adaptive bitrate is a later isolated controller driven by transport metrics.
  It must not be mixed into the transport replacement before baseline
  reliability is proven.

### Failure behavior

| Failure | Required result |
| --- | --- |
| Wrong stream ID | Listener rejects caller, output remains standby |
| Wrong passphrase | SRT handshake fails, output remains standby |
| No sender by connect deadline | Session expires and ingest resources close |
| Packet loss within latency budget | SRT retransmits and decoded output continues |
| Short path loss | Session enters reconnecting, output shows standby, same session may resume |
| Grace period exceeded | Session expires, sender creates a new session |
| Decoder or pipeline error | Session fails, diagnostics preserve cause, output shows standby |
| Sender stop | DELETE and SRT shutdown are both tolerated in either order |
| Receiver stop | HTTP and SRT stop, output pipeline closes once during process shutdown |

## Observability contract

Every lifecycle event includes a run ID and session ID when available. Secrets
and full endpoint URLs are forbidden.

Required receiver events:

- `session_created`
- `srt_listening`
- `srt_caller_accepted`
- `srt_caller_rejected`
- `srt_disconnected`
- `first_transport_bytes`
- `first_decoded_frame`
- `output_live`
- `output_standby`
- `session_reconnecting`
- `session_expired`
- `session_stopped`
- `pipeline_failed`

Required metrics:

- bytes and packets received;
- lost, retransmitted, and dropped packets where exposed by SRT;
- SRT RTT and negotiated latency;
- first-byte and first-frame times;
- decoded frame count and current output frame rate;
- output queue depth and pressure events;
- reconnect count and current state.

Status responses use nullable metrics when a platform or plugin cannot supply a
value. Missing data is not represented as zero.

## Implementation plan and gates

The implementation follows these milestones in order. A failed gate is diagnosed before
starting the next milestone.

### Milestone 0: freeze the contract - completed

1. Run `work-context` with the task artifact and inspect the dirty worktree.
2. Add an ADR selecting SRT/MPEG-TS and document the sender-initiated boundary.
3. Add `protocol/control-v2.schema.json`, examples, Rust DTOs, Kotlin DTOs, and
   cross-language fixture tests.
4. Introduce typed `TransportEndpoint`, `SrtEndpoint`, `OutputProfile`, and
   session-state types. Do not thread more strings or primitive port values
   through media layers.
5. Put all receiver-owned defaults in one receiver-core configuration. Derive
   CLI defaults, capability responses, session responses, scripts, and docs
   from it where practical.

Gate: Rust and Android parse the same v2 fixtures and reject unknown required
versions, malformed endpoints, invalid SRT key lengths, invalid passphrases,
and unsupported codec/profile requests.

### Milestone 1: SRT transport spike - completed

1. Add an `srtsrc` pipeline source behind a receiver media-source boundary.
2. Run a host GStreamer test source through `mpegtsmux ! srtsink` into the Rust
   receiver.
3. Validate stream ID rejection, passphrase rejection, first frame, clean stop,
   listener reuse, and reconnect during grace.
4. Read SRT stats and map available fields into receiver diagnostics.
5. Keep the existing persistent v4l2 output open throughout all tests.

Gate: a synthetic moving H.264 source reaches `/dev/video10`, a disconnect
switches to standby, and a reconnect resumes live frames without reopening the
device.

### Milestone 2: sender-initiated control - completed

1. Implement `/v2` routes using one receiver-owned session service.
2. Generate per-session credentials and construct the SRT listener before
   returning `201 Created`.
3. Add optional bearer authentication and explicit advertised-host configuration.
4. Make session deletion idempotent and session expiry deterministic.
5. Add explicit advertised-host configuration. For the Android emulator use
   `10.0.2.2`; never infer a usable media address from the TCP peer.

Gate: a control contract test creates a session, observes listening, rejects a
second session, reports receiver-derived state, deletes twice safely, and leaves
the persistent output in standby.

### Milestone 3: Android SRT adapter - completed

1. Add the RootEncoder SRT artifact once in the version catalog and remove
   duplicate RootEncoder declarations.
2. Implement `RootEncoderSrtStreamEngine` or convert the current adapter so
   `StreamEngine` receives a typed SRT endpoint.
3. Configure video-only H.264, stream ID, negotiated latency, per-session
   passphrase, AES-256, retry policy, and keyframe request through the adapter.
4. Keep one RootEncoder stream, one camera source, one preview surface, and one
   lifecycle mutex.
5. Leave all physical camera and lens work untouched.
6. Map SRT connection, disconnect, congestion, retry, RTT, loss, and frame
   counters into typed engine events without leaking RootEncoder types upward.

Gate: an Android unit test proves endpoint mapping and secret redaction. A
RootEncoder-to-GStreamer compatibility run proves H.264 video-only ingest,
encrypted handshake, stop, and reconnect.

### Milestone 4: deterministic emulator vertical slice - completed with v4l2 capture pending

1. Add a script that generates a small moving sample camera video with a frame
   counter using FFmpeg. Generated evidence belongs under a gitignored
   artifacts directory, not in source control.
2. Resolve Android SDK tools from `android/local.properties` or standard SDK
   environment variables.
3. Start `codex-phone-webcam-api35` with
   `-camera-back videofile:<absolute-sample-path>`. Do not wipe the AVD.
4. Build and install the exact debug APK, grant camera permission, and configure
   the receiver origin through a debug-only bootstrap or the normal diagnostic
   endpoint entry.
5. Start the receiver with `--advertise-host 10.0.2.2` and the reference output
   profile.
6. Start streaming and record the exact APK hash, session ID, state transitions,
   decoder, SRT stats, frame count, and v4l2 capture.
7. When a capture consumer is available, open OBS, add `Mobile Webcam` as a
   Video Capture Device, and capture evidence that the frame counter moves.

Gate evidence currently reproduces sample video from the emulator through the
real app and receiver into decoded frames. OBS and v4l2 capture require an open
consumer on the host.

### Milestone 5: reliability and lifecycle hardening - partially verified

1. Add unit tests for every sender and receiver state transition.
2. Add an isolated Linux network-namespace test with `tc netem`. The script
   owns a named veth pair, installs cleanup traps, and never alters a user's
   default interface or root qdisc.
3. Run clean network, controlled loss, reordering, short outage, and reconnect
   cases.
4. Exercise repeated create/start/stop/delete cycles while OBS remains open.
5. Prove stale frames are cleared on every disconnect and failure path.
6. Run a sustained session and check bounded queues, stable memory, no task or
   thread growth, and no repeated pipeline creation.

Gate status: host authentication, reconnect, network-namespace, lifecycle,
and 60-second sustained checks pass. The full 30-minute run, controlled-loss
metrics, and OBS capture evidence remain environment gates.

### Milestone 6: automatic receiver discovery with manual fallback - completed

1. Give the receiver one stable `_mobile-webcam._tcp.local.` DNS-SD
   advertisement with the HTTP control port and non-secret TXT metadata.
2. Add Android NSD and iOS Bonjour adapters behind platform-neutral discovery
   boundaries, with lifecycle cleanup and unsupported-version filtering.
3. Keep manual receiver-origin entry as the explicit fallback for restricted
   multicast networks and deterministic emulator tests.
4. Remove desktop subnet scanning, sender advertisements, the Android sender
   control server, reverse start/stop requests, and peer-address inference.
5. Keep sender-controlled start and stop as the portable behavior.

Gate: Android selects a discovered receiver when available, can fall back to
manual origin entry, creates and controls its own session, and has no listening
network service.

### Milestone 7: iOS conformance - unverified on this host

1. Implement the same v2 control DTOs and typed SRT endpoint under the existing
   `IOSMediaEngine` boundary.
2. Prove deterministic file-source H.264/MPEG-TS/SRT ingest first.
3. Add the camera source only after the file-source transport passes.
4. Run Swift unit tests and simulator/device integration on macOS.
5. Record the selected iOS SRT library, version, license, codec behavior,
   stream-ID mapping, passphrase mapping, latency mapping, and retry behavior.

Gate: the iOS adapter sends the shared fixture stream to the same unmodified
Rust receiver. If macOS hardware is unavailable, record the gate as unverified
and do not claim iOS runtime support.

### Milestone 8: remove superseded media and control paths - completed

1. The `android/rootencoder-udp` module and its Gradle wiring are deleted.
2. UDP packet sizing, UDP source construction, dynamic media-port range,
   UDP-only scripts, and stale fixtures are deleted.
3. Sender-control listeners and the obsolete reverse-control discovery client
   are deleted; the receiver advertisement is owned by `receiver-discovery`.
4. Architecture, setup, troubleshooting, security, and testing docs are
   refreshed.
5. Only v2 protocol fixtures required by supported adapters remain.
6. Changed production files are inspected for unexplained literals and
   duplicate configuration values.

Gate: production has no dependency on `mpegts-udp`, `UdpStream`, the project
owned UDP module, sender subnet probing, or the phone control port.

## Acceptance matrix

| Scenario | Required evidence |
| --- | --- |
| Host synthetic source | Moving H.264 pattern reaches live output and then standby |
| Android emulator source | Video-file camera reaches receiver `receiving` and produces changing v4l2 frames |
| OBS consumer | OBS displays `Mobile Webcam` while remaining open across sender stop/restart |
| Clean session | First decoded frame within the named startup budget, no pipeline error, no unbounded queue |
| Wrong stream ID | Caller rejected, no decoded frame, no stale live output |
| Wrong passphrase | Handshake rejected, secret absent from logs |
| One percent packet loss | Session remains live, retransmission metrics increase, output frame counter progresses |
| Three percent packet loss | No crash or unbounded latency; any unrecoverable degradation is explicit and bounded |
| Two-second outage | Output enters standby and same session resumes within reconnect grace |
| Grace expiry | Old session expires, resources release, new session can start |
| Repeated lifecycle | At least 20 start/stop cycles with one receiver process and one OBS source |
| Sustained run | At least 30 minutes, bounded memory and queues, no thread/task growth, moving output |
| Receiver restart | Sender fails clearly and can create a fresh session after receiver returns |
| Sender process death | Receiver returns to standby and expires session without manual cleanup |
| Protocol compatibility | Rust, Kotlin, and Swift fixtures agree on v2 shapes and stable enum values |

Numerical startup, retry, latency, queue, and duration budgets must be named
configuration or test constants with rationale at their definitions. The values
in this plan are initial reference targets, not permission to scatter literals.

## Verification commands and evidence

Project scripts keep the final command names stable. The
expected verification set includes:

```bash
cargo fmt --manifest-path desktop/Cargo.toml --all -- --check
cargo test --manifest-path desktop/Cargo.toml --workspace
cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings
android/gradlew -p android test lint assembleDebug
scripts/linux/test-srt-receiver.sh /dev/video10 55001 55000
scripts/linux/test-srt-netns.sh 55041 55040 /dev/video10
scripts/linux/test-srt-lifecycle.sh 20 55011 55010 /dev/video10
scripts/android/test-emulator-srt.sh
```

Store bulky evidence under a gitignored `.artifacts/reliable-streaming-v2/`
directory:

- receiver structured log;
- filtered Android logcat;
- exact APK SHA-256;
- session response with secrets redacted;
- SRT stats snapshots;
- v4l2 frame sample and changing-frame hash report;
- OBS screenshot or recording showing the moving frame counter;
- network impairment parameters and cleanup result;
- sustained-run memory, thread, queue, and frame-count samples.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| RootEncoder SRT interoperability defect | Compatibility spike before refactor. Keep architecture behind `ReliableTransport`; use official `libsrt` binding if required. |
| iOS library behavior differs | Conformance fixtures and file-source spike before camera work. Keep wire contract library-neutral. |
| SRT retransmission increases latency under heavy loss | Fixed latency budget, too-late packet drop, bounded queues, explicit degraded state, measured impairment tests. |
| HTTP exposes session secret on an untrusted LAN | Trusted-LAN scope only for v2 initial release. Bearer authentication and SRT encryption are still required. Add HTTPS with receiver certificate pinning before untrusted-network support. |
| Emulator networking advertises an unusable host | Explicit `--advertise-host 10.0.2.2`; never infer media host from peer address. |
| OBS sees format churn | Receiver output profile is fixed for process lifetime and the persistent writer never restarts per session. |
| Existing dirty work overlaps transport files | Preserve user changes, inspect diffs before editing, integrate in coherent commits, never reset the worktree. |
| Removing reverse control changes demand-driven behavior | Sender-controlled start is the portable v2 contract. Persistent standby keeps OBS usable without requiring the desktop to wake a phone. |

## Out of scope

- Improving camera capture, lens selection, stabilization, zoom, or preview UI.
- Audio.
- Internet traversal, cloud relay, TURN, or operation across unrelated NATs.
- Windows or macOS virtual-camera output.
- Adaptive bitrate before fixed-bitrate SRT reliability is proven.
- H.265 before H.264 passes all required gates.
- Claiming secure operation on an untrusted network while control uses plain
  HTTP.

## Implementation handoff rules

- Treat `.tasks/2026-08-04-reliable-cross-platform-phone-to-virtual-camera-streaming-architecture.md`
  as the execution entry point and this file as the detailed design.
- Run `work-context` and `task-ready` at the required workflow boundaries.
- Do not work on camera acquisition. The emulator video-file camera is the test
  input.
- Keep milestones separately verifiable and commit coherent milestones with
  Conventional Commit messages.
- Keep the v2 path as the only runtime connection stack. Superseded paths are
  deleted once their replacement gate is recorded.
- Stop and report a real blocker only after documenting the exact failed gate,
  logs, attempted alternatives, and the next bounded experiment.
- Do not mark the task complete with only unit tests or a synthetic host sender.
  Android emulator to OBS evidence is mandatory.
- Do not claim iOS runtime support without macOS build and integration evidence.

## Primary references

- SRT project and platform support: <https://github.com/Haivision/srt>
- SRT protocol: <https://haivision.github.io/srt-rfc/draft-sharabayko-srt.html>
- GStreamer `srtsrc`: <https://gstreamer.freedesktop.org/documentation/srt/srtsrc.html>
- RootEncoder: <https://github.com/pedroSG94/RootEncoder>
- HaishinKit Swift: <https://github.com/HaishinKit/HaishinKit.swift>
- Android Emulator camera file option:
  <https://developer.android.com/studio/run/emulator-commandline>
