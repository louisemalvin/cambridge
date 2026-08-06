# Android sender setup

This document covers the current Android platform adapter. The portable
session and wire contract is documented in [the baseline contract](../contract.md).

Install Android Studio or an equivalent JDK 17 Android build environment. The
project compiles against Android API 37 and targets API 35.

From the repository root:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew \
  -p android testDebugUnitTest lint assembleDebug --console=plain
```

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

The sender presents one configured OBS computer from
[`protocol/direct-stream-deployment.json`](../../protocol/direct-stream-deployment.json).
If no computer is configured, the pairing screen leads to Stream setup. The
setup screen lets the user choose the supported quality and one of four
explicit orientations—Landscape, Landscape (Reversed), Portrait, or Portrait
(Reversed)—before pressing Start stream. Once streaming starts, the selected
orientation is locked and cannot change until Stop releases
the camera and keeps the configured computer for the next setup. Removing the
app task also stops the active stream; the foreground notification remains the
explicit in-session Stop control.

## Required runtime test target

Create or use the AVD named exactly `codex-phone-webcam-api35`. The canonical
runtime check starts it with a deterministic video-file camera and uses the
explicit serial `emulator-5556`:

```bash
./scripts/android/test-emulator-direct-webcam.sh
```

The harness uses `10.0.2.2` as the emulator host endpoint and explicitly
selects the test-only `720p30` profile because this AVD does not advertise the
normal 2K encoder size. It can exercise all four session orientations with
`DIRECT_WEBCAM_ROTATION_DEGREES=0`, `90`, `180`, or `270`.
