# CamBridge iOS sender

The iOS sender is a native Swift/SwiftUI application for iOS 17.4 and later. It
uses the CamBridge v6 control contract and RFC 6184 H.264 RTP over UDP used by
the unchanged Linux OBS receiver.

Its current product behavior is defined by
[`simple-phone-sender.md`](../../internal/implementation-contracts/simple-phone-sender.md).

Open `CamBridge.xcodeproj` directly in Xcode. Signing is automatic and the
committed project intentionally has no development team. Select a local team
only in Xcode when installing on a device. No account, certificate,
provisioning profile, or private key belongs in this repository.

The platform-neutral package can be tested with:

```bash
./scripts/sender/ios/check-core.sh
./scripts/sender/ios/check-fixture.sh
```

On macOS, run the unsigned simulator project checks with:

```bash
./scripts/sender/ios/check-xcode.sh
```

Physical camera, VideoToolbox hardware, local-network permission, signing, and
OBS glass-to-glass behavior require validation on a signed physical-device
build; the scripts above cannot validate them.

For a physical pass, keep the OBS CamBridge source active, then verify all four
resolution/FPS combinations plus Full HD 60 at 1 Mbps. Confirm Start either
streams the exact request or reports its failing stage, front/back flip and
0.5x/1x/2x or pinch zoom leave the wire settings unchanged, and Stop releases
the camera before the next Start. Copied diagnostics identify capture/encoder,
TCP connect, hello, receiver acceptance, UDP setup, and capture-start failures
without exposing the receiver host.

The Swift fixture is test-only and is invoked by the native Linux harness with
`CAMBRIDGE_SENDER_MODE=swift`. The production app never uses its POSIX socket
adapter. Until the macOS and physical gates are validated, this directory is an
install candidate rather than a public iOS support claim.
