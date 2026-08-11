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

The target privacy manifest is `CamBridge/Resources/PrivacyInfo.xcprivacy`.
It declares `CA92.1` for the app's private `UserDefaults` preferences and the
Xcode check verifies that the manifest is present in the built app bundle.
The target does not yet contain an `AppIcon` asset catalog: approved artwork
has not been supplied, so distribution readiness remains blocked on that
owner-provided asset only. Do not create or select substitute artwork.

## Local install

1. Open `sender/ios/CamBridge.xcodeproj` in Xcode.
2. Select the connected iPhone, enable Developer Mode if requested, and set a
   local Personal Team or paid team for the local run only.
3. Grant camera and local-network access.
4. On Setup, use “Copy capability report” before starting a stream; the same
   action is available under Settings. Confirm the copied report is marked as
   local diagnostic data, redacts receiver hosts, and includes camera
   descriptors, exact formats, zoom bounds, stabilization choices, encoder
   identity or an explicit unavailable reason, and bitrate bounds.

Record Xcode version, iPhone model, iOS version, camera descriptors, exact
offered modes, selected format, encoder identity, stabilization options, and
zoom bounds.

## Physical matrix

For every offered mode, record successful 1080p30 operation and each other
offered resolution/FPS combination, including 2K60 when the report offers it.
Exercise not-determined, denied, restricted, and authorized camera states;
no receiver, one discovered receiver, multiple-receiver selection, valid and
invalid manual receiver probes; all four rotations; preview/OBS agreement;
every exposed rear lens; zoom bounds; requested versus active stabilization;
explicit Start/Stop; Settings-to-Setup propagation while idle; active-stream
preference locking; accepted values after stopping; backgrounding; camera
interruption; TCP control loss; terminal UDP failure; and restart after
failure.

For each offered non-highest mode, retain at least three measured minutes
after a ten-second warm-up. Retain at least ten measured minutes for the
highest offered mode. Record requested FPS, encoded access units and cadence,
queue maximum and drops, RTP packet/byte counts, maximum send duration, CPU
and thermal/system-pressure state, and OBS presentation behavior. A mode that
misses the fixed contract thresholds must not remain offered on that device.

Retain at least one sustained-session diagnostics report containing queue/drop,
thermal/system-pressure, encoder cadence, first-encoded, and first-sent RTP
milestones. Repeat with OBS hardware decode and CPU fallback where the Linux
host supports both. Unsupported exact device modes may remain omitted; a mode
that repeatedly fails must not remain offered.
