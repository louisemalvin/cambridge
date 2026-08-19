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
- Swift 6.0.3 or Docker for the Linux `CamBridgeCore` and Swift fixture checks

Linux plugin builds use the host OBS and FFmpeg development packages selected by
`pkg-config`. The current Linux compatibility floor is OBS 32.2.0 with the
FFmpeg 8 ABI family or newer. The plugin is intentionally not bundled with
those libraries, so its build and installation target must provide compatible
system or OBS-managed libraries.

The Xcode project requires macOS with Xcode 16.4 (or a compatible newer Xcode)
for Apple-target compilation. A physical iPhone is required for hardware
camera, local-network permission, signing, and glass-to-glass validation.

The emulator smoke test additionally uses an Android emulator, `adb`, `ffmpeg`,
`jq`, and an installed OBS binary. Avahi development files are optional for
local builds, but required for the packaged Linux receiver because they enable
receiver discovery advertisement.

macOS receiver builds require macOS 12 or later, Xcode command-line tools,
Homebrew, and the pinned CMake, OBS Studio, and FFmpeg dependencies prepared by
`scripts/receiver/macos/prepare-cambridge-build-dependencies.sh`.

## Repository layout

- `sender/android/app/` — Android sender application
- `sender/android/receiver-discovery/` — lifecycle-scoped Android DNS-SD library
- `receiver/obs/` — shared OBS receiver
- `protocol/` — shared wire contract, schema, and examples
- `scripts/sender/` and `scripts/receiver/` — platform-specific checks and fixtures
- `sender/cambridge-video-settings.json` — shared Android/iOS resolution, frame-rate, and bitrate defaults

## Repository checks

Run the complete local check from the repository root:

```bash
JAVA_HOME=/path/to/jdk-17 ./scripts/development/check-all.sh
```

This validates contract parity, runs Android unit tests and lint, assembles a
debug APK, builds and tests the native plugin, and checks its linked libraries.

Run the iOS-independent checks separately from the repository root:

```bash
./scripts/sender/ios/check-core.sh
./scripts/sender/ios/check-fixture.sh
```

These commands check generated contract, sender-mode, and version outputs,
run the Swift 6 package tests, and build the Linux Swift interoperability
fixture. They do not claim that Apple-only app code compiles.

On macOS, run the committed unsigned Xcode project and scheme:

```bash
./scripts/sender/ios/check-xcode.sh
```

The script selects the first available iPhone simulator deterministically and
uses `CODE_SIGNING_ALLOWED=NO`. It intentionally does not test a real camera
or hardware H.264 encoder.

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

The Linux CI and release jobs prepare the pinned OBS 32.2.0 development
library from the official OBS source archive before building the plugin. A
normal local build uses the host's compatible OBS development package through
`pkg-config`.

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

The build script also runs the dependency validator, which checks that the
module uses the selected `libobs` and FFmpeg SONAMEs, contains no RPATH/RUNPATH,
and has no unresolved transitive dependencies.

### macOS receiver build and fixture

The macOS build uses the shared receiver tree and selects the three concrete
macOS implementations at CMake configure time. Prepare one architecture's
pinned FFmpeg and OBS/libobs prerequisites, then build that slice:

```bash
CAMBRIDGE_MACOS_ARCHITECTURE=arm64 \
  ./scripts/receiver/macos/prepare-cambridge-build-dependencies.sh
CAMBRIDGE_MACOS_ARCHITECTURES=arm64 \
CAMBRIDGE_REQUIRE_UNIVERSAL=OFF \
  ./scripts/receiver/macos/build-cambridge-obs-plugin.sh
```

The release workflow repeats this for arm64 and x86_64 and combines the
verified slices into one universal package. The build checks `lipo` architecture
membership, validates the bundle metadata and ad hoc development signature, and
rejects unintended Homebrew load paths with `otool -L`. The staged artifact is
one self-contained bundle:

```text
build/cambridge-obs-plugin-macos/staging/obs-plugins/cambridge-obs-plugin.plugin
```

Its executable is under `Contents/MacOS`, while the Info.plist and compiled
Metal library are under `Contents/Info.plist` and `Contents/Resources`.

The preparation script reads the pinned CMake, OBS, and FFmpeg URLs and SHA-256
values from `receiver/obs/cambridge-obs-source/buildspec.json`, then uses the
committed libobs-only OBS entry point to build only `libobs` and install only
the `Development` component. It verifies the resolved pinned versions and
architecture before exporting the CMake and pkg-config paths used by the
plugin build. The Native workflow repeats those checks for arm64 and x86_64
and uploads each verified `.plugin` bundle. The native decoder CTest runs its
VideoToolbox assertions when H.264 hardware decoding is available and reports
an explicit CTest skip on hosts without that capability; the native-required
fixture remains the physical acceptance gate.

Run the native acceptance fixture with the mode required for native assertions:

```bash
CAMBRIDGE_DECODER_MODE=native_required \
CAMBRIDGE_DURATION_SECONDS=5 \
  ./scripts/receiver/macos/test-cambridge-fixture.sh
```

Software and explicit native fault checks are separate:

```bash
CAMBRIDGE_DECODER_MODE=cpu CAMBRIDGE_DURATION_SECONDS=5 \
  ./scripts/receiver/macos/test-cambridge-fixture.sh
CAMBRIDGE_ENABLE_TEST_FAULTS=ON CAMBRIDGE_NATIVE_FAULT=pool \
CAMBRIDGE_DECODER_MODE=native_required \
  CAMBRIDGE_DURATION_SECONDS=5 \
  ./scripts/receiver/macos/test-cambridge-fixture.sh
```

The fault harness also covers export, conversion, and import. A native-required
run must report `sessionMediaPath` as `native`, `cpuFrameCopies` as zero, and
one GPU copy per presented native frame; software output never satisfies that
gate. Native failures are terminal for the active generation.

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
CAMBRIDGE_PROFILE_ID=fixture-2k30 \
CAMBRIDGE_WIDTH=2560 \
CAMBRIDGE_HEIGHT=1440 \
CAMBRIDGE_FPS=30 \
CAMBRIDGE_BITRATE_BPS=18000000 \
CAMBRIDGE_DURATION_SECONDS=30 \
bash scripts/receiver/linux/test-cambridge-fixture.sh
```

Force the bounded CPU fallback with `CAMBRIDGE_DECODER_MODE=cpu`. Set
`CAMBRIDGE_ROTATION_DEGREES=90` to exercise portrait display metadata. Set
`CAMBRIDGE_CAPTURE_OUTPUT=1` to save an isolated OBS recording and frame
hashes.

Use the Swift transport fixture against the same unchanged native receiver by
setting `CAMBRIDGE_SENDER_MODE=swift`. For a software-only local run:

```bash
CAMBRIDGE_SENDER_MODE=swift \
CAMBRIDGE_DECODER_MODE=cpu \
CAMBRIDGE_PROFILE_ID=fixture-720p30 \
CAMBRIDGE_WIDTH=1280 \
CAMBRIDGE_HEIGHT=720 \
CAMBRIDGE_FPS=30 \
CAMBRIDGE_BITRATE_BPS=4000000 \
CAMBRIDGE_DURATION_SECONDS=1 \
bash scripts/receiver/linux/test-cambridge-fixture.sh
```

The Swift fixture performs a probe, validates the returned capabilities,
sends hello plus Annex-B H.264 RTP, and sends a matching explicit stop. The
native harness requires OBS, FFmpeg, `jq`, and the Linux plugin environment.

## Diagnostics

The Android app records structured stream events in its process log. The OBS
source reports receiver, decoder, render mode, frame, and drop events in the
OBS log. In the CamBridge source properties, **Write diagnostics now** saves a
snapshot containing session identity, geometry, decoder/render mode, queue
occupancy, drops, and frame counters.

The diagnostic field `hardwareCpuTransfers` is retained for tooling
compatibility and is deprecated; native-frame export failures are terminal and
the field is always serialized as zero. Native diagnostics also record the setup
result, selected and locked media path, native export/conversion/import
failures, pool exhaustion, and `gpuCopies`.

For an emulator run, use the log directory printed by the harness. Hardware
decode may report VAAPI/DRM PRIME and direct DMA-BUF; software decode and NV12
upload are valid fallback modes.

Android camera mode ownership, stabilization behavior, and the physical A/B
validation matrix are documented in [Android camera modes](android-camera.md).
Receiver-discovery failures are exposed to the app coordinator and logged with
the failed NSD operation and Android error code when one is available.

The iOS physical validation matrix is deliberately not reported as complete
on Linux. Before calling iOS supported, a Mac-lab run must retain the Xcode
version, iPhone model/iOS version, requested and applied source/output formats,
encoder identity,
all four orientations, camera controls, interruption/background behavior,
queue/drop/thermal diagnostics, and OBS hardware plus CPU-fallback results.

## Release packaging

Every user-facing update is a release. Bump `VERSION`, add a corresponding
`CHANGELOG.md` entry, and publish the downloadable artifacts; do not send
end users through a source build. Maintainers prepare the release by running
the complete repository checks and validating the Linux package with
`./scripts/release/package-linux-plugin.sh`. Push the release commit before
pushing its matching `v<version>` tag. The tag starts the release workflow,
which builds a signed Android APK named `cambridge-v<version>.apk` and a Linux
archive named `cambridge-obs-plugin-<version>-linux-x86_64.tar.gz`, plus a
macOS universal package named
`cambridge-obs-plugin-<version>-macos-universal.pkg`.

The macOS package script requires Developer ID application and installer
identities plus a configured `notarytool` keychain profile. It signs the whole
plugin bundle and installer, submits the package, requires an `Accepted`
notarization result, staples and validates the ticket, verifies Gatekeeper and
package signatures, and writes a SHA-256 checksum.

The release workflow imports credentials into a temporary keychain on a fresh
runner. Configure these Actions secrets:

- `CAMBRIDGE_DEVELOPER_ID_APPLICATION` and
  `CAMBRIDGE_DEVELOPER_ID_INSTALLER`: exact signing identity names
- `CAMBRIDGE_DEVELOPER_ID_APPLICATION_P12_BASE64` and
  `CAMBRIDGE_DEVELOPER_ID_APPLICATION_P12_PASSWORD`
- `CAMBRIDGE_DEVELOPER_ID_INSTALLER_P12_BASE64` and
  `CAMBRIDGE_DEVELOPER_ID_INSTALLER_P12_PASSWORD`
- `CAMBRIDGE_SIGNING_KEYCHAIN_PASSWORD`
- `CAMBRIDGE_NOTARY_PROFILE`, `CAMBRIDGE_NOTARY_APPLE_ID`,
  `CAMBRIDGE_NOTARY_TEAM_ID`, and `CAMBRIDGE_NOTARY_APP_PASSWORD`

The temporary keychain and decoded PKCS#12 files are deleted after packaging.
The tag workflow builds and publishes macOS only when the repository Actions
variable `CAMBRIDGE_RELEASE_MACOS` is exactly `true`. Leave it unset until both
physical architecture gates and a clean-machine install, uninstall, and
reinstall have been retained. Android and Linux releases remain independent of
that support gate.

Signing keys and passwords belong in the release environment or GitHub Actions
secrets, never in the repository. The packaging script writes release files
under `build/release/`.
