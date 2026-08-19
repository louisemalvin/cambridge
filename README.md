# CamBridge

Turn your phone into a webcam through OBS Studio.

CamBridge streams H.264 video from a phone camera over your local network to a
native source in OBS. Use the source in an OBS scene for recording or
streaming, or share the scene with video-call applications through
[OBS Virtual Camera](https://obsproject.com/kb/virtual-camera-guide).

## Features

### Phone sender

- Direct camera streaming to OBS over the local network
- Automatic receiver discovery with explicit selection and a manual address
  fallback
- Phone-supported 1080p and 2K modes at 30 or 60 fps with bitrate control
- Portrait and landscape video with explicit stabilization controls

### OBS receiver

- Native CamBridge source for OBS Studio
- Hardware-accelerated H.264 decoding where available with a software fallback
- Phone camera output for recording, streaming, and OBS Virtual Camera

## Quick Start

1. Install CamBridge on the phone and the CamBridge plugin for OBS by following
   the [installation guide](docs/installation.md).
2. Add a **CamBridge** source in OBS, select it from the phone, and press
   **Start stream**.
3. To use the scene in another application, start **Virtual Camera** in OBS and
   select **OBS Virtual Camera** in that application.

## Platform Support

### Senders

| Platform | Availability |
| --- | --- |
| Android | Supported |
| iOS 17.4+ | In development |

### OBS receivers

| Platform | Availability |
| --- | --- |
| Linux x86_64/amd64 | Supported |
| macOS 12+ on arm64 and x86_64 | In development |
| Windows | Not available |
| Linux ARM | Not available |

Senders and receivers use the same versioned CamBridge protocol and are not
coupled to a particular platform pairing. Supported downloads are published on
the [latest release](https://github.com/louisemalvin/cambridge/releases/latest).
Install sender and receiver artifacts from the same CamBridge release.

Platforms marked as in development are implemented and tested in CI, but still
require physical-device or clean-package acceptance before release.

## How It Works

```text
Phone camera
    -> CamBridge sender
    -> H.264/RTP over the local network
    -> CamBridge source in OBS Studio
    -> recording, streaming, or OBS Virtual Camera
```

The sender owns camera and video settings. The receiver owns decoding and OBS
presentation. See [Architecture](docs/architecture.md) for the component
boundaries and buffering model.

## Security and Limitations

CamBridge is designed for a trusted local network. Its control and media
transport is not encrypted or authenticated. Read
[Known limitations](docs/known-limitations.md) before deployment.

## Documentation

- [Installation](docs/installation.md)
- [Architecture](docs/architecture.md)
- [Protocol](docs/protocol.md)
- [Known limitations](docs/known-limitations.md)
- [Android camera modes](docs/android-camera.md)
- [Development](docs/development.md)
- [Security policy](SECURITY.md)

## Contributing

Source builds, tests, diagnostics, and release packaging are covered in
[Development](docs/development.md). Contributors should also read
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

CamBridge uses a split license:

- The senders, protocol, scripts, and documentation are distributed under the
  [Apache License 2.0](LICENSE).
- The native OBS plugin is distributed under
  [GPL-2.0-or-later](receiver/obs/cambridge-obs-source/LICENSE) because it links
  with OBS Studio.

See [Third-party notices](THIRD_PARTY_NOTICES.md) for dependency licensing.
