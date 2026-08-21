# CamBridge architecture

CamBridge connects one Android camera sender to one native OBS receiver. The
user owns one explicit Start and Stop lifecycle, and the receiver owns one
active session. The supported Phase 1 path is Android to Linux OBS.

## Data flow

```text
Android Camera2
    -> MediaCodec H.264 access units
    -> GStreamer appsrc -> h264parse -> rtph264pay -> rtpbin
    -> RTP/RTCP UDP
    -> receiver rtpbin jitter buffer and RTX -> H.264 depay/parser/appsink
    -> FFmpeg H.264 decoder
    -> newest-frame mailbox
    -> OBS texture
```

The Android transport keeps media ownership in GStreamer. The sender's
application code only supplies normalized Annex-B access units and responds to
GStreamer bitrate and keyframe callbacks. `rtprtxsend` and `rtpgccbwe` handle
retransmission and congestion estimation. The receiver sends RTCP feedback to
the sender and passes only complete H.264 access units to FFmpeg.

The receiver selects its presentation path once per session:

```text
Linux native:  FFmpeg -> VAAPI -> DRM PRIME -> DMA-BUF -> OBS NV12 textures
Linux software: FFmpeg software H.264 -> CPU NV12 -> bounded OBS upload
macOS candidate: FFmpeg -> VideoToolbox -> Metal/IOSurface presentation
```

After the path is locked, decode, conversion, import, and upload failures end
the session. The receiver does not silently switch paths or reconnect.

## Android sender

The Android app owns camera discovery, preview, Camera2 capture, MediaCodec
configuration, receiver discovery, control TCP, and stream lifecycle.
`CamBridgeStreamEngine` prepares the encoder before connecting the media
transport. It uses a bounded `ArrayBlockingQueue` containing at most two
access units. When the queue is full, it requests a sync frame, drops the
current backlog, waits for the next keyframe, and resumes with that keyframe.

The transport worker takes one access unit and calls GStreamer. It does not
packetize RTP, pace datagrams, open UDP sockets, or sleep for rate control.

`GStreamerRuntime` initializes the official GStreamer Android SDK once. The
JNI bridge owns the native sender and a Java global reference for callbacks.
Callbacks are suppressed during destruction, and native pipeline errors stop
the pipeline before they are reported to the session controller.

## Control and media transport

DNS-SD proposes receiver control endpoints. The control plane uses
length-prefixed UTF-8 JSON over TCP for capability probing, session setup, and
stop messages. The media plane uses RFC 6184 H.264 RTP with a separate RTCP
socket. RTP payload type 96, RTX payload type 97, a 90 kHz clock, MTU 1200, and
TWCC extension ID 1 are shared contract values.

The sender and receiver use the AVPF RTP profile. The sender advertises H.264
NACK/PLI and FIR feedback, stores RTX history for 150 ms, and publishes GCC
estimates. The receiver uses a 40 ms jitter buffer with retransmission and
loss events enabled. Recoverable packet loss is repaired through NACK/RTX.
Loss outside the retransmission window causes a keyframe request and the
receiver waits for a clean IDR before presenting more frames.

## Linux OBS receiver

The CamBridge OBS source starts the control listener and GStreamer runtime when
the source is created. A hello prepares the FFmpeg decoder and starts one
GStreamer receiver session with the sender's RTCP endpoint. The receiver
pipeline is:

```text
udpsrc RTP -> rtpbin -> rtph264depay -> h264parse -> appsink
udpsrc RTCP -> rtpbin -> udpsink back to Android
```

`rtprtxreceive` is supplied to `rtpbin` for payload mapping 96 to 97. The
appsink is synchronized off, bounded to one buffer, and applies backpressure
instead of dropping encoded access units. It copies Annex-B data, attaches the
RTP timestamp and monotonic receive time, and submits the access unit to the
FFmpeg decoder.

VAAPI with a DRM render node is preferred. When the host cannot provide a
usable hardware path, the decoder produces software NV12 frames and the
renderer uploads them to an OBS texture.

## Ownership boundaries

- Android owns Camera2 and MediaCodec. GStreamer owns Android media transport.
- The protocol owns control framing, session identity, wire bounds, RTP values,
  and RTCP port roles.
- The receiver owns GStreamer input, FFmpeg decoding, frame lifetime, and
  presentation scheduling.
- OBS integration owns source properties, graphics resources, and the output
  texture.
- The iOS sender and macOS receiver remain deferred candidates. Their current
  platform code is not part of the Phase 1 release acceptance path.

## Latency and buffering

```text
camera -> MediaCodec -> one-access-unit sender queue -> blocking appsrc -> GStreamer RTP
receiver GStreamer jitter/RTX -> appsink -> one-access-unit FFmpeg queue -> newest-frame mailbox -> OBS
```

The sender handoff, appsrc, appsink, and decoder queue each hold at most one
encoded access unit. GStreamer's 40 ms receiver latency budget is the only
playout policy. While a session is active, OBS holds the latest valid decoded
frame until a replacement arrives. Session end or terminal failure clears the
mailbox and requires the user to start another session explicitly.
