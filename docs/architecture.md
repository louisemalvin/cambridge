# CamBridge Architecture

CamBridge has an Android sender and a native Linux OBS receiver. A session is
started explicitly by the user and the receiver handles one active session at
a time.

## Data flow

```text
Android camera
    → Camera2 capture
    → MediaCodec H.264 encoder
    → RTP/H.264 over UDP
    → OBS source on Linux
    → FFmpeg H.264 decoder
    → VAAPI/DRM PRIME and DMA-BUF when available
    → OBS texture
```

The receiver falls back to software decoding and a bounded NV12 texture upload
when hardware decoding or direct DMA-BUF import is unavailable.

The iOS sender follows the same wire path and does not add a second receiver
or transport:

```text
iPhone AVFoundation camera
    → VideoToolbox hardware H.264
    → bounded newest-access-unit queue
    → RFC 6184 RTP/H.264 over UDP
    → unchanged CamBridge OBS source
```

## Components

### Android sender

The Android app owns camera discovery, preview, camera controls, capture
surfaces, H.264 encoding, receiver discovery, and stream lifecycle. The
session coordinator validates the selected phone mode, bitrate, and orientation
before starting the camera and encoder. The selected session settings stay
fixed until the user stops the stream.

Receiver discovery is isolated in the Android `:receiver-discovery` library.
It runs for the Stream Setup lifecycle, retains every IPv4 address resolved for
each DNS-SD service, combines them with the receiver's bounded IPv4 unicast
address metadata, and removes addresses when the service is lost. The
app coordinator probes those addresses and deduplicates successful responses
by receiver ID; discovery itself never establishes readiness or starts a
stream.

### iOS sender candidate

The iOS project is under `sender/ios/` and targets iOS 17 or later. Its
platform-neutral `CamBridgeCore` package owns the generated v6 contract,
control framing, H.264 normalization, RTP packetization, session state, and
bounded queue policy. The app target owns AVFoundation, VideoToolbox,
Network.framework, SwiftUI, persistence, and diagnostics.

The iOS sender uses exact AVFoundation formats and a temporary hardware
VideoToolbox probe before offering a shared sender mode. It keeps coded video
geometry separate from the four clockwise wire rotations; preview orientation
is configured independently. Bonjour TXT addresses are candidates only: each
selected receiver is probed over TCP before Start, and the accepted media port
is used for the connected UDP path.

### Control and media transport

DNS-SD proposes receiver control endpoints. The control plane then uses
length-prefixed UTF-8 JSON over TCP for capability probing, session setup, and
stop messages. The media plane carries H.264 access units as RFC 6184 RTP
packets over UDP.

The two planes share a session ID and generation. The receiver rejects stale
or incompatible sessions before presenting their frames.

### Linux OBS receiver

The CamBridge OBS source starts the control and media listeners when an OBS
source is created. It validates the session, reorders a bounded number of RTP
packets, assembles H.264 access units, and passes them to FFmpeg. Decoded
frames are presented through the OBS graphics API.

VAAPI with a DRM render node is preferred. When the host cannot provide a
usable hardware path, the decoder produces software frames and the renderer
uploads NV12 data to an OBS texture.

## Ownership boundaries

- Android owns Camera2 and MediaCodec; it does not depend on OBS APIs.
- iOS owns AVFoundation and VideoToolbox; it does not depend on OBS APIs or
  the POSIX interoperability fixture.
- The protocol layer owns message framing, session identity, wire bounds, and
  RTP/H.264 rules; the shared sender catalog owns phone mode definitions.
- The receiver owns network input, decoding, frame lifetime, and presentation
  scheduling.
- OBS integration owns source properties, graphics resources, and the output
  texture.

## Latency and buffering

The media path is intentionally bounded:

```text
camera → encoder queue → RTP sender
                         ↓
receiver UDP → reorder window → decoder queue → newest-frame mailbox → OBS
```

Late packets, incomplete access units, and stale decoded frames are dropped.
The presentation mailbox keeps the newest frame rather than allowing backlog
to grow. There is no media retransmission or automatic reconnect; after a
terminal session failure, the user starts another session explicitly.
