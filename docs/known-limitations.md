# Known limitations

- The supported product path is Android sender → Linux x86_64/amd64 OBS
  receiver. An iOS sender and Windows, macOS, and ARM Linux receivers are not
  currently supported.
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
