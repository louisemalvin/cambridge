# Known limitations

- The supported product path is Android sender → Linux x86_64/amd64 OBS
  receiver. The macOS 12+ receiver implementation is present but remains an
  acceptance candidate until both physical architectures and clean-machine
  package installation pass. An iOS sender and Windows and ARM Linux receivers
  are not currently supported.
- Android camera capabilities and hardware H.264 encoder support vary by
  device. The app may offer fewer compatible quality or frame-rate choices
  than the normal 1080p and 2K phone modes, and the encoder can narrow a
  mode's bitrate slider range.
- `720p30` is retained for automated runtime testing and is not a normal
  product quality choice.
- Stabilization is reported from Camera2 capture results, not inferred from
  the requested setting. Electronic stabilization is guaranteed by Android
  only within a limited 1080p30 envelope; at 1440p30 the app may report the
  requested mode as unavailable for that stream.
- Stabilization quality and preview/OBS equivalence require physical-device
  A/B evidence. See [Android camera modes](android-camera.md); no universal
  quality claim is made from emulator or single-device results.
- Hardware decoding and direct DMA-BUF presentation depend on the Linux GPU,
  driver, render-node permissions, and OBS graphics support. The software
  decode/NV12 upload fallback uses more CPU and may perform differently.
- Receiver decoder modes are explicit: Automatic selects native or software at
  session start, NativeRequired rejects unavailable native setup, and Software
  never attempts native setup. Once selected, a path does not change; native
  decode, Metal conversion, import, or pool failures end the session.
- Receiver discovery depends on Android NSD and the receiver's mDNS/Avahi
  advertisement. CamBridge retains all addresses Android resolves, including
  addresses from multi-homed or VPN-connected receivers, but networks that do
  not carry the DNS-SD advertisement still require a receiver endpoint entered
  manually in the Android setup screen.
- The media path is best-effort. Lost sessions do not reconnect automatically;
  start the stream again from the phone after fixing the network or receiver.
- One receiver handles one active session. Resolution, frame rate, and
  orientation changes take effect after Stop and a new Start.
- The control and media transport is unauthenticated and unencrypted. Use
  CamBridge only on a trusted local network.

## macOS receiver status

The macOS path is VideoToolbox → retained IOSurface-backed NV12 → one Metal
NV12-to-BGRA conversion → bounded IOSurface pool → OBS texture. It is built for
macOS 12+ arm64 and x86_64, but no macOS support claim is made until the native
fixture, Bonjour lifecycle, rotation, soak, and clean-install gates pass on both
architectures. The signed package is release-gated and is not added to tagged
releases until those checks pass. If local-network access is denied, Bonjour
discovery is degraded; manual receiver addressing remains available.

## iOS sender status

The native iOS sender project and Linux Swift interoperability fixture are
present, but iOS support remains an install candidate until macOS Xcode checks
and physical-device validation are retained. Linux cannot establish whether a
particular iPhone exposes an exact shared mode, a usable hardware H.264
encoder, the requested stabilization modes, or the expected preview/OBS
orientation.

iOS streaming is foreground-only. Backgrounding, camera interruption, system
pressure, terminal control loss, and terminal UDP failure end the session; the
app does not reconnect automatically. Unsupported exact camera modes are
omitted rather than scaled or silently downgraded.
