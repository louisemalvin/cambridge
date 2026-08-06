# Mobile Webcam

Mobile Webcam sends camera video through one bounded, low-latency direct stream
to a configured receiver. The current product is an Android sender and a
native Linux OBS host:

```text
Camera2 -> MediaCodec H.264 -> RTP/H.264 over UDP -> FFmpeg H.264 decoder
        -> VAAPI DRM PRIME -> DMA-BUF -> OBS texture
```

If VAAPI or DMA-BUF import is unavailable, the source falls back to software
decode and one bounded NV12 texture upload. The receiver keeps one active
session and drops stale work instead of building an unbounded queue.
The normal quality is 2K30; 720p30 is retained only for the named AVD smoke
test.

## Repository

- `android/`: Kotlin/Compose phone app with a setup-first stream flow
- `desktop/hosts/obs/direct-webcam-source/`: current native OBS host adapter
- `protocol/`: versioned TCP control and RTP contract
- `docs/`: setup, architecture, verification, diagnostics, and limitations
- `scripts/`: native build, emulator smoke, and repository checks

## Build

Install Android Studio or a JDK 17 Android toolchain, OBS development headers,
FFmpeg development libraries, libva/libdrm, and jansson. Then run:

```bash
./scripts/development/check-all.sh
```

The native module is installed into a staging directory under `build/` and the
debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Emulator smoke

The only supported automated Android runtime target is the AVD
`codex-phone-webcam-api35`. The harness creates a deterministic camera video,
starts an isolated OBS instance, installs the debug APK, supplies the AVD host
endpoint `10.0.2.2`, opens the stream setup screen, presses the semantic Start
stream action, and verifies native decode, first-frame
publication, and either direct DMA-BUF or CPU NV12 presentation:

```bash
./scripts/android/test-emulator-direct-webcam.sh
```

Every ADB command in that harness targets the explicit emulator serial
`emulator-5556`. It must not be pointed at a physical phone.

## Manual OBS setup

Build and stage the plugin with:

```bash
./scripts/linux/build-direct-webcam-plugin.sh
```

Install the staged plugin using the OBS plugin directory layout documented in
[Linux/OBS setup](docs/platforms/linux-obs.md), then add the `Phone Webcam`
source in OBS.
Its normal settings are already configured. Open the Phone Webcam app on the
phone, choose the stream quality and orientation, and press Start stream. No
OBS transport or decoder settings need to be changed.

## Documentation

- [Baseline contract](docs/contract.md)
- [Architecture](docs/architecture.md)
- [Android setup](docs/platforms/android.md)
- [Linux/OBS setup](docs/platforms/linux-obs.md)
- [Testing](docs/operations/testing.md)
- [Diagnostics](docs/operations/diagnostics.md)
- [Known limitations](docs/operations/known-limitations.md)
