# Mobile Webcam

Mobile Webcam streams video from a mobile sender to a Linux desktop with a
low-latency, bounded-buffer path. Android is the active sender implementation;
iOS currently provides a native development skeleton:

```text
Android or iOS camera -> platform H.264/H.265 encoder -> MPEG-TS over UDP
    -> Rust/GStreamer receiver -> v4l2loopback -> OBS or browser
```

The local-network flow has three explicit boundaries:

- Desktop-side discovery of the mobile sender's TCP control service.
- HTTP/JSON receiver control on TCP port `5001`.
- MPEG-TS media on a UDP port allocated from `50000-50099` for each session.

H.264 is the compatibility codec. Auto mode prefers H.265 when both endpoints
support the selected profile. The project is video-only and designed for a
trusted local network. Control and media are intentionally unencrypted in
Phase 1.

## Repository

- `android/`: one Kotlin/Compose sender application.
- `ios/`: native SwiftUI/Xcode development skeleton and future media boundary.
- `desktop/`: reusable Rust receiver crates and thin CLI.
- `protocol/`: versioned control contract and JSON fixtures.
- `docs/`: architecture, media transport, setup, testing, troubleshooting, and decisions.
- `scripts/`: Linux setup and synthetic stream helpers.

## Quick start

From a supported Linux desktop, run the one-time installer from the repository
root:

```bash
./scripts/linux/install-receiver.sh
```

The installer handles the GStreamer packages, v4l2loopback module setup, and
release build. It may ask for `sudo` for those operating-system changes. Start
the desktop receiver each day with:

```bash
mobile-webcam-desktop
```

It opens the receiver window, shows a live preview, and writes the same decoded
frames to the virtual camera. No device path or port arguments are required.
The receiver automatically selects the first v4l2loopback device, discovers
available mobile senders, and connects to the only sender automatically. The
first connection requires approval on the sender. Approved pairs reconnect without
entering an address.

The headless receiver is also available for servers or terminal-only sessions:

```bash
mobile-webcam-receiver
```

Advanced users can pass `--device` or `--control-port` to the desktop app. Both
receiver applications allocate a fresh UDP media port from `50000-50099` for
each prepared session. The repository wrappers `./scripts/linux/start-desktop.sh` and
`./scripts/linux/start-receiver.sh` are available before installation.

The installer supports CachyOS/Arch and Ubuntu/Debian. See the matching
[Arch Linux guide](docs/linux-arch-setup.md) or
[Ubuntu guide](docs/linux-ubuntu-setup.md) for platform-specific notes.

The Android application requires Android Studio or a JDK 17 Android build
environment. See [Android setup](docs/android-setup.md).

## Status

The Phase 1 implementation is intentionally hardware-dependent at its final
edges. Synthetic GStreamer tests and Rust/Kotlin unit tests cover the control,
negotiation, and media architecture. Physical Android, v4l2loopback, OBS,
browser, Wi-Fi, and USB-tethering validation must be run on a configured host.
The iOS target is a non-functional skeleton until its native media spike is
complete.

See [known limitations](docs/known-limitations.md) and [testing](docs/testing.md).

Key references:

- [Architecture](docs/architecture.md)
- [MPEG-TS/UDP media contract](docs/media-transport-v1.md)
- [Control protocol](docs/protocol.md)
- [Codec behavior](docs/codecs.md)
- [Latency testing](docs/latency-testing.md)
- [Troubleshooting](docs/troubleshooting.md)
- [USB tethering](docs/usb-tethering.md)
