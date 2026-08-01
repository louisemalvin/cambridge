# Android setup

## Prerequisites

- Android Studio or JDK 17 with an Android Gradle environment.
- Android SDK Platform 35 and a device running Android API 26 or newer.
- A physical Android phone with a rear camera for streaming validation.
- The phone and Linux receiver reachable on the same Wi-Fi or USB-tethered network.

The project pins RootEncoder `2.8.0` in
[`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml). The
RootEncoder dependency is isolated to `streaming/rootencoder/`; the rest of the
app uses the project-owned `StreamEngine` interface.

## Build and install

From the repository root, use the Gradle wrapper when one is present. The
current Phase 1 repository relies on the CI and Android Studio Gradle
installation, so a local checkout without a wrapper can use:

```bash
gradle -p android test lint assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads Gradle, AndroidX, Compose, Ktor, and RootEncoder
dependencies. No microphone permission is requested.

## Connect

1. Start the Linux receiver and note its reachable IP address.
2. Grant the app camera permission when prompted.
3. Enter the receiver IPv4 or IPv6 address and control port `5001`.
4. Leave Codec mode at Auto - prefer H.265 for normal negotiation.
5. Start with `1080p30`.
6. Confirm the selected codec in the streaming screen and receiver session API.

The media stream is sent to the receiver's negotiated UDP port, normally
`5000`. The Android foreground notification has a Stop action. Camera,
encoder, preview, notification, wake-lock, and Wi-Fi-lock resources are
released when the session stops or fails.

## Permissions and background behavior

The app requests camera permission only. It uses the camera foreground-service
type while streaming and keeps an ongoing notification. Activity recreation
uses the application-scoped session controller and does not start a second
encoder. A process kill cannot preserve an active hardware encoder session;
restart the stream after reopening the app.
