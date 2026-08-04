# Testing

## Automated checks

Run the Rust checks from the repository root:

```bash
cargo fmt --manifest-path desktop/Cargo.toml --all -- --check
cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings
cargo test --manifest-path desktop/Cargo.toml --workspace
cargo build --manifest-path desktop/Cargo.toml --workspace
```

When the Android toolchain is installed, run:

```bash
./android/gradlew -p android test lint assembleDebug
```

Rust tests cover JSON schema fixtures, protocol version and codec rejection,
codec negotiation, output policy, authenticated v2 HTTP routes, session
conflicts, timeout cleanup, bounded pipeline construction, SRT stream identity,
H.265 parser selection, and Linux device inspection. Kotlin tests cover shared fixture decoding,
negotiation, session cleanup behavior, profile-driven preview geometry,
orientation mapping, zoom clamping/reset, and RootEncoder output-dimension
mapping. The Android instrumentation suite also checks that the Material 3
zoom control exposes its slider and reset action, receiver-origin persistence
can be forgotten, and the coordinator clears its configured receiver state.
The presentation mapper tests cover waiting, streaming, failure
diagnostics, and camera-control projection without exposing domain types to
Compose. The preview viewport tests cover fitted landscape and portrait
geometry.

The MPEG-TS compatibility checks assert the parser branches and SRT source
properties. The iOS adapter has source-level unit tests for configuration,
typed SRT destinations, and the non-functional media boundary; it does not
claim camera hardware or a completed media pipeline.

## Synthetic SRT receiver gate

Run the host SRT gate against a configured virtual camera:

```bash
scripts/linux/test-srt-receiver.sh /dev/video10 55001 55000
scripts/linux/test-srt-netns.sh 55041 55040 /dev/video10
scripts/linux/test-srt-lifecycle.sh 20 55011 55010 /dev/video10
SUSTAINED_SECONDS=1800 scripts/linux/test-srt-sustained.sh 55021 55020 /dev/video10
```

The gate creates a moving H.264 pattern, packages it in MPEG-TS, and sends it
to the receiver's encrypted SRT endpoint. It checks wrong stream IDs and
passphrases, decoded frames, reconnect, standby, idempotent cleanup, bounded
RSS, and persistent output.

## Android emulator SRT gate

The emulator harness builds and installs the exact APK, uses the emulator's
video-file camera, passes the manual receiver-origin fallback through the activity
launcher, and waits for receiver `receiving` state:

```bash
scripts/android/test-emulator-srt.sh
```

Set `REQUIRE_V4L2_CAPTURE=1` when OBS is open and must be part of the gate.

## Virtual camera

Validate the device with:

```bash
scripts/linux/inspect-video-devices.sh
scripts/linux/test-virtual-camera.sh
```

The demand-driven loopback test validates the private client-usage event and
the persistent standby producer without scanning processes:

```bash
scripts/linux/test-demand-driven-webcam.sh /dev/video10
```

It checks side-effect-free capability enumeration, sustained capture, a second
consumer where the driver permits it, final release, and abrupt consumer death.
The monitor must report `Active` only during sustained capture and `Inactive`
after the final consumer leaves. The persistent producer remains attached in
both states.

The test script automatically selects the first v4l2loopback device. Pass an
explicit device path only when testing a non-default loopback configuration.

Open the device in OBS, Firefox, Chromium, or a browser after the persistent
desktop producer attaches. In OBS, add `Video Capture Device (V4L2)` and select
`Mobile Webcam`. Capability enumeration alone must leave the phone camera off;
starting preview or capture should start it, and releasing the final consumer
should return the output to black standby.

With OBS open and the Mobile Webcam source selected, run:

```bash
scripts/linux/test-obs-virtual-camera.sh /dev/video10
```

The desktop application can be used instead of the CLI:

```bash
mobile-webcam-desktop
```

It is the single receiver process for the control API, SRT media, decoded
preview, and v4l2loopback output. Do not start both receiver binaries on the
same ports and virtual-camera device.

During an Android lifecycle check, record the v2 `sessionId` and verify that a
repeated DELETE is idempotent, a sender restart can create a fresh session, and
killing the receiver leaves the sender with a bounded control failure.

## End-to-end matrix

Run each applicable row on a physical mobile sender and configured Linux host:

| Sender | Network | Receiver | Consumer |
| --- | --- | --- | --- |
| Android H.264 | Wi-Fi | Arch | OBS |
| Android H.264 | Wi-Fi | Ubuntu | OBS |
| Android H.264 | USB tethering | Arch | OBS |
| Android H.264 | USB tethering | Ubuntu | OBS |
| Android H.265 | Wi-Fi | Linux | OBS |
| Android H.265 | USB tethering | Linux | OBS |
| Android H.264 | Wi-Fi or USB | Linux | Browser |

The iOS skeleton is not included in the end-to-end matrix until its native
media spike is complete. Once implemented, iOS H.264 must pass the same
receiver-side rows and MPEG-TS compatibility checks as Android.

For each row verify codec selection, first-frame time, stable latency, sender
restart without receiver restart, and receiver recovery after a two-second
network interruption.

For Android camera interaction, also verify on a physical device that the
receiver reports decoded frames with the selected profile aspect ratio while
the phone is held in portrait and landscape orientations. Change zoom through
the slider and pinch gesture, confirm minimum/maximum/reset behavior, and
confirm the media session remains active. Minimize the app, lock and unlock the
phone, rotate or recreate the activity, and destroy/recreate the preview
surface; each operation must leave streaming running. Stop and restart after
these transitions, and repeat at least once with H.265.

## Stability runs

Record memory, CPU, decoder, first-frame time, latency, timeout count, and
Android battery temperature for 60 minutes at 1080p30, 30 minutes at 1440p30,
and 15 minutes at experimental 4K30. Normal phone heating is expected; a
growing latency or queue is not.
