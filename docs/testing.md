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
gradle -p android test lint assembleDebug
```

Rust tests cover JSON schema fixtures, protocol version and codec rejection,
codec negotiation, output policy, HTTP routes, session conflicts, timeout
cleanup, bounded pipeline construction, H.265 parser selection, and Linux
device inspection. Kotlin tests cover shared fixture decoding, negotiation,
endpoint validation, and session cleanup behavior.

## Synthetic H.264

With a receiver using a test sink or a configured virtual camera, run:

```bash
scripts/linux/synthetic-h264-sender.sh 127.0.0.1 5000
```

The sender creates a live 1080p30 test pattern, uses `x264enc` with a one-second
keyframe interval, packages H.264 in MPEG-TS, and sends UDP unicast. The
receiver should report `receiving`, a decoder name, and a first-frame event.

## Synthetic H.265

Run:

```bash
scripts/linux/synthetic-h265-sender.sh 127.0.0.1 5000
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

The test script automatically selects the first v4l2loopback device. Pass an
explicit device path only when testing a non-default loopback configuration.

Open the device in OBS or a browser after the producer attaches. In OBS, add
`Video Capture Device (V4L2)` and select `Mobile Webcam`. With
`exclusive_caps=1`, the device is initially producer-facing and becomes
capture-facing after `v4l2sink` connects.

The desktop application can be used instead of the CLI:

```bash
mobile-webcam-desktop
```

It is the single receiver process for the control API, UDP media, decoded
preview, and v4l2loopback output. Do not start both receiver binaries on the
same ports and virtual-camera device.

## End-to-end matrix

Run each applicable row on a physical phone and configured Linux host:

| Sender | Network | Receiver | Consumer |
| --- | --- | --- | --- |
| Android H.264 | Wi-Fi | Arch | OBS |
| Android H.264 | Wi-Fi | Ubuntu | OBS |
| Android H.264 | USB tethering | Arch | OBS |
| Android H.264 | USB tethering | Ubuntu | OBS |
| Android H.265 | Wi-Fi | Linux | OBS |
| Android H.265 | USB tethering | Linux | OBS |
| Android H.264 | Wi-Fi or USB | Linux | Browser |

For each row verify codec selection, first-frame time, stable latency, sender
restart without receiver restart, and receiver recovery after a two-second
network interruption.

## Stability runs

Record memory, CPU, decoder, first-frame time, latency, timeout count, and
Android battery temperature for 60 minutes at 1080p30, 30 minutes at 1440p30,
and 15 minutes at experimental 4K30. Normal phone heating is expected; a
growing latency or queue is not.
