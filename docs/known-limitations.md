# Known limitations

- The supported product path is Android sender → Linux x86_64/amd64 OBS
  receiver. Windows, macOS, iOS, and ARM Linux receivers are not currently
  supported.
- Android camera capabilities and hardware H.264 encoder support vary by
  device. The app may offer fewer compatible quality or frame-rate choices
  than the normal 1080p and 2K profiles.
- `720p30` is retained for automated runtime testing and is not a normal
  product quality choice.
- Hardware decoding and direct DMA-BUF presentation depend on the Linux GPU,
  driver, render-node permissions, and OBS graphics support. The software
  decode/NV12 upload fallback uses more CPU and may perform differently.
- Receiver discovery depends on local-network mDNS/Avahi support. Networks
  that block multicast discovery may require a receiver endpoint configured by
  the deployment or build.
- The media path is best-effort. Lost sessions do not reconnect automatically;
  start the stream again from the phone after fixing the network or receiver.
- One receiver handles one active session. Resolution, frame rate, and
  orientation changes take effect after Stop and a new Start.
- The control and media transport is unauthenticated and unencrypted. Use
  CamBridge only on a trusted local network.
