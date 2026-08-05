# Mobile Webcam

Mobile Webcam streams video from a mobile sender to a Linux desktop with a
low-latency, bounded-buffer path. Android is the active sender implementation;
iOS currently provides a native development skeleton:

```text
Android or iOS video source -> native H.264 encoder -> MPEG-TS over encrypted SRT
    -> Rust/GStreamer receiver -> persistent v4l2loopback -> OBS or browser
```

The local-network flow has three explicit boundaries:

- Receiver discovery with sender-side manual-origin fallback.
- HTTP/JSON receiver control on TCP port `5001`.
- One receiver-owned SRT listener on port `5000` (SRT uses UDP transport), with
  per-session stream IDs and AES-256 passphrases.

H.264 is the compatibility codec. H.265 remains available only where the
receiver and sender explicitly support it. The project is video-only and
designed for a trusted local network. v2 control can require a bearer token;
SRT media is encrypted per session.

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

It opens the receiver window, shows a live preview, advertises itself on the
local network, and writes the same decoded frames to the virtual camera. No
device path is required. The receiver selects the first v4l2loopback device and
waits for the phone to select it and create a v2 session. The Android app keeps
manual receiver-origin entry as a fallback when local discovery is unavailable.

The headless receiver is also available for servers or terminal-only sessions:

```bash
mobile-webcam-receiver
```

Advanced users can pass `--device`, `--control-port`, `--srt-port`, or
`--receiver-name` to the receiver. The SRT host is derived from the control
origin automatically. `--advertise-host` remains an explicit override for
emulators, NAT, and multi-homed hosts. The repository wrappers
`./scripts/linux/start-desktop.sh` and `./scripts/linux/start-receiver.sh` are
available before installation.

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
- [Reliable SRT streaming plan](docs/reliable-streaming-v2-plan.md)
- [Control and media protocol](docs/protocol.md)
- [Control protocol](docs/protocol.md)
- [Codec behavior](docs/codecs.md)
- [Latency testing](docs/latency-testing.md)
- [Troubleshooting](docs/troubleshooting.md)
- [USB tethering](docs/usb-tethering.md)
