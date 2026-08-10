# Android camera modes

The Android sender owns the video mode catalog. Camera2 and the selected H.264
MediaCodec encoder narrow that catalog at runtime; the OBS receiver only checks
wire/resource safety and consumes the exact values in the v6 `hello`.

## Phone video catalog

The runtime source of these values is
[`VideoProfiles.kt`](../sender/android/app/src/main/java/dev/cambridge/sender/session/VideoProfiles.kt).
The 720p mode is available to the emulator smoke harness only.

| Mode | Minimum | Default | Maximum | Step |
| --- | ---: | ---: | ---: | ---: |
| 720p30 smoke | 2 Mbps | 4 Mbps | 8 Mbps | 1 Mbps |
| 1080p30 | 4 Mbps | 8 Mbps | 16 Mbps | 1 Mbps |
| 1080p60 | 8 Mbps | 16 Mbps | 32 Mbps | 1 Mbps |
| 1440p30 (`2k30`) | 9 Mbps | 18 Mbps | 36 Mbps | 1 Mbps |
| 1440p60 (`2k60`) | 18 Mbps | 36 Mbps | 72 Mbps | 1 Mbps |

The bitrate slider and numeric input use 1 Mbps steps and store integer bits per second. Their effective
range is the intersection of the selected catalog mode and the selected phone
encoder's advertised bitrate range. Changing resolution or FPS selects the
catalog default for the resulting mode. Finishing a bitrate drag or submitting
an in-range numeric value persists the selected step.

## Explicit stabilization modes

After camera preparation, the setup and in-stream Settings screens show only
the modes advertised by the selected camera/lens, with `Off` always available:

- `Off`: video and optical stabilization are both requested Off.
- `Optical`: optical stabilization On and video stabilization Off.
- `Electronic`: video stabilization On and optical stabilization Off.
- `Preview (camera-managed)`: preview stabilization is requested and optical
  stabilization is Off. This requires API 33 or later and the corresponding
  Camera2 capability.

The request is not reported as applied immediately. Capture-result metadata is
reduced to a platform-neutral applied state. The UI can show `Applying`,
`Applied`, or `unavailable for this stream`; a request that remains Off past the
confirmation deadline leaves the stream running and reports unavailable. Mode
changes may recreate the Camera2 capture session when a session parameter
requires it, but they do not recreate the MediaCodec encoder, RTP session, or
OBS control session.

Diagnostics are transition-based. Stabilization request/applied events include
the run and session IDs when streaming, camera/lens identity, coded resolution,
FPS, zoom, requested mode, applied mode, and confirmation timing. They should
be saved alongside each A/B clip.

## Physical A/B validation

A is always `Off`; B is one explicit mode advertised by the selected lens. Hold
the phone, lens, zoom (1x), orientation, resolution, FPS, bitrate, lighting,
subject, network path, OBS scene, and motion path constant. Capture equal-length
alternating `A/B/A` clips and retain the matching Android structured log and OBS
diagnostics. Repeat for every physical lens offered by the device.

Evaluate both the OBS recording and phone preview for residual shake,
crop/field-of-view changes, wobble or warping, focus pumping, frame
discontinuities during a runtime switch, and added latency. A mode passes the
functional check only when capture results report the requested applied mode
and OBS continues receiving changing frames. Visual improvement is a separate
observation and is not inferred from metadata.

| Device / API | Lens | 1080p30 A/B | 1440p30 A/B | Diagnostics / OBS frames |
| --- | --- | --- | --- | --- |
| Physical device not connected in this environment | Pending | Pending | Pending | Pending |

No physical-device stabilization or glass-to-glass result is claimed by this
repository change. The emulator lifecycle smoke test can validate fallback and
no-crash behavior, but it cannot establish physical stabilization quality.
