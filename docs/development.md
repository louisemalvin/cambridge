# Development

This page is for contributors and maintainers. User installation is covered
in [Installation](installation.md).

## Prerequisites

The full repository checks require:

- JDK 17 and the Android SDK/toolchain used by the project
- CMake, a C++17 compiler, and `pkg-config`
- OBS Studio development headers (`libobs`)
- FFmpeg development libraries (`libavcodec`, `libavutil`, and `libswscale`)
- `libva` with the DRM backend, `libdrm`, and `jansson`

The emulator smoke test additionally uses an Android emulator, `adb`, `ffmpeg`,
`jq`, and an installed OBS binary. Avahi development files are optional for
local builds, but required for the packaged Linux receiver because they enable
receiver discovery advertisement.

## Repository layout

- `sender/android/` — Android sender
- `receiver/linux/obs/` — Linux OBS receiver
- `protocol/` — shared wire contract, schema, and examples
- `scripts/sender/` and `scripts/receiver/` — platform-specific checks and fixtures

## Repository checks

Run the complete local check from the repository root:

```bash
JAVA_HOME=/path/to/jdk-17 ./scripts/development/check-all.sh
```

This validates contract parity, runs Android unit tests and lint, assembles a
debug APK, builds and tests the native plugin, and checks its linked libraries.

## Android build

From the repository root:

```bash
cd sender/android
JAVA_HOME=/path/to/jdk-17 ./gradlew \
  testDebugUnitTest lint assembleDebug --console=plain
```

The debug APK is written to
`sender/android/app/build/outputs/apk/debug/app-debug.apk` when referenced from the
repository root.

## OBS plugin build

Build, test, and stage the native plugin with:

```bash
./scripts/receiver/linux/build-cambridge-obs-plugin.sh
```

The staged module is written to:

```text
build/cambridge-obs-plugin/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so
```

Check its runtime dependencies with:

```bash
ldd -r build/cambridge-obs-plugin/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so
```

## Integration tests

### Android emulator

Use an Android API 35 emulator with a camera input. Set
`CAMBRIDGE_AVD_NAME` when more than one AVD is installed:

```bash
CAMBRIDGE_AVD_NAME=your-api-35-avd ./scripts/sender/android/test-emulator-cambridge.sh
```

Replace `your-api-35-avd` with the AVD name on your machine. The harness
builds the debug APK and plugin, starts an isolated OBS instance, uses a
deterministic camera input, and checks session acceptance, decoding, first-frame
presentation, and stream cleanup. It uses the test-only `720p30` profile. The
script prints the temporary directory containing Android, OBS, emulator, and
build logs.

The emulator name and serial are configurable with `CAMBRIDGE_AVD_NAME`,
`CAMBRIDGE_EMULATOR_PORT`, and `CAMBRIDGE_EMULATOR_SERIAL`; no particular
workstation AVD name is required.

### Native receiver fixture

Run the contract-conformant native fixture with the default hardware/software
decoder selection:

```bash
CAMBRIDGE_PROFILE_ID=2k30 \
CAMBRIDGE_DURATION_SECONDS=30 \
bash scripts/receiver/linux/test-cambridge-fixture.sh
```

Force the bounded CPU fallback with `CAMBRIDGE_DECODER_MODE=cpu`. Set
`CAMBRIDGE_ROTATION_DEGREES=90` to exercise portrait display metadata. Set
`CAMBRIDGE_CAPTURE_OUTPUT=1` to save an isolated OBS recording and frame
hashes.

## Diagnostics

The Android app records structured stream events in its process log. The OBS
source reports receiver, decoder, render mode, frame, and drop events in the
OBS log. In the CamBridge source properties, **Write diagnostics now** saves a
snapshot containing session identity, geometry, decoder/render mode, queue
occupancy, drops, and frame counters.

For an emulator run, use the log directory printed by the harness. Hardware
decode may report VAAPI/DRM PRIME and direct DMA-BUF; software decode and NV12
upload are valid fallback modes.

## Release packaging

Maintainers create a release by updating `VERSION` and pushing a matching
`v<version>` tag. The release workflow builds a signed Android APK named
`cambridge-v<version>.apk` and a Linux archive named
`cambridge-obs-plugin-<version>-linux-x86_64.tar.gz`.

Signing keys and passwords belong in the release environment or GitHub Actions
secrets, never in the repository. The packaging script writes release files
under `build/release/`.
