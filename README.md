# CamBridge

Turn your Android phone into a low-latency camera source for OBS Studio.

CamBridge streams H.264 video directly from Android to a native OBS source,
with hardware-accelerated decoding where supported.

> Current support: Android sender → OBS Studio on Linux x86_64

## Features

- Android phone as an OBS camera source
- Low-latency H.264 streaming over a local network
- Native OBS source with automatic receiver discovery where supported
- Portrait and landscape orientations
- Configurable 1080p and 2K resolution at 15 or 30 fps
- Hardware decoding with a software fallback where hardware acceleration is unavailable

## Quick Start

1. Download the Android APK and Linux OBS plugin archive from the [latest
   GitHub release](https://github.com/louisemalvin/cambridge/releases/latest).
   Release artifacts use the names `cambridge-v<version>.apk` and
   `cambridge-obs-plugin-<version>-linux-x86_64.tar.gz`.
2. Install the APK on the Android phone and install the plugin on the Linux
   computer as described in [Installation](docs/installation.md).
3. Restart OBS, add a `CamBridge` source, and leave its default settings in
   place.
4. Put the phone and computer on the same trusted network, open CamBridge,
   and allow camera access.
5. Open Stream setup, wait for the OBS receiver check, choose resolution,
   frame rate, and orientation, then press **Start stream**.

## Current Platform Support

| Sender | Receiver | Status |
| --- | --- | --- |
| Android | Linux x86_64/amd64 + OBS Studio | Supported |

Windows, macOS, iOS, and ARM Linux receivers are not currently supported.

## How It Works

The phone captures and encodes video, sends it across the local network, and
the OBS source decodes and presents it as an OBS texture:

```text
Camera2 → MediaCodec H.264 → RTP/H.264 over UDP → FFmpeg → OBS source
                                                       ↳ VAAPI/DRM PRIME when available
                                                       ↳ software decode otherwise
```

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
- [Development](docs/development.md)
- [Security policy](SECURITY.md)

## License

CamBridge is distributed under the terms of the [Apache License 2.0](LICENSE).
