# CamBridge testing strategy

This document defines how CamBridge is tested across the Android sender, the
native OBS receiver, and the shared wire contract. It is intentionally
decision-oriented. A larger test count is not treated as evidence unless the
tests exercise the branch, state transition, or boundary that can change the
user-visible result.

## Quality model

Every release review should answer five questions for each critical path:

1. What input or external state makes the decision change?
2. What action does the application take for each outcome?
3. What user-visible or wire-level result proves that outcome?
4. Which test runs that decision deterministically?
5. Which host, device, or integration condition is still required?

The review record should label evidence as one of:

- `verified`: the relevant test or runtime check ran and passed in the stated
  environment.
- `indirect`: a nearby unit or policy test passed, but the actual platform or
  process boundary was not exercised.
- `pending`: the required environment was unavailable or the decision has no
  test yet.

Line and method percentages are not release gates in this repository. The
quality gate is the decision trace matrix, state-transition coverage, failure
injection, protocol round trips, and runtime acceptance evidence below.

## Test layers

### Contract and pure logic

These tests run quickly and should cover all branches that can be represented
without Android, OBS, a camera, or a network process:

- generated protocol parity and JSON schema validation;
- video profile selection, bitrate intersection, geometry, orientation, and
  60 fps capability decisions;
- receiver endpoint validation and selection ordering;
- stream lifecycle state transitions and cleanup ownership;
- GStreamer sender and receiver construction, required factory availability,
  and transport configuration;
- native control, mailbox, decoder-path, renderer-path, diagnostics, and
  discovery metadata policies.

Pure tests must assert the decision and its reason, not only that a list or
object was returned.

### Platform-bound tests

Android instrumentation tests should exercise Compose semantics and lifecycle
behavior that JVM tests cannot observe. The minimum set is:

- first permission request and denial;
- permanent denial leading to Android app settings;
- returning from settings and refreshing permission state;
- setup controls showing a supported resolution while disabling only its
  unsupported frame rate;
- receiver selection, manual fallback, and start-button enablement.

The instrumentation APK must compile in CI. Execution requires a configured
API 35 emulator or physical device and is a separate evidence item.

### Process and runtime integration

The native fixture starts an isolated OBS process with a Cambridge-only scene
and profile. It must cover control probe, hello and acceptance, GStreamer RTP
decode, first-frame publication, recording output when enabled, sender stop,
session invalidation, and clean OBS shutdown.

The shared GStreamer integration test is mandatory when the pinned transport
runtime is available. It uses a local RTP proxy to inject deterministic loss,
delay, and recovery conditions:

- A: continuous delivery with no loss;
- B: one lost RTP datagram recovered by NACK/RTX without an additional PLI;
- C: unrecoverable loss requests a keyframe and resumes on a clean IDR;
- D: ten consecutive lost datagrams do not leave persistent corruption;
- E: bandwidth reduction drives GCC and the Android bitrate callback down;
- F: restored bandwidth drives GCC and the callback back toward the target.

The test exits with the CTest skip code when `rtpgccbwe` or the x264 test
encoder is unavailable. A release environment must install the GStreamer Rust
RTP plugin and run the test rather than treating that skip as transport
acceptance.

The Android emulator fixture adds the Camera2 and MediaCodec boundary, but it
does not prove a particular physical phone's camera modes or stabilization
quality. A physical acceptance run is required for those claims.

## Decision trace matrix

The following IDs are used in review reports and test names.

| ID | Decision | Required branch evidence |
| --- | --- | --- |
| PERM-01 | Camera permission is already granted at setup entry | Capability inspection starts and the setup action can proceed. |
| PERM-02 | First permission request is denied | Setup remains actionable and does not start a partial session. |
| PERM-03 | Permission is permanently denied | The primary action opens Android app settings and explains the recovery. |
| PERM-04 | Permission is granted after returning from settings | Permission state refreshes, capabilities are re-probed, and a pending start is attempted once. |
| PERM-05 | Permission disappears between capability inspection and camera start | The result is `CameraPermissionDenied`, not an OBS or video-quality error; resources are cleaned up. |
| VIDEO-01 | Camera and encoder both support a mode with an overlapping bitrate range | The mode is enabled and its effective bitrate range is shown. |
| VIDEO-02 | Camera rejects a mode | Only that mode is disabled with a camera reason. |
| VIDEO-03 | Encoder rejects a mode | Only that mode is disabled with the encoder reason. |
| VIDEO-04 | Encoder bitrate range does not overlap the product range | The mode is disabled with the overlap reason. |
| VIDEO-05 | One FPS is supported and its sibling FPS is not | The resolution stays enabled, only the unsupported FPS is disabled, and its reason is not shown on the resolution. |
| VIDEO-06 | Selected bitrate is outside the effective step range | Start is rejected before encoder preparation and the selected value is not persisted. |
| RX-01 | One receiver is discovered or manually probed | The endpoint is selected, persisted after success, and used for start. |
| RX-02 | Multiple receivers are discovered | Start remains blocked until an explicit receiver is selected. |
| RX-03 | Manual receiver probe fails | The previous known-good endpoint remains persisted and the failure is actionable. |
| RX-04 | Discovery is unavailable or returns duplicate identities | Manual addressing remains available and candidates are stable and deterministic. |
| SESSION-01 | Start is requested while idle | Capability, configuration, foreground, engine preparation, and engine start occur once in order. |
| SESSION-02 | Capability, transform, foreground, prepare, or engine start fails | The failure is classified, all owned resources are released, and the state is terminal and inspectable. |
| SESSION-03 | Stop is requested repeatedly | Stop is idempotent and does not start a new connection. |
| SESSION-04 | Encoder disconnects while streaming | The session fails as a network disconnect and cleanup runs once. |
| SESSION-05 | Bitrate changes while idle or streaming | Idle updates fail; active updates reach the engine and are diagnosed. |
| MEDIA-01 | Required GStreamer factories are unavailable | Start fails clearly and no fallback transport is selected. |
| MEDIA-02 | One RTP datagram is lost | RTCP NACK triggers RTX recovery without an additional keyframe request. |
| MEDIA-03 | Loss exceeds RTX history | RTCP PLI/FIR reaches the sender, MediaCodec receives a sync-frame request, and delivery resumes at the next IDR. |
| MEDIA-04 | Ten consecutive datagrams are lost | The receiver does not retain persistent corruption after a clean access unit. |
| MEDIA-05 | Available bandwidth drops | GCC publishes a lower estimate and the sender applies a bounded MediaCodec bitrate update. |
| MEDIA-06 | Available bandwidth recovers | GCC and the encoder bitrate return toward the configured target. |
| OBS-01 | Plugin is loaded by the target OBS ABI | OBS reaches startup complete with only Cambridge enabled and no new coredump. |
| OBS-02 | Native decoder and renderer path is selected | The selected path is explicit in diagnostics and frames are published. |
| OBS-03 | CPU fallback is selected | Software decode and rendering are explicit and changing frames are recorded. |
| OBS-04 | Sender stops or control connection closes | The receiver invalidates the active generation and leaves no stale session. |

## Why 60 fps may be blocked

60 fps is present in the product catalog for 1080p and 2K, and the shared
CamBridge contract permits up to 120 fps. There is no protocol-level rule that
blocks 60 fps.

The Android setup enables a mode only when all of these are true:

1. Camera2 advertises the requested output size and a compatible target FPS
   range, and its minimum frame duration can sustain that FPS.
2. An H.264 MediaCodec encoder advertises the exact size and FPS through
   `areSizeAndRateSupported`.
3. The encoder bitrate range overlaps the CamBridge catalog range after the
   one Mbps product step is applied.

Therefore 1080p30 can be enabled while 1080p60 is disabled. The meaningful
diagnostic is the resolved per-mode capability record, not the existence of
the 60 fps entry in the catalog. The Android logger records each mode as
`profileId:supported:reason` in the `video_capabilities_resolved` event.

For a physical phone review, retain the record for `1080p30`, `1080p60`,
`2k30`, and `2k60`, including camera support, encoder support, encoder name,
effective bitrate range, and reason. This distinguishes a Camera2 limit, an
encoder limit, and a bitrate intersection failure. A phone that advertises a
60 fps camera range can still reject the matching MediaCodec surface mode.

## Required commands and evidence

From the repository root:

```bash
python3 scripts/development/check-cambridge-stream-contract.py
./scripts/sender/android/prepare-gstreamer-android.sh
export GSTREAMER_ROOT_ANDROID="$PWD/build/gstreamer-android-1.24.13"
export JAVA_HOME=/path/to/jdk-17
./scripts/development/check-all.sh
JAVA_HOME=/path/to/jdk-17 ./scripts/development/check-all.sh
```

Android-only checks:

```bash
cd sender/android
../../scripts/sender/android/prepare-gstreamer-android.sh
export GSTREAMER_ROOT_ANDROID="$PWD/../../build/gstreamer-android-1.24.13"
JAVA_HOME=/path/to/jdk-17 ./gradlew \
  testDebugUnitTest lint assembleDebug compileDebugAndroidTestKotlin --console=plain
JAVA_HOME=/path/to/jdk-17 ./gradlew connectedDebugAndroidTest --console=plain
```

Native-only checks:

```bash
./scripts/receiver/linux/build-cambridge-obs-plugin.sh
ldd -r build/cambridge-obs-plugin/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so
GST_PLUGIN_PATH=/path/to/gst-plugin-rtp \
  ./build/cambridge-shared-tests/cambridge-obs-plugin-tests
```

Runtime fixture examples:

```bash
CAMBRIDGE_DECODER_MODE=cpu \
CAMBRIDGE_DURATION_SECONDS=5 \
  ./scripts/receiver/linux/test-cambridge-fixture.sh

CAMBRIDGE_DECODER_MODE=cpu \
CAMBRIDGE_ROTATION_DEGREES=90 \
CAMBRIDGE_DURATION_SECONDS=5 \
  ./scripts/receiver/linux/test-cambridge-fixture.sh
```

Each runtime run must retain the artifact directory, OBS log, fixture summary,
plugin hash, and any recording hashes. Review the log for startup completion,
session acceptance, decoder readiness, first frame, session invalidation, and
absence of decoder or RTP failures.

Linux bundle and installer tests are separate from the native fixture. The
variant declarations in `receiver/obs/cambridge-obs-source/buildspec.json`
drive exact SONAME validation for each matching build environment. Run the
metadata, exact selection, multiple-installation choice, and no-mutation
installer tests with:

```bash
python3 scripts/release/test-cambridge-linux-bundle.py
```

When a package is available, use its installer with `--dry-run` and an
explicit OBS path to inspect selection without changing the user's OBS
configuration. Native unit tests do not prove OBS plugin discovery, and an
isolated OBS process smoke test remains separate evidence when the target
platform environment is available.

## Component release metadata

The root `VERSION` file is a JSON manifest with independent Android sender and
OBS plugin versions. The iOS sender is explicitly deferred until its physical
validation gates pass. Validate the manifest, release tag mapping, and
generated iOS placeholder with:

```bash
python3 scripts/development/cambridge_component_versions.py --check
python3 scripts/development/test-component-versions.py
python3 scripts/development/generate-ios-version.py --check
```

Android and OBS release tags are `android-v<version>` and `obs-v<version>`.
Protocol compatibility remains controlled only by the v7 stream contract, not
by requiring the component marketing versions to be equal.

## Release acceptance criteria

A supported release is accepted only when the following evidence is retained:

- contract validation passes;
- Android JVM tests, lint, debug assembly, and instrumentation compilation pass
  with the pinned GStreamer Android SDK;
- the six-case GStreamer loss, RTX, keyframe, and adaptive bitrate integration
  test runs without a skip;
- native CTest passes and `ldd -r` reports no unresolved symbols or libraries;
- an isolated Cambridge-only OBS profile reaches startup complete without a new
  coredump;
- a runtime fixture publishes changing frames, validates the requested
  geometry and FPS, and invalidates the session after stop;
- CPU fallback is tested independently from automatic/native selection;
- DroidCam and existing OBS scenes are smoke-tested after the Cambridge-only
  profile is removed;
- a physical Android run confirms permission recovery, the resolved capability
  matrix, the selected FPS, and start/stop behavior on the target phone;
- all failures are classified in diagnostics and no acceptance claim relies on
  a test that was only compiled.

The physical Android and glass-to-glass criteria remain device evidence. No
claim about the Vivo's actual 60 fps capability should be made until its
resolved capability record is retained.

## Priority follow-ups

1. Add route-level instrumentation coverage for permanent denial, settings
   return, and the pending-start-on-resume path.
2. Add a local TCP test for Android control framing, including fragmented
   headers, fragmented payloads, timeout, malformed JSON, and oversized frames.
3. Add start/stop race, cancellation, foreground-start failure, and bitrate
   range tests to the session controller.
4. Add Android encoder-output conversion tests for Annex-B, AVC length-prefix,
   and AVC configuration records.
5. Add a Linux OBS process smoke job where the target OBS binary is available;
   CTest and `ldd -r` alone do not prove plugin discovery or startup behavior.
6. Add explicit test reporting for native CTest skips and platform-dependent
   decoder paths so a green job cannot hide an unexecuted acceptance branch.
