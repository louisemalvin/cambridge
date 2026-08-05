# Android setup

## Prerequisites

- A current stable Android Studio release from the official Android Developers
  download page, or JDK 17+ with an Android Gradle environment. API 37 needs a
  current Android toolchain.
- Android SDK Platform 37, Android SDK Build-Tools 36.0.0, and a device running
  Android API 26 or newer.
- A physical Android phone with a rear camera for streaming validation.
- The phone and Linux receiver reachable on the same Wi-Fi or USB-tethered network.

The project pins RootEncoder `2.8.0` in
[`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml).
The RootEncoder dependency is isolated to `media/streaming/rootencoder/`; the
rest of the app uses the project-owned `StreamEngine` interface. Compose
dependencies use the stable Compose BOM so Material 3 is resolved as a
compatible stable set; the app does not independently pin a Material 3
artifact.

## Build and install

From the repository root, use the checked-in Gradle wrapper:

```bash
android/gradlew -p android test lint assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The project uses AGP 9.3.1, Gradle 9.6.1, compile SDK 37, Kotlin 2.3.21,
Activity 1.13.0, Lifecycle 2.11.0, Navigation 3 1.1.4, Hilt 2.60.1, and KSP
2.3.10. Java and Kotlin bytecode target JVM 17 in both Android modules;
the Gradle settings enable automatic JDK 17 toolchain resolution. The first
build downloads Gradle, the JDK toolchain, AndroidX, Compose, Ktor, and
RootEncoder dependencies. No microphone permission is requested.

## Connect

1. Start `mobile-webcam-desktop` or `mobile-webcam-receiver` on Linux.
2. Open Mobile Webcam on the phone and grant camera permission.
3. Select the receiver discovered on the local network. The app verifies its
   v2 HTTP origin and asks for a bearer token only when the receiver advertises
   authentication. If discovery is unavailable, use the manual receiver-origin
   fallback. The phone enters connected standby and keeps the authenticated
   demand subscription open; it creates the v2 media session only after a
   sustained consumer opens `Mobile Webcam`.
4. Leave Codec mode at H.264 for the first compatibility check and start with
   the receiver-owned `720p30` profile.
5. Open `Mobile Webcam` in OBS or another generic V4L2 consumer. Confirm the
   receiver reaches `receiving`; closing the final consumer returns the sender
   and device to connected standby.

The receiver owns one stable SRT listener port and returns a per-session stream
ID and AES-256 passphrase. The Android foreground notification has a Stop
action. Camera, encoder, preview, notification, wake-lock, and Wi-Fi-lock
resources are released when the session stops or fails.

Codec and profile defaults are owned by the application-scoped
`SenderSettingsRepository` and persisted in the sender settings store, so a
settings change survives activity recreation and is used by the next
negotiated session. The Pairing destination stores the selected receiver
origin, and Settings exposes a Forget receiver action that stops the session,
clears the configured origin, and returns to the connection form.

The RootEncoder adapter serializes camera, preview, and stream teardown through
the single session owner. Its stop path uses one ordered RootEncoder stop
sequence before releasing the Camera2 source; this avoids repeating the
upstream library's GL teardown sequence on the Android emulator.

The preview uses the selected profile's aspect ratio. In portrait display
orientation the preview layout uses the profile dimensions transposed for the
surface, while the encoded stream keeps the negotiated profile width and
height. The receiver therefore receives the same profile aspect ratio instead
of a stretched portrait surface.

The Android screen is preview-first. Before a consumer starts a negotiated
session, it shows connected standby. During a session, the preview is fitted into
the available portrait or landscape window with black letterbox space instead
of stretching or creating a screen-level scroll container. Settings open in a
Material 3 settings screen with a top app bar and list rows, so codec/profile
defaults, camera capability status, full zoom, connection
details, and diagnostics are not permanently visible or constrained by
landscape bottom-sheet geometry.

While streaming, pinch gestures and a compact zoom tray use a slider bounded by
the active camera's reported zoom range. The settings screen also exposes the
full slider and a reset action that returns to 1x. Zoom changes update the
existing RootEncoder `Camera2Source` camera control and do not renegotiate or
restart the stream. The screen dim action applies a reversible UI scrim and
does not change Android window brightness.

RootEncoder 2.8.0's `Camera2Source` exposes physical-camera binding through the
existing `OutputConfiguration.setPhysicalCameraId` path. On Android 9 and
newer, the adapter discovers a logical rear camera and its vendor-provided
physical IDs, then keeps those IDs as the user-visible choices. The IDs are not
portable lens names, so the UI intentionally labels them as physical IDs. The
Vivo V2413 exposes rear logical ID `0` with physical IDs `2`, `3`, and `4`, plus
front logical ID `1`. Stabilization remains unsupported by this adapter. No
second camera session or parallel capture pipeline is opened.

## Permissions and background behavior

The app requests camera permission only. Normal v2 operation does not expose a
phone-side control service. The receiver bearer token is encrypted with an
Android Keystore AES-GCM key. It keeps the setup screen awake so the process
remains available. It uses the camera foreground-service type while streaming
and keeps an ongoing notification. Activity recreation
uses the application-scoped session and camera controller, detaches the old
preview surface, and attaches the new one without starting a second encoder.
Minimizing or locking the app has the same behavior because preview surface
visibility is independent from foreground media ownership. A process kill
cannot preserve an active hardware encoder session;
restart the stream after reopening the app.

## Emulator validation

The deterministic harness is `scripts/android/test-emulator-srt.sh`. It creates
a moving file with FFmpeg, starts `codex-phone-webcam-api35` with
`-camera-back videofile:<path>`, launches the receiver with an emulator-reachable
control origin, installs the exact APK, configures the manual receiver-origin
fallback,
and checks connected standby, demand generations, decoded frames, black standby,
reopen behavior, v4l2 output, and redacted Android logs. Physical-device,
macOS/iOS, and long-duration latency evidence remain separate gates.
