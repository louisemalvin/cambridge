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
codec negotiation, output policy, HTTP routes, session conflicts, timeout
cleanup, bounded pipeline construction, H.265 parser selection, discovery
state, and Linux device inspection. Kotlin tests cover shared fixture decoding,
negotiation, session cleanup behavior, profile-driven preview geometry,
orientation mapping, zoom clamping/reset, and RootEncoder output-dimension
mapping. The Android instrumentation suite also checks that the Material 3
zoom control exposes its slider and reset action, pairing persistence can be
forgotten, and the coordinator clears its approved receiver state. The
presentation mapper tests cover waiting, approval, streaming, failure
diagnostics, and camera-control projection without exposing domain types to
Compose. The preview viewport tests cover fitted landscape and portrait
geometry.

The MPEG-TS compatibility checks also assert 188-byte packet caps and the
H.264/H.265 parser branches. Android transport tests assert the derived
six-packet datagram limit. The iOS skeleton has source-level unit tests for
configuration validation and stub media-engine boundaries; they do not require
camera hardware or a completed media pipeline.

## Synthetic H.264

With a receiver using a test sink or a configured virtual camera, prepare a
session and send to the assigned UDP port:

```bash
PREPARED_SESSION=$(curl -fsS \
  -H 'content-type: application/json' \
  --data-binary @protocol/examples/prepare-h264-request.json \
  http://127.0.0.1:5001/v1/sessions/prepare)
MEDIA_PORT=$(jq -r '.media.port' <<<"$PREPARED_SESSION")
scripts/linux/synthetic-h264-sender.sh 127.0.0.1 "$MEDIA_PORT"
```

The sender creates a live 1080p30 test pattern, uses `x264enc` with a one-second
keyframe interval, packages H.264 in MPEG-TS, and sends UDP unicast. The
receiver should report `receiving`, a decoder name, and a first-frame event.

## Synthetic H.265

Prepare an H.265 session and run:

```bash
PREPARED_SESSION=$(curl -fsS \
  -H 'content-type: application/json' \
  --data-binary @protocol/examples/prepare-h265-request.json \
  http://127.0.0.1:5001/v1/sessions/prepare)
MEDIA_PORT=$(jq -r '.media.port' <<<"$PREPARED_SESSION")
scripts/linux/synthetic-h265-sender.sh 127.0.0.1 "$MEDIA_PORT"
```

If `x265enc`, `udpsink`, or another required element is unavailable, install
the GStreamer plugin package that provides it or mark the runtime test
skipped. Parser, negotiation, and pipeline construction tests still provide
useful coverage without a complete runtime sender path.

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

The desktop application can be used instead of the CLI:

```bash
mobile-webcam-desktop
```

It is the single receiver process for the control API, UDP media, decoded
preview, and v4l2loopback output. Do not start both receiver binaries on the
same ports and virtual-camera device.

During a physical Android lifecycle check, record the sender-control `streamId`
for each demand activation. A repeated start retry must reuse the same ID, a
later activation must use a new ID, and an old stop must not stop the newer
activation. Killing the desktop while streaming should stop the Android camera
after the bounded receiver-session watchdog window.

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
