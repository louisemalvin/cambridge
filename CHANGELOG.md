# Changelog

## Unreleased

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
