# Android setup

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
[`protocol/direct-stream-deployment.json`](../protocol/direct-stream-deployment.json).
Press Connect to start the fixed 2K30 stream. The operating system owns route
selection. The session captures the current Portrait or Landscape orientation;
rotate the phone only between sessions. Stop releases the camera and keeps the
configured computer for the next Connect.

## Required runtime test target

Create or use the AVD named exactly `codex-phone-webcam-api35`. The canonical
runtime check starts it with a deterministic video-file camera and uses the
explicit serial `emulator-5556`:

```bash
./scripts/android/test-emulator-direct-webcam.sh
```

The harness uses `10.0.2.2` as the emulator host endpoint and explicitly
selects the test-only `720p30` profile because this AVD does not advertise the
normal 2K encoder size. It can exercise a portrait or landscape session with
`DIRECT_WEBCAM_ROTATION_DEGREES=0` or `90`, and checks an OBS restart by
default. Do not run the test against a physical phone. Physical camera and
glass-to-glass validation is a separate deferred gate.
