# Android setup

## Prerequisites

- A current stable Android Studio release from the official Android Developers
  download page, or JDK 17+ with an Android Gradle environment. API 37 needs a
  current Android toolchain.
- Android SDK Platform 37, Android SDK Build-Tools 36.0.0, and a device running
  Android API 26 or newer.
- A physical Android phone with a rear camera for streaming validation.
- The phone and Linux receiver reachable on the same Wi-Fi or USB-tethered network.

The project pins RootEncoder `2.8.0` and CameraX `1.6.1` in
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

The project uses AGP 9.3.1, Gradle 9.6.1, compile SDK 37, Kotlin 2.2.10,
Activity 1.13.0, Lifecycle 2.11.0, Navigation 3 1.1.4, Hilt 2.60.1, and KSP
2.2.10-2.0.2. Java and Kotlin bytecode target JVM 17 in both Android modules;
the Gradle settings enable automatic JDK 17 toolchain resolution. The first
build downloads Gradle, the JDK toolchain, AndroidX, Compose, Ktor, and
RootEncoder dependencies. No microphone permission is requested.

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

The preview uses the selected profile's aspect ratio. In portrait display
orientation the preview layout uses the profile dimensions transposed for the
surface, while the encoded stream keeps the negotiated profile width and
height. The receiver therefore receives the same profile aspect ratio instead
of a stretched portrait surface.

The Android screen is preview-first. Before a receiver starts a negotiated
session, it shows a waiting state. During a session, the preview is fitted into
the available portrait or landscape window with black letterbox space instead
of stretching or creating a screen-level scroll container. Settings open in a
Material 3 settings screen with a top app bar and list rows, so codec/profile
defaults, camera capability status, full zoom, connection
details, and diagnostics are not permanently visible or constrained by
landscape bottom-sheet geometry.

While streaming, pinch gestures and a compact zoom tray use a slider bounded by
the active camera's reported zoom range. The settings screen also exposes the
full slider and a reset action that returns to 1x. Zoom changes update the
existing RootEncoder `CameraXSource` camera control and do not renegotiate or
restart the stream. The screen dim action applies a reversible UI scrim and
does not change Android window brightness.

RootEncoder 2.8.0's public `CameraXSource` API does not expose logical
multi-camera physical IDs or video stabilization controls. The app therefore
keeps those settings in the presentation model but reports them as unsupported
when running through the supported RootEncoder source. No direct Camera2
fallback or second camera session is opened. A future physical-lens or
stabilization implementation needs an explicit RootEncoder-compatible adapter
decision and device validation.

## Permissions and background behavior

The app requests camera permission only. It exposes a local sender-control
service while the activity is open and uses a remembered pairing token to
prevent automatic activation by an unapproved desktop. It keeps the setup
screen awake so the process remains available. It uses the camera foreground-service
type while streaming and keeps an ongoing notification. Activity recreation
uses the application-scoped session and camera controller, detaches the old
preview surface, and attaches the new one without starting a second encoder.
Minimizing or locking the app has the same behavior because preview surface
visibility is independent from foreground media ownership. A process kill
cannot preserve an active hardware encoder session;
restart the stream after reopening the app.
