# Testing

## Fast checks

```bash
./scripts/development/check-all.sh
```

This runs Android unit tests, lint, debug APK assembly, native CMake tests, and
the native plugin build. It begins with the contract parity check, which
validates protocol version, ports, geometry limits, default profile, and typed
Kotlin/C++ profile values against the JSON contract.

## Native checks

```bash
./scripts/linux/build-direct-webcam-plugin.sh
ldd -r build/direct-webcam-source/direct-webcam-source.so
```

The CTest suite covers RTP parsing, H.264 single-NAL and FU-A packetization,
bounded reorder behavior, control framing, strict JSON fields, and identity
validation. `ldd -r` should report no unresolved symbols.

## Android checks

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew \
  -p android testDebugUnitTest lint assembleDebug --console=plain
```

The unit suite covers direct control framing, RTP packetization, the fixed 2K
normal profile, single-attempt connection behavior, network wakeup, generation
changes, cancellation, explicit start/stop transitions, camera orientation,
and failure diagnostics.

## End-to-end emulator check

```bash
./scripts/android/test-emulator-direct-webcam.sh
```

The check uses only `codex-phone-webcam-api35` and an explicit emulator serial.
It verifies Android `stream_started`, RTP access-unit transmission, native
session acceptance, FFmpeg decoder readiness, first-frame publication, and
`dma_buf_direct` or `cpu_nv12_upload` rendering. Logs are retained under a
temporary `build/direct-webcam-avd.*` directory printed by the script.
The harness explicitly injects `720p30` for this AVD, opens the stream setup
screen, exercises the selected display orientation, presses Start stream once,
and checks lifecycle release. A lost OBS connection is not retried by the app;
the next session requires another explicit Start stream action.

## Native receiver checks

These checks exercise the native OBS source with a contract-conformant H.264
fixture. They do not replace the Android emulator check.

```bash
DIRECT_WEBCAM_PROFILE_ID=2k30 \
DIRECT_WEBCAM_DURATION_SECONDS=30 \
bash scripts/linux/test-direct-webcam-fixture.sh
```

To verify the bounded CPU fallback in the same plugin:

```bash
DIRECT_WEBCAM_PROFILE_ID=2k30 \
DIRECT_WEBCAM_DECODER_MODE=cpu \
DIRECT_WEBCAM_DURATION_SECONDS=30 \
bash scripts/linux/test-direct-webcam-fixture.sh
```

Run the native fixture with portrait geometry metadata using
`DIRECT_WEBCAM_ROTATION_DEGREES=90`. The fixture keeps the coded frame
landscape and checks the native display dimensions and rotation.

Set `DIRECT_WEBCAM_CAPTURE_OUTPUT=1` to save an isolated OBS MP4 and changing
frame hashes under the printed artifact directory. The fixture output is
receiver evidence, not Android Camera2 evidence.

The AVD currently rejects the normal 2K30 profile with `NoCompatibleCodec`.
The Android harness explicitly injects its test-only 720p30 profile and does
not treat that profile as a product quality.

## Endurance gate

The release-level endurance gate is a separate one-hour native fixture run at
2k30. Compare queue peaks, mailbox replacements, decoder drops, RSS, and thread
count at the start and end. A short native fixture run and the emulator smoke
check are sufficient for routine development; they do not claim the one-hour
gate.
