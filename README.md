# CamBridge

Turn your Android phone into a low-latency camera source for OBS Studio.

CamBridge streams H.264 video directly from Android to a native OBS source,
with hardware-accelerated decoding where supported.

> Current support: Android sender → OBS Studio on Linux x86_64

## Features

- Android phone as an OBS camera source
- Low-latency H.264 streaming over a local network
- Automatic receiver discovery, explicit receiver selection, and manual fallback
- Portrait and landscape orientations
- Phone-supported 1080p and 2K modes at 30 or 60 fps with bitrate control
- Explicit optical, electronic, and camera-managed stabilization controls
- Automatic, native-required, and software-only receiver decoder modes
- Hardware decoding with a software fallback selected once at session start

## Quick Start

1. Download the Android APK and Linux OBS plugin archive from the [latest
   GitHub release](https://github.com/louisemalvin/cambridge/releases/latest).
   Release artifacts use the names `cambridge-v<version>.apk` and
   `cambridge-obs-plugin-<version>-linux-x86_64.tar.gz`.
2. Install the APK on the Android phone and install the plugin on the Linux
   computer as described in [Installation](docs/installation.md).
   Always install both artifacts from the same release because protocol
   compatibility is versioned.
3. Restart OBS, add a `CamBridge` source, and leave its default settings in
   place.
4. Put the phone and computer on the same trusted network, open CamBridge,
   and allow camera access.
5. Open Stream setup, choose the OBS computer when more than one is available,
   choose resolution, frame rate, and bitrate, then press
   **Start stream**.

## Current Platform Support

| Sender | Receiver | Status |
| --- | --- | --- |
| Android | Linux x86_64/amd64 + OBS Studio | Supported |
| Android | macOS 12+ + OBS Studio | Implementation present; physical acceptance pending |
| iOS 17.4+ | Linux x86_64/amd64 + OBS Studio | Install candidate; physical acceptance pending |

Windows and ARM Linux receivers are not currently supported.
The macOS receiver is not a supported downloadable product until its arm64 and
x86_64 physical acceptance, clean-machine package installation, and release
verification gates pass.

## How It Works

The phone captures and encodes video, sends it across the local network, and
one shared OBS receiver decodes and presents it as an OBS texture:

```text
Camera2 → MediaCodec H.264 → RTP/H.264 over UDP → shared OBS receiver
                                                   ↳ Linux: FFmpeg → VAAPI/DRM PRIME → DMA-BUF → OBS
                                                   ↳ macOS: FFmpeg → VideoToolbox → Metal → IOSurface → OBS
                                                   ↳ software: FFmpeg → CPU NV12 → OBS
```

The receiver locks one of the selected media paths before accepting RTP for a
session. A native decode, conversion, or import failure ends that session; it
does not silently switch paths after decoding starts.

See [Architecture](docs/architecture.md) for the component boundaries and
latency/buffering model.

## Installation

Downloadable installation and first-use instructions are in
[Installation](docs/installation.md). Users do not need to build the project
from source.

## Known Limitations

Read [Known limitations](docs/known-limitations.md) before deploying CamBridge,
especially the trusted-network and host-GPU requirements.

## Building / Contributing

Source builds, repository checks, emulator testing, diagnostics, and release
packaging are covered in [Development](docs/development.md). Contributors
should also read [CONTRIBUTING.md](CONTRIBUTING.md).

## Documentation

- [Installation](docs/installation.md)
- [Architecture](docs/architecture.md)
- [Protocol](docs/protocol.md)
- [Known limitations](docs/known-limitations.md)
- [Android camera modes](docs/android-camera.md)
- [Development](docs/development.md)
- [Security policy](SECURITY.md)

## License

CamBridge is distributed under the terms of the [Apache License 2.0](LICENSE).
