# Portable receiver core

This directory is the boundary for receiver behavior that must work on every
host operating system. It is intentionally separate from the current OBS host
adapter at `../hosts/obs/direct-webcam-source/`.

The core may own:

- direct-stream control validation and session identity
- RTP/H.264 packet parsing and access-unit assembly
- decoder coordination interfaces
- frame metadata and bounded newest-frame delivery
- lifecycle, drop policy, and diagnostics events

The core must not depend on libobs, VAAPI, DRM, Windows APIs, Cocoa, or host
UI. Host integrations consume decoded frames through an explicit sink.

The current native plugin still contains both receiver and host responsibilities
so the established build remains unchanged. Extraction into this directory is
the next implementation step and must be guarded by the existing contract,
RTP, and end-to-end tests.
