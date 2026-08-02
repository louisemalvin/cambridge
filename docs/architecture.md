# Architecture

Phase 1 has an Android sender, an iOS development skeleton, and one reusable
Rust receiver. Media production is platform-specific, while the encoded media
transport is platform-agnostic:

```text
Android camera/encoder ─┐
                        ├─> MPEG-TS over UDP unicast
iOS camera/encoder ────┘
  -> GStreamer udpsrc, tsparse, tsdemux, codec parser, decodebin
  -> videoconvert -> tee
       -> videoscale -> videorate -> fixed output caps -> bounded queue
          -> Linux v4l2sink -> v4l2loopback -> OBS, browser, or video-call app
       -> bounded queue -> RGBA conversion -> desktop preview window
```

The sender exposes a versioned control service on TCP port `53555`. The desktop
probes bounded local IPv4 subnets with a side-effect-free describe request,
then uses the selected sender for paired reverse control. The sender infers the
desktop address from the TCP peer and calls the receiver's HTTP/JSON API on TCP
port `5001`. Session preparation allocates a new UDP media port from the
application-owned range `50000-50099`. Only the selected sender receives that
port, which keeps host firewall policy narrow without returning to a shared
fixed media socket.

The normative media contract is
[`media-transport-v1.md`](media-transport-v1.md). It defines MPEG-TS packet
alignment, H.264/H.265 compatibility, timestamp and restart semantics, and
stream epochs. The receiver does not inspect whether a stream was produced by
Android or iOS.

## Android boundaries

The app is organized into feature presentation packages and grouped infrastructure modules:

```text
feature/ (pairing, webcam, settings)
  <- UiState / SenderUiEffect
  -> SenderScreenAction
destination ViewModels (PairingViewModel, WebcamViewModel, SettingsViewModel)
  -> pure state mappers
  -> connection/discovery/ (SenderConnectionCoordinator, PairingStore, SenderControlServer)
  -> media/streaming/session/ (StreamSessionController)
       -> connection/control/http/ (HttpReceiverControlClient)
       -> media/capabilities/ (MediaCodecCapabilityProbe)
       -> media/streaming/ (StreamEngine, CodecNegotiator)
            -> media/streaming/rootencoder/ (RootEncoderStreamEngine)
                 -> media/camera/ (CameraXSource via RootEncoderCameraSourceFactory)
  -> platform/ (service, notification, power)
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

## iOS development boundary

`ios/` contains a native SwiftUI/Xcode application skeleton. Its
`IOSMediaEngine` boundary owns the future AVFoundation, VideoToolbox, MPEG-TS,
and UDP implementation, while the session, discovery, pairing, and receiver
control files expose only coarse models and integration points. The current
stub deliberately stops before camera capture or per-frame processing.

Kotlin Multiplatform is optional future infrastructure for low-throughput
control/session behavior such as DTOs, pairing, negotiation, retries,
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
       -> one RootEncoder CameraXSource
       -> attach/detach preview surface without stream restart
```

`VideoProfile.width` and `VideoProfile.height` remain the encoded output
dimensions used by the receiver. The Compose preview derives its aspect ratio
from those dimensions and the current display orientation, swapping the
dimensions for portrait layout. RootEncoder's preview renderer receives the
surface dimensions and orientation independently, while the encoder keeps the
negotiated profile dimensions so the receiver's fixed output caps preserve the
same aspect ratio.

Zoom is applied through RootEncoder `CameraXSource.setZoom(Float)` using the
camera-reported `getZoomRange()`. CameraX lifecycle operations are dispatched
to the Android main dispatcher because RootEncoder's `CameraXSource` owns a
`LifecycleRegistry` and binds its single preview use case there. The zoom state
and reset action are coarse UI state; camera frames and Android camera objects
do not cross the session or wire contracts. A destroyed preview surface
detaches only the GL preview. The foreground service and application-scoped
stream engine continue the media session, and a later surface can attach to the
existing camera source. RootEncoder's supported CameraX API currently does not
provide physical-camera selection or video stabilization controls, so those
capabilities remain explicitly unsupported rather than using a second capture
path.

## Rust crate boundaries

| Crate | Responsibility |
| --- | --- |
| `receiver-protocol` | Versioned DTOs, protocol enums, Serde mapping, fixture tests |
| `receiver-core` | Configuration, negotiation, session state, service coordination |
| `receiver-control-http` | Axum routes, request mapping, JSON errors, watchdog |
| `receiver-gstreamer` | GStreamer initialization, pipeline construction, bus and pad handling |
| `receiver-platform-linux` | v4l2loopback validation and `v4l2sink` creation |
| `sender-control-protocol` | Phone discovery and paired reverse-control DTOs |
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

`udpsrc` reports a timeout after two seconds without packets. The receiver
changes the session to `timed_out`, keeps the process and pipeline alive, and
can return to `receiving` when frames arrive again. A longer configurable
grace period releases an abandoned session so a later sender can prepare a
new one. The HTTP server runs a small watchdog to poll the GStreamer bus and
apply this policy without requiring a status request.

## Security and deferred work

Phase 1 is for a trusted local network. First use requires approval on the
mobile sender, and the sender stores a token scoped to stable sender and
desktop IDs. HTTP
receiver control and UDP media remain unencrypted. Audio, cloud relays, WebRTC,
SRT, Tauri, and non-Linux virtual-camera backends are deferred.
