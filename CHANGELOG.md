# Changelog

## Unreleased

## Android 0.5.5 - persistent rear lens controls

- Keep the main rear camera's complete lens pill list visible after selecting
  an individual rear lens.

## Android 0.5.4 - camera selection controls

- Replace mixed front, back, and physical-camera choices with one front/back
  flip action and ordered rear-lens zoom pills.

## Android 0.5.3 - transport worker startup

- Activate the stream generation before starting MediaCodec and its transport
  worker so the worker cannot exit before delivering the first access unit.

## OBS plugin 0.5.2 - GStreamer transport simplification

- Restore GStreamer's adaptive RTX, RTCP scheduling, jitter, and congestion
  control defaults while retaining one 40 ms receiver latency policy.
- Replace timed bitrate, keyframe, decoder-age, and stale-render controls with
  state-based encoder requests, one-access-unit backpressure, and latest-frame
  presentation.
- Make the Android transport handoff cancellation-aware so stopping an active
  stream cannot remain blocked on an empty access-unit queue.
- Add direct clean-path, loss recovery, 2K30 at 16 Mbps, and temporary decoder
  slowdown transport coverage with GStreamer jitterbuffer diagnostics.

## Android 0.5.1 and OBS plugin 0.5.1 - transient recovery presentation

- Keep the last decoded frame visible during one bounded live-age recovery
  window so short RTP/RTX recovery gaps do not flash a black placeholder.
- Preserve the black placeholder after the bounded recovery window and when
  the session has no valid frame.

## Android 0.5.0 - GStreamer RTP transport

- Replace application RTP packetization and pacing with the pinned GStreamer
  RTP/RTCP sender, RTX, TWCC, and GCC pipeline.
- Add bounded access-unit recovery, MediaCodec keyframe requests, and adaptive
  bitrate updates from GStreamer estimates.
- Require the official GStreamer Android SDK and protocol v7 control fields.

## OBS plugin 0.5.0 - GStreamer receiver

- Replace the raw UDP RTP receiver with GStreamer `rtpbin`, jitter buffering,
  RTCP feedback, RTX receive, H.264 depayloading, and appsink delivery.
- Add integration coverage for retransmission, keyframe recovery, burst loss,
  and bandwidth adaptation.
- Require GStreamer 1.24.13 or newer and the Rust RTP plugin at runtime.

## Android 0.4.1 - RTP delivery recovery

- Pace H.264 RTP datagrams at the configured media rate so large 2K frames do
  not arrive as one burst on the local or Tailscale network path.
- Surface receiver media failures as terminal stream errors so the sender
  releases its session cleanly.

## OBS plugin 0.4.1 - Media failure recovery

- Notify the Android sender before closing a failed control session.
- Release the control connection after decoder failure so the next Start can
  connect without restarting OBS.

## Android 0.4.0 - Permission, encoder, and stream lifecycle hardening

- Gate stream start on the current camera permission and recover cleanly when
  permission is revoked or restored.
- Select the exact requested H.264 MediaCodec encoder and resolve the active
  video configuration before opening the session.
- Make control startup, callback ownership, and stop cleanup terminal and
  generation-safe.

## OBS plugin 0.4.0 - Multi-ABI packaging and receiver hardening

- Package one Linux installer bundle containing exact OBS and FFmpeg ABI
  profiles without bundling host libraries.
- Select the compatible plugin from the OBS executable's actual ELF
  dependencies and preserve existing installations when validation fails.
- Harden native control startup, media callbacks, and release validation.

## 0.3.3 - Permission recovery and decision-oriented testing

- Classify camera permission loss as a permission failure throughout stream
  startup, including recovery from the webcam route through Android app
  settings.
- Keep a known-good receiver endpoint when a new manual receiver probe fails.
- Keep unsupported 60 fps reasons attached to the frame-rate choice instead of
  incorrectly marking a supported resolution as unavailable.
- Add decision-trace documentation, release acceptance criteria, targeted
  lifecycle tests, and CI compilation of Android instrumentation tests.

## 0.3.2 - Android permission recovery and capability diagnostics

- Fixed stream setup getting stuck with a disabled Start stream control when
  camera permission was missing or denied.
- Added explicit camera permission recovery, including an app-settings path
  for permanently denied access and refresh when returning from Android
  settings.
- Explain why an unavailable resolution or frame rate is blocked, including
  the camera, H.264 encoder, and bitrate-intersection causes.
- Record the resolved phone video capability reasons in Android diagnostics.

## 0.3.1 — OBS 32.2 compatibility and release hardening

- Rebuilt the Linux OBS plugin against the current OBS 32.2 and FFmpeg 8
  library ABIs instead of the obsolete `libobs.so.0` and FFmpeg 6 stack.
- Removed plugin RPATH/RUNPATH requirements and reject bundled OBS or FFmpeg
  libraries from the Linux release archive, so OBS loads the host-compatible
  libraries.
- Added Linux dependency validation, isolated OBS startup coverage, and a
  versioned release package for direct download.
- Release CI now builds against the pinned OBS 32.2.0 source and Ubuntu 26.04
  FFmpeg 8 libraries used by the supported Linux target.

- Consolidated the OBS receiver into one shared Linux/macOS source tree with
  explicit Automatic, NativeRequired, and Software media-path selection locked
  before RTP acceptance.
- Added the macOS VideoToolbox, Metal, IOSurface, Bonjour, universal-build, CI,
  and signed/notarized package paths. macOS remains an acceptance candidate and
  is not marked as supported until the physical architecture and clean-machine
  gates pass.
- Packaged the macOS receiver as one OBS `.plugin` bundle containing its
  permission metadata and compiled Metal resource, with explicit fresh-runner
  signing and notarization setup.
- Made native decode, conversion, import, and bounded-pool failures terminal
  for the active session; the deprecated `hardwareCpuTransfers` diagnostic is
  always zero.
- Serialized receiver session replacement and made decoder/renderer failures
  generation-scoped so stale callbacks cannot stop a newer stream. Tightened
  DMA-BUF object bounds and NV12 pitch validation on Linux.

## 0.3.0 — Sender-owned video modes and resilient receiver discovery

- Introduced CamBridge stream protocol version 6. It is intentionally
  incompatible with version 5, so the Android app and OBS plugin must be
  upgraded together.
- Moved video-mode ownership to the phone. CamBridge now offers the 1080p and
  2K modes supported by the selected camera and H.264 encoder at 30 or 60 fps,
  with a bounded, persistent bitrate control.
- Added lifecycle-scoped receiver discovery, explicit selection when multiple
  OBS receivers are available, manual address entry, and probing of bounded
  IPv4 candidates advertised by multi-homed receivers.
- Added explicit Off, optical, electronic, and camera-managed preview
  stabilization choices, including capture-result confirmation and structured
  diagnostics when a requested mode is unavailable for the active stream.
- Expanded contract, Android, native receiver, discovery, UI, and end-to-end
  coverage for the new session and discovery behavior.

## 0.2.2 — Portable release checksums

- Write Linux plugin checksums with the archive filename instead of the build
  runner's absolute path, so they can be verified on another laptop.

## 0.2.1 — Linux discovery fix

- Require Avahi development support when building the packaged Linux receiver,
  so the plugin advertises `_cambridge._tcp` discovery services.
- Preserve the legacy OBS source ID for existing scenes during upgrades.

## 0.2.0 — CamBridge platform layout

- Renamed sender, receiver, package, and protocol identifiers to CamBridge.
- Organized implementation code under `sender/android` and the native
  `receiver/obs` tree.
- Introduced CamBridge stream protocol version 5; it is intentionally
  incompatible with earlier protocol releases.

## 0.1.1 — CamBridge branding update

- Branded the Linux receiver as the CamBridge OBS Plugin.
- Renamed downloadable APK and Linux plugin artifacts to use CamBridge names.
- Preserved stream behavior while standardizing the public CamBridge branding.

## 0.1.0 — initial public release

Release status: published.

- Android sender using Camera2, MediaCodec H.264, and RTP/H.264 over UDP.
- CamBridge OBS Plugin for Linux x86_64/amd64 with VAAPI/DRM PRIME and CPU fallback.
- Downloadable Android APK and CamBridge OBS Plugin release artifacts.
- Trusted-LAN deployment without authentication or encryption.
