# Linux receiver baseline

Recorded before the cross-platform receiver migration, on 2026-08-11 from
the clean `main` worktree.

## Identity and compatibility

- OBS source IDs: `cambridge_android_source` and deprecated
  `direct_android_rtp_webcam`.
- OBS source display name: `CamBridge`.
- Discovery receiver ID: `cambridge-obs-source`.
- Discovery display name: `OBS receiver`.
- Protocol: version 6, length-prefixed JSON over TCP, RFC 6184 H.264 RTP over
  UDP.

## Saved properties

The existing property keys are `control_port`, `media_port`,
`maximum_long_edge`, `maximum_short_edge`, `reorder_deadline_ms`,
`maximum_decoder_queue_age_ms`, `maximum_live_frame_age_ms`,
`receive_buffer_bytes`, `drm_device`, `decoder_mode`,
`transparent_placeholder`, and `diagnostics_path`.

The saved decoder values are `auto` and `cpu`. The default DRM device is
`/dev/dri/renderD128`; the default decoder mode is `auto`.

## Geometry

For the baseline 2560x1440 fixture, the source reports these display sizes:

| Wire rotation | Display geometry |
| --- | --- |
| 0 | 2560x1440 |
| 90 | 1440x2560 |
| 180 | 2560x1440 |
| 270 | 1440x2560 |

The same swap rule applies to every accepted coded geometry.

## Events

The observed/logged event vocabulary includes `identity`, `listening`,
`bounds`, `discovery`, `discovery_unavailable`, `session_accepted`,
`decoder_ready`, `first_frame_published`, `render_mode`,
`control_disconnected_session_invalidated`, `live_frame_stale_placeholder`,
`rtp_loss`, `rtp_invalid`, `diagnostics_written`, and the decoder/renderer
error events emitted by the current implementation.

## Diagnostics

The current JSON snapshot fields are `module`, `version`, `gitCommit`,
`protocolVersion`, `state`, `codedWidth`, `codedHeight`, `displayWidth`,
`displayHeight`, `rotationDegrees`, `decoder`, `render`,
`mailboxOccupancy`, `mailboxMaximum`, `framesReplaced`, `framesStale`,
`framesDecoded`, `framesRendered`, `cpuFrameCopies`, `gpuCopies`,
`hardwareCpuTransfers`, `dmaBufImportFailures`, `packetsReceived`,
`bytesReceived`, `packetsLost`, `malformedPackets`, `invalidSourcePackets`,
`decodeFailures`, `decoderQueueDrops`, `decoderQueueOccupancy`,
`reorderOccupancy`, `reorderPeak`, `reorderDeadlineDrops`,
`maxReceiveToDecodeMs`, `maxReceiveToPublishMs`, `maxReceiveToRenderMs`,
and the `configured` object.

## Lifecycle

The first accepted `hello` ends any older session, clears the mailbox, starts
the decoder session, and begins RTP acceptance. A matching `stop` ends the
session. Control disconnect also ends the session and logs
`control_disconnected_session_invalidated`. Session end clears the mailbox and
returns the source to its placeholder while the control and media listeners
remain available.

## Baseline verification

The existing Linux build and unit-test gate passed:

```text
./scripts/receiver/linux/build-cambridge-obs-plugin.sh
100% tests passed out of 3
```

The host has `/dev/dri/card1` and `/dev/dri/renderD128`, so the VAAPI device
is present. A complete VAAPI/DMA-BUF fixture result was not recorded because
the host is headless and has no `DISPLAY`; it is an outstanding physical
graphics gate.

The required CPU fixture command was attempted:

```text
CAMBRIDGE_DECODER_MODE=cpu CAMBRIDGE_DURATION_SECONDS=5 \
  ./scripts/receiver/linux/test-cambridge-fixture.sh
```

It did not produce a frame recording. OBS 32.2.1 aborted immediately before
writing its log, and the fixture reported:

```text
OBS did not load the CamBridge OBS plugin
artifacts=/home/ltanaka/github/phone-webcam-project/build/cambridge-fixture.IEgmo6
```

This is recorded as unavailable on the headless baseline host, not as a
passing changing-frame result. The native implementation gates remain
explicitly pending on a graphical Linux host.
