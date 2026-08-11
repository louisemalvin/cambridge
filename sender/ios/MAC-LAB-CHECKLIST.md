# CamBridge iOS Mac-lab checklist

This is an install-candidate checklist, not physical-device evidence. Do not
update public iOS support until the completed matrix and retained diagnostics
are attached to the validation record.

## Pre-install

```bash
python3 scripts/development/generate-cambridge-swift-contract.py --check
python3 scripts/development/generate-cambridge-sender-modes.py --check
python3 scripts/development/generate-ios-version.py --check
python3 scripts/development/check-cambridge-stream-contract.py
./scripts/sender/ios/check-core.sh
./scripts/sender/ios/check-fixture.sh
./scripts/receiver/linux/build-cambridge-obs-plugin.sh
./scripts/development/check-all.sh
./scripts/sender/ios/check-xcode.sh
```

Confirm the committed project and `CamBridge` scheme are the ones tested by
Xcode. CI uses unsigned simulator builds and does not require a development
team.

## Local install

1. Open `sender/ios/CamBridge.xcodeproj` in Xcode.
2. Select the connected iPhone, enable Developer Mode if requested, and set a
   local Personal Team or paid team for the local run only.
3. Grant camera and local-network access.
4. Export the capability report before starting a stream.

Record Xcode version, iPhone model, iOS version, camera descriptors, exact
offered modes, selected format, encoder identity, stabilization options, and
zoom bounds.

## Physical matrix

For every offered mode, record successful 1080p30 operation and each other
offered resolution/FPS combination. Exercise all four rotations, preview/OBS
agreement, every exposed rear lens, zoom bounds, requested versus active
stabilization, explicit Start/Stop, backgrounding, camera interruption, TCP
control loss, terminal UDP failure, and restart after failure.

Retain at least one sustained-session diagnostics report containing queue/drop,
thermal/system-pressure, encoder cadence, first-encoded, and first-sent RTP
milestones. Repeat with OBS hardware decode and CPU fallback where the Linux
host supports both. Unsupported exact device modes may remain omitted; a mode
that repeatedly fails must not remain offered.
