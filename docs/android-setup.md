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
[`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml). The
RootEncoder dependency is isolated to `streaming/rootencoder/`; the rest of the
app uses the project-owned `StreamEngine` interface.

## Build and install

From the repository root, use the checked-in Gradle wrapper:

```bash
android/gradlew -p android test lint assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The project uses AGP 9.1.1, Gradle 9.5.0, compile SDK 37, and JDK 17. The first
build downloads Gradle, AndroidX, Compose, Ktor, and RootEncoder dependencies.
No microphone permission is requested.

## Connect

1. Open Mobile Webcam on the phone and grant camera permission.
2. Start `mobile-webcam-desktop` on Linux.
3. If this is the only available phone, the desktop selects it automatically.
   If several phones are available, select one in the desktop window.
4. Approve the desktop on the phone the first time.
5. Leave Codec mode at Auto - prefer H.265 and start with `1080p30`.
6. Confirm the selected codec in the streaming screen.

The media stream is sent to the receiver's session-specific UDP port in
`50000-50099`. The Android foreground notification has a Stop action. Camera,
encoder, preview, notification, wake-lock, and Wi-Fi-lock resources are
released when the session stops or fails.

## Permissions and background behavior

The app requests camera permission only. It exposes a local sender-control
service while the activity is open and uses a remembered pairing token to
prevent automatic activation by an unapproved desktop. It keeps the setup
screen awake so the process remains available. It uses the camera foreground-service
type while streaming and keeps an ongoing notification. Activity recreation
uses the application-scoped session controller and does not start a second
encoder. A process kill cannot preserve an active hardware encoder session;
restart the stream after reopening the app.
