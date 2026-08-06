# Architecture

The supported path has one sender, one logical computer, one native OBS source,
one active session, and two sockets:

```text
Camera2
  -> MediaCodec H.264 input Surface
  -> bounded encoded-access-unit queue
  -> RFC 6184 RTP packetizer
  -> UDP unicast
  -> bounded RTP reorder and access-unit assembler
  -> FFmpeg/libavcodec H.264 decoder
  -> VAAPI DRM PRIME frame or software NV12 frame
  -> one-slot newest-frame mailbox
  -> finite OBS texture pool
```

The TCP control socket uses length-prefixed JSON. It validates the protocol
version, session ID, generation, H.264 geometry, and media port before
accepting RTP.
The UDP receiver validates the source address learned from the control
connection and ignores packets from other senders.

The Android app stores one configured computer endpoint. It opens that endpoint
and leaves route selection to the operating system. The endpoint lives in the
deployment JSON and the media port derives from the protocol contract.

## Ownership boundaries

- Android `Camera2Capture` owns camera devices, capture sessions, and the
  encoder input surface.
- `DirectRtpStreamEngine` owns MediaCodec, RTP packetization, the UDP socket,
  and stream lifecycle.
- The native control server owns session identity and control messages.
- The native media receiver owns UDP reads, RTP ordering, and access-unit
  assembly.
- The decoder owns FFmpeg and the VAAPI device. It exposes complete decoded
  frames only.
- The renderer owns libobs graphics objects. It imports DRM PRIME DMA-BUF
  descriptors directly when possible and otherwise uploads NV12 through one
  dynamic texture.

The sender always encodes the selected coded profile dimensions. For portrait,
the native source reports the swapped display dimensions and rotates texture
coordinates in one NV12 shader for both Y and UV sampling. The OBS scene uses
centered aspect-fit bounds on the 2560x1440 verification canvas, so source
geometry can change without a fixed scale assumption.

No Android framework object crosses into native code. No libobs object crosses
into the decoder or network code.

## Bounded work

- Android retains at most `MAXIMUM_ENCODED_QUEUE` newest access units.
- RTP reordering has a packet-count bound and a deadline.
- Access-unit bytes and access-unit count are capped before decode.
- Decoder input drops the oldest queued unit when full.
- The frame mailbox replaces an unpublished frame with the newest one.
- OBS presentation uses a finite texture pool and renders a placeholder when
  the newest frame is stale.

When a bound is hit, the source drops media and reports the event. It does not
request retransmission or an IDR, allocate a larger queue, or block the
graphics thread.

## Lifecycle

1. The user opens Stream setup for the configured OBS computer.
2. The user selects the supported quality and portrait or landscape axis. The
   setup screen remains in the phone's current orientation.
3. On Start stream, Android snapshots the camera transform, creates one
   session ID and generation, validates the selected profile, and sends
   protocol v3 `hello` over TCP.
4. The native source accepts the matching hello, starts the decoder, and
   returns the media port.
5. Android starts Camera2 and MediaCodec, then sends RTP/H.264. Once the
   session is streaming, the activity locks to the selected axis and permits a
   180-degree reverse.
6. Stop, control disconnect, or invalid generation ends the session and clears
   the mailbox. A lost session releases stale media resources and waits for a
   new explicit Start stream. Removing the Android app task also stops the
   active session through the foreground service.

The source never creates a virtual camera device. OBS consumes the source
directly as a native texture source.
