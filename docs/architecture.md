# Architecture

The project has an Android sender, an iOS protocol adapter, and one reusable
Rust receiver. Media production is platform-specific, while the encoded media
transport is platform-agnostic:

```text
Android or iOS H.264 encoder ─> MPEG-TS over encrypted SRT caller
  -> GStreamer srtsrc listener, tsparse, tsdemux, codec parser, decodebin
  -> videoconvert -> tee
       -> videoscale -> videorate -> fixed output caps -> bounded queue
          -> Linux v4l2sink -> v4l2loopback -> OBS, browser, or video-call app
       -> bounded queue -> RGBA conversion -> desktop preview window
```

The sender calls the receiver's versioned HTTP/JSON API on TCP port `5001` using
an automatically discovered receiver origin or the manual-origin fallback. Session
creation returns the receiver-owned SRT listener on port `5000` (SRT uses UDP
transport), an opaque stream ID, and a per-session AES-256 passphrase. The
receiver never infers the sender address and does not need a phone-hosted
control listener.

The normative media contract is the encrypted SRT section of
[`docs/protocol.md`](protocol.md) and the typed v2 schema. It defines MPEG-TS
alignment, H.264/H.265 compatibility, per-session credentials, timestamp and
restart semantics. The receiver does not inspect whether a stream was produced
by Android or iOS.

## Android boundaries

The app is organized into feature presentation packages and grouped infrastructure modules:

```text
feature/ (connection, webcam, settings)
  <- UiState / SenderUiEffect
  -> SenderScreenAction
destination ViewModels (PairingViewModel, WebcamViewModel, SettingsViewModel)
  -> pure state mappers
  -> connection/ (receiver discovery, manual fallback, and control)
  -> session/ (StreamSessionController, CodecNegotiator, VideoProfiles)
       -> connection/control/http/ (HttpReceiverControlClient)
       -> media/capabilities/ (MediaCodecCapabilityProbe)
       -> media/streaming/ (StreamEngine, CodecNegotiator)
            -> media/streaming/rootencoder/ (RootEncoderStreamEngine,
                 RootEncoderCameraSourceFactory -> one Camera2Source)
  -> platform/ (service, notification, power)
  -> platform/preferences/ (SenderSettingsStore)
```

`app/di/` defines Hilt modules (`ApplicationModule`, `MediaModule`, `ConnectionModule`) for dependency injection. `connection/control/http/` owns Ktor and JSON DTOs. `media/capabilities/mediacodec/` owns Android codec discovery. Only `media/streaming/rootencoder/` imports RootEncoder types. The session controller owns validation, negotiation, preparation, lifecycle serialization, cleanup, and typed failures.

The current Android session and preview contracts remain inside the Android
application. Android framework types such as `Context`, `Surface`, and
`MediaFormat` are not part of the wire contract; codec MIME mapping is kept in
the Android MediaCodec capability package. Further extraction is incremental
and must improve the active Android path rather than reorganise the repository.

Each destination composable consumes only its immutable presentation model.
`PairingViewModel`, `WebcamViewModel`, and `SettingsViewModel` own their
destination state and map coordinator or camera-controller flows through pure
screen-state mappers. `SenderScreenAction` remains the UI intent boundary.
One-shot Android work such as requesting camera permission or copying
diagnostics is exposed as `SenderUiEffect`; composables do not access the
coordinator, RootEncoder, or camera controller directly. Navigation 3 entry
decorators scope each destination ViewModel to its back-stack entry while the
application-scoped session and stream owner remains shared.

`SenderSettingsRepository` is the single source for codec, profile, and
receiver connection defaults. The Android preferences adapter persists those
values and encrypts the receiver bearer token with an Android Keystore AES-GCM
key. The coordinator reads one settings snapshot when a receiver session
starts. The connection layer first browses for the receiver's
`_mobile-webcam._tcp` service, stores the selected origin, and keeps manual
origin entry available when local-network discovery cannot operate.

## iOS development boundary

`ios/` contains a native SwiftUI/Xcode application skeleton. Its receiver
client implements the v2 HTTP DTO and transport boundary, while
`IOSMediaEngine` still stops before camera capture, MPEG-TS packetization, and
native SRT integration. Runtime iOS support is not claimed until the adapter
builds and passes the same file-source conformance suite on macOS.

Kotlin Multiplatform is optional future infrastructure for low-throughput
control/session behavior such as DTOs, receiver-origin handling, negotiation, retries,
configuration, and coarse events. It must not sit in the per-frame media path
or carry native camera frames, encoded buffers, MPEG-TS packets, or platform
media objects.

The foreground service owns notification lifetime and receives the Stop
action. It does not create a second stream engine. The application-scoped
session controller remains the single owner of the stream.

The Android camera interaction boundary is separate from session negotiation:

```text
Compose preview and zoom controls
  -> CameraController state/events
  -> RootEncoderStreamEngine
       -> one RootEncoder Camera2Source
       -> attach/detach preview surface without stream restart
```

`VideoProfile.width` and `VideoProfile.height` remain the encoded output
dimensions used by the receiver. The Compose preview derives its aspect ratio
from those dimensions and the current display orientation, swapping the
dimensions for portrait layout. RootEncoder's preview renderer receives the
surface dimensions and orientation independently, while the encoder keeps the
negotiated profile dimensions so the receiver's fixed output caps preserve the
same aspect ratio.

Zoom is applied through RootEncoder `Camera2Source.setZoom(Float)` using the
camera-reported `getZoomRange()`. Camera2 lifecycle operations are dispatched
to the Android main dispatcher because RootEncoder's `Camera2Source` owns the
camera manager and capture session. The zoom state and reset action are coarse
UI state; camera frames and Android camera objects do not cross the session or
wire contracts. A destroyed preview surface detaches only the GL preview. The
foreground service and application-scoped stream engine continue the media
session, and a later surface can attach to the existing camera source. Physical
lens selection reopens that same source with the selected vendor physical ID,
so it does not create a second capture pipeline or renegotiate the session.

## Rust crate boundaries

| Crate | Responsibility |
| --- | --- |
| `receiver-protocol` | Versioned DTOs, protocol enums, Serde mapping, fixture tests |
| `receiver-core` | Configuration, negotiation, session state, service coordination |
| `receiver-control-http` | Axum routes, request mapping, JSON errors, watchdog |
| `receiver-gstreamer` | GStreamer initialization, pipeline construction, bus and pad handling |
| `receiver-platform-linux` | v4l2loopback validation and `v4l2sink` creation |
| `receiver-cli` | Arguments, logging, composition, shutdown, status output |
| `receiver-desktop` | GTK desktop composition, preview window, and Linux runtime |

The core crate has no GStreamer, Axum, CLI, or Linux-device dependency. The
GStreamer crate has no HTTP routing. Its `VideoSinkFactory` supports one
required output and one optional bounded preview output. The Linux crate owns
the final virtual-camera backend, while the desktop app owns only the GTK
window and RGBA frame store. A future Windows or macOS backend can replace the
virtual-camera factory without changing negotiation or media reception.

## Latency and recovery policy

`tsdemux` latency starts at zero. Compressed video remains lossless through
parse and decode. The final raw-video queue is bounded to two buffers with
downstream leaky behavior. When output is slower than the
incoming stream, older frames are discarded and the newest frames remain
available. No application channel is unbounded.

`srtsrc` reports transport loss after the configured inactivity timeout. The
receiver changes the session to `reconnecting`, keeps the persistent v4l2 writer
alive in standby, and returns to `receiving` when the same caller reconnects.
A longer configurable grace period releases an abandoned session so a later
sender can prepare a new one. The HTTP server polls the GStreamer bus and
applies this policy without requiring a status request.

## Security and deferred work

The initial release is for a trusted local network. v2 control supports an
optional bearer token and SRT media uses a per-session passphrase and stream ID.
Audio, cloud relays, WebRTC, adaptive bitrate, and non-Linux virtual-camera
backends remain deferred.
