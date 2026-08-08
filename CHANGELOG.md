# Changelog

## 0.2.1 — Linux discovery fix

- Require Avahi development support when building the packaged Linux receiver,
  so the plugin advertises `_cambridge._tcp` discovery services.
- Preserve the legacy OBS source ID for existing scenes during upgrades.

## 0.2.0 — CamBridge platform layout

- Renamed sender, receiver, package, and protocol identifiers to CamBridge.
- Organized implementation code under `sender/android` and
  `receiver/linux/obs`.
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
