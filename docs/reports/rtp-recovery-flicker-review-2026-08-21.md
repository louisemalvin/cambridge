# RTP recovery flicker review notes

Status: review-only evidence for the temporary `review/rtp-recovery-flicker`
branch. This is not a claim that the flicker is fixed.

## Test configuration

- CamBridge version: `0.5.1`
- Protocol version: `7`
- Video profile: `2560x1440`, approximately 30 fps, 16 Mbps target
- Receiver decoder path: native VAAPI
- Receiver process: isolated OBS instance with the staged GStreamer runtime
- The network address and session identifiers are intentionally omitted.

## Observed receiver behavior

The receiver remained in `PLAYING` and reported `packetLoss=0`. The appsink
reported zero queued and dropped buffers in the sampled summaries. The same
summaries also showed a large and continuing recovery storm:

- `rtxRequests` grew past `31,000`.
- `rtxPackets`, `rtxAssociatedPackets`, and `rtxRecovered` grew together past
  `13,000`.
- `sessionSentNacks` grew past `36,000`.
- `keyframeRequests` grew past `900`.
- Decoder summaries reached `decoderQueueDrops=7` and
  `staleTransitions=10`.

The Android sender logs showed approximately 30 fps capture with
`appsrcQueuedBuffers=0` and `appsrcDroppedBuffers=0`. That does not indicate a
sender-side appsrc backlog in this run.

## Mitigation currently on this branch

The receiver keeps the last decoded frame visible for one bounded recovery
grace window after its configured live-frame deadline. It still presents the
black placeholder when there is no valid frame, when the stream generation is
old, or after the grace window expires. The policy has focused boundary tests.

The live run still accumulated stale transitions and decoder queue drops, so
this only masks short presentation gaps. It does not explain or eliminate the
underlying recovery behavior.

## Review targets

Start with these paths:

1. `receiver/obs/cambridge-obs-source/src/gstreamer_media_receiver.cpp`
   - `configure_jitterbuffer`
   - `on_depay_event`
   - `make_rtx_receiver`
   - `receiver_summary`
2. The interaction between `rtpbin`, the jitterbuffer, `rtprtxreceive`, and the
   H.264 depayloader's `request-keyframe` and `wait-for-keyframe` properties.
3. `receiver/obs/cambridge-obs-source/src/cambridge_source.cpp` and
   `renderer.cpp` to verify whether a stale transition is only a presentation
   artifact or follows a decoder starvation event.

The key question is why the receiver generates sustained NACK/RTX and
keyframe-request activity while reporting zero packet loss and no appsink
overflow. The evidence points to a transport/recovery or decoder-boundary
problem rather than Wi-Fi signal quality or a simple black-placeholder timeout.

## Verification completed

- CamBridge version and transport-contract checks passed.
- Focused live-frame policy test passed.
- Android unit tests, lint, and debug APK build passed.
- Remote native plugin build passed and `ldd -r` reported no unresolved
  references.
