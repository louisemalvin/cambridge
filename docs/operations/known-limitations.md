# Known limitations

These limitations apply to the current baseline described in
[the contract](../contract.md); they are not alternate product architectures.

- The canonical Android runtime gate uses the named Android API 35 AVD with an
  explicit test-only 720p30 profile. The normal product profile is 2K30, but
  the AVD does not advertise the required 2560x1440 encoder size.
- Physical 2K landscape and portrait capture still require a separately
  authorized hardware run.
- A one-hour stability and bounded-RSS run has not been claimed by the short
  smoke script. It must be executed with retained logs before release.
- Physical Android camera, Wi-Fi, and glass-to-glass latency validation is
  intentionally deferred. The required development check must not contact the
  available physical device.
- VAAPI and direct DMA-BUF import depend on the host GPU, Mesa/libva driver,
  DRM render-node permissions, and OBS graphics support. Software decode with
  NV12 upload is the bounded fallback.
- OBS shutdown behavior can be host-session dependent under isolated Wayland
  smoke runs. The source logs its own resource cleanup and module unload, but a
  clean GUI shutdown is not used as a pass criterion.
- The transport is intended for a trusted local network. Authentication and
  encryption are outside this direct Phase 1 contract.
