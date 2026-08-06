# Linux/OBS host setup

This document covers the current Linux/OBS host adapter. The receiver protocol
it implements is documented in [the baseline contract](../contract.md).

The native source requires:

- OBS Studio and libobs development headers
- CMake, a C++17 compiler, and pkg-config
- FFmpeg development libraries for libavcodec, libavutil, and libswscale
- libva with the DRM backend and libdrm
- jansson
- `jq` for the contract-backed emulator harness

Build and test the source:

```bash
./scripts/linux/build-direct-webcam-plugin.sh
```

The build prints the module path, source commit, SHA-256, and staging path.
The OBS-compatible staged layout is:

```text
staging/obs-plugins/direct-webcam-source/bin/64bit/direct-webcam-source.so
```

For a local OBS profile, copy that file to:

```text
~/.config/obs-studio/plugins/direct-webcam-source/bin/64bit/direct-webcam-source.so
```

Then add the `Phone Webcam` source in OBS. Its normal settings are already
configured, so the user does not need to change decoder or network settings.
The `scripts/linux/direct-webcam-test-scene.json` file is a valid isolated OBS
scene collection template for smoke testing. It uses a 2560x1440 canvas and
centered aspect-fit bounds, so portrait input is pillarboxed without a fixed
stretch or crop.

## LAN firewall

The source listens on the contract-backed control and media ports. Inspect the
scoped UFW rules with:

```bash
scripts/linux/setup-direct-webcam-firewall.sh --check
```

To add only the configured LAN rules, run the apply mode with administrator
authority:

```bash
scripts/linux/setup-direct-webcam-firewall.sh --apply
```

The script reads both ports and the single interface/source CIDR from the
deployment and protocol contracts. It is idempotent and does not open either
port globally.

## Hardware decode

The preferred render node is `/dev/dri/renderD128`. The source opens it for
VAAPI H.264 decode, maps frames as DRM PRIME, and imports the DMA-BUF into an
OBS texture. If the render node or import path is unavailable, the source
switches to software decode and dynamic NV12 upload. The active mode and
bounded queue metrics are available through the source diagnostics property.
