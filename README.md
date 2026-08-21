# CamBridge

Turn your phone into a webcam through OBS Studio.

CamBridge sends live video from your phone camera to OBS over your local
network. Add the camera to an OBS scene for recording or streaming, or share
the scene with video-call applications through
[OBS Virtual Camera](https://obsproject.com/kb/virtual-camera-guide).

## Features

### On your phone

- See a live preview while using the camera in OBS
- Find your OBS computer automatically or enter its address yourself
- Choose the resolution, frame rate, and video quality
- Use portrait or landscape video and adjust zoom and stabilization

### In OBS

- Add the phone camera to a scene like any other source
- Record it, include it in a livestream, or use it in video-call applications
- Keep the phone-to-computer connection on your local network without an
  account or cloud service

## Quick Start

1. Install CamBridge on the phone and the CamBridge plugin for OBS by following
   the [installation guide](docs/installation.md).
2. Add a **CamBridge** source in OBS, select the computer from the phone, and
   press **Start stream**.
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

Every CamBridge sender works with every CamBridge receiver from the same
version. The phone and computer platforms are not fixed pairs. Supported
Android downloads are published on the
[latest CamBridge release](https://github.com/louisemalvin/cambridge/releases/latest).
The Linux OBS receiver is currently installed from source against the host's
OBS and FFmpeg packages.

Platforms marked as in development are not included in public releases yet.

## How It Works

```text
Phone camera
    -> CamBridge app
    -> local network
    -> CamBridge source in OBS Studio
    -> recording, streaming, or OBS Virtual Camera
```

The phone controls the camera and picture settings. The OBS plugin receives the
video and displays it in your scene. See [Architecture](docs/architecture.md)
for technical design details.

## Security and Limitations

CamBridge does not encrypt or verify connections, so use it only on a network
you trust. Read [Known limitations](docs/known-limitations.md) before use.

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
