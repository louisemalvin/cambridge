# Mobile Webcam

Mobile Webcam streams video from an Android phone to a Linux desktop with a
low-latency, bounded-buffer path:

```text
Android camera -> hardware H.264/H.265 encoder -> MPEG-TS over UDP
    -> Rust/GStreamer receiver -> v4l2loopback -> OBS or browser
```

Phase 1 uses two local-network connections:

- HTTP/JSON control on TCP port `5001`.
- MPEG-TS media over UDP port `5000`.

H.264 is the compatibility codec. Auto mode prefers H.265 when both endpoints
support the selected profile. The project is video-only and designed for a
trusted local network. Control and media are intentionally unencrypted in
Phase 1.

## Repository

- `android/`: one Kotlin/Compose sender application.
- `desktop/`: reusable Rust receiver crates and thin CLI.
- `protocol/`: versioned control contract and JSON fixtures.
- `docs/`: architecture, setup, testing, troubleshooting, and decisions.
- `scripts/`: Linux setup and synthetic stream helpers.

## Quick start

From a supported Linux desktop, run the one-time installer from the repository
root:

```bash
./scripts/linux/install-receiver.sh
```

The installer handles the GStreamer packages, v4l2loopback module setup, and
release build. It may ask for `sudo` for those operating-system changes. Start
the receiver each day with:

```bash
mobile-webcam-receiver
```

No device path or port arguments are required. The receiver automatically
selects the first v4l2loopback device and uses control TCP `5001` plus media
UDP `5000`. Advanced users can still pass `--device`, `--control-port`, or
`--media-port`. The repository wrapper `./scripts/linux/start-receiver.sh` is
also available for local development.

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

See [known limitations](docs/known-limitations.md) and [testing](docs/testing.md).

Key references:

- [Architecture](docs/architecture.md)
- [Control protocol](docs/protocol.md)
- [Codec behavior](docs/codecs.md)
- [Latency testing](docs/latency-testing.md)
- [Troubleshooting](docs/troubleshooting.md)
- [USB tethering](docs/usb-tethering.md)
