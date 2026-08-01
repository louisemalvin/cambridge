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

Build the receiver with:

```bash
cargo build --manifest-path desktop/Cargo.toml --workspace
```

Then follow [Linux setup](docs/linux-ubuntu-setup.md) or
[Arch Linux setup](docs/linux-arch-setup.md), and run:

```bash
mobile-webcam-receiver --device /dev/video10
```

The Android application requires Android Studio or a JDK 17 Android build
environment. See [Android setup](docs/android-setup.md).

## Status

The Phase 1 implementation is intentionally hardware-dependent at its final
edges. Synthetic GStreamer tests and Rust/Kotlin unit tests cover the control,
negotiation, and media architecture. Physical Android, v4l2loopback, OBS,
browser, Wi-Fi, and USB-tethering validation must be run on a configured host.

See [known limitations](docs/known-limitations.md) and [testing](docs/testing.md).

