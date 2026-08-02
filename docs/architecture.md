# Architecture

Phase 1 has one Android sender and one reusable Rust receiver. The media path
is intentionally simple and local:

```text
Android camera
  -> MediaCodec hardware H.264 or H.265 encoder through RootEncoder
  -> MPEG-TS muxing
  -> UDP unicast
  -> GStreamer udpsrc, tsparse, tsdemux, codec parser, decodebin
  -> videoconvert -> tee
       -> videoscale -> videorate -> fixed output caps -> bounded queue
          -> Linux v4l2sink -> v4l2loopback -> OBS, browser, or video-call app
       -> bounded queue -> RGBA conversion -> desktop preview window
```

The phone exposes a versioned control service on TCP port `53555`. The desktop
probes bounded local IPv4 subnets with a side-effect-free describe request,
then uses the selected phone for paired reverse control. The phone infers the
desktop address from the TCP peer and calls the receiver's HTTP/JSON API on TCP
port `5001`. Session preparation allocates a new UDP media port from the
application-owned range `50000-50099`. Only the selected phone receives that
port, which keeps host firewall policy narrow without returning to a shared
fixed media socket.

## Android boundaries

The app is one Gradle module with these dependency directions:

```text
Compose UI
  -> SenderViewModel
  -> SenderConnectionCoordinator
       -> pairing store and reverse-control service
  -> StreamSessionController
       -> ReceiverControlClient
       -> EncoderCapabilityProbe
       -> CodecNegotiator
       -> StreamEngine
            -> RootEncoder adapter
```

`model/` contains project-owned enums and typed state. `control/http/` owns
Ktor and JSON DTOs. `capabilities/mediacodec/` owns Android codec discovery.
Only `streaming/rootencoder/` imports RootEncoder types. The session
controller owns validation, negotiation, preparation, lifecycle serialization,
cleanup, and typed failures.

The foreground service owns notification lifetime and receives the Stop
action. It does not create a second stream engine. The application-scoped
session controller remains the single owner of the stream.

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

Phase 1 is for a trusted local network. First use requires approval on Android,
and the sender stores a token scoped to stable phone and desktop IDs. HTTP
receiver control and UDP media remain unencrypted. Audio, cloud relays, WebRTC,
SRT, Tauri, and non-Linux virtual-camera backends are deferred.
