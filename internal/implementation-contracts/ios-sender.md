# iOS Sender Implementation Contract

Internal status: implementation handoff. This document defines the required iOS
sender architecture and delivery sequence. It is not a statement that iOS is
already supported.

The implementation agent should make routine implementation choices within
these boundaries without requesting further product decisions. Any proposed
wire-protocol change, OBS receiver change, security-model change, or reduction
of the required product scope must be raised before implementation.

## Objective

Add a production-quality iPhone sender that interoperates with the existing
CamBridge protocol v6 Linux OBS receiver. The iOS sender must provide the same
product lifecycle as Android: discover or manually address a receiver, select
a phone-supported stream configuration, explicitly start one session, show a
live camera interface, and explicitly stop the session.

The result is not a proof-of-concept or a permanently reduced MVP. Development
may use internal checkpoints, but work continues through the complete scope and
verification gates in this document.

## Normative language

`MUST`, `MUST NOT`, `SHOULD`, and `SHOULD NOT` describe implementation
requirements. A `SHOULD` may be violated only when the implementation records a
specific Apple-platform constraint and supplies equivalent behavior.

## Implementation-agent operating rules

The implementation agent must follow these rules mechanically:

1. Read this entire document, `AGENTS.md`, the JSON contract, protocol docs,
   Android stream engine, Android RTP tests, and native control/RTP tests before
   editing production code.
2. Create a task plan containing all ten implementation stages from this
   document. Never have more than one stage marked in progress.
3. Implement stages in order. Do not start UI polish while lower-level contract
   or media tests are failing.
4. At the end of every stage, run that stage's required checks and inspect every
   changed production file for unexplained numeric literals.
5. Do not replace required work with `TODO`, `fatalError("not implemented")`,
   empty methods, fake success results, hard-coded test responses, or disabled
   tests.
6. Do not make a test pass by weakening contract validation, increasing an
   established buffer bound, or adding sleeps/retries without a named and
   documented reason.
7. Do not copy Android implementation code line-by-line. Match externally
   observable behavior while using Apple frameworks in their intended style.
8. Do not add a second protocol, transport, codec, audio path, server, relay,
   virtual-camera path, or receiver negotiation mechanism.
9. Do not edit native receiver behavior unless a failing cross-language test
   proves a receiver defect independent of iOS. Escalate that evidence first.
10. Do not edit the wire contract merely to simplify Swift implementation.
11. Keep every queue, read size, message size, NAL size, and retry policy
    bounded by the contract or a narrowly scoped named configuration.
12. Keep generated files clearly marked and never hand-edit them.
13. Use official Apple documentation to confirm Apple API signatures and
    availability. Do not guess deprecated or simulator-only APIs.
14. When Xcode is unavailable locally, use Linux tests and macOS CI. Do not
    claim Apple-only code compiles until macOS CI proves it.
15. Preserve unrelated user changes and keep signing/account information out of
    all patches and logs.
16. Do not change README platform support, release artifacts, or the public
    support table until physical-device completion evidence exists.
17. If blocked on one Apple adapter, continue only with independent work from
    the same stage; do not skip its acceptance gate and declare the stage done.
18. A stage is complete only when its production code, tests, documentation,
    commands, and clean-worktree check all pass.

## Fixed product decisions

- The deployment target is iOS 17 or later.
- The primary device family is iPhone. iPad-specific layouts are not required.
- The app uses Swift, SwiftUI, Observation, and structured concurrency.
- The app uses AVFoundation for camera capture and preview.
- The app uses VideoToolbox for real-time H.264 encoding.
- The app uses Network.framework for Bonjour discovery, TCP control, and UDP
  media transport.
- The app has no third-party runtime dependencies.
- The app sends video only. It does not capture or transmit audio.
- The app is a CamBridge v6 sender; it does not add a new protocol version.
- The OBS receiver is not changed merely to accommodate the iOS implementation.
- Streaming is foreground-only. Backgrounding ends the active session.
- There is no automatic reconnect. A terminal failure requires another explicit
  Start.
- The transport remains intended for a trusted local network and remains
  unauthenticated and unencrypted, matching the current product.
- Signing uses Xcode automatic signing selected locally by the developer. No
  development team, Apple Account, certificate, profile, password, or private
  key is committed.
- The repository contains a native Xcode project that can be opened directly.
  The developer must not need a project generator to install the app.

## Authoritative sources

The implementation must derive behavior from these sources in this order:

1. `protocol/cambridge-stream-contract.json` is the authoritative wire and
   transport contract.
2. `protocol/cambridge-stream.schema.json` defines valid control-message shapes.
3. `docs/protocol.md` explains the intended protocol behavior.
4. The Android sender is the behavioral reference for session lifecycle,
   bounded latency, discovery selection, and user-facing flow.
5. The native receiver is the compatibility reference for accepted control and
   RTP input.
6. This document defines iOS architecture and Apple-platform behavior.

Protocol constants MUST NOT be copied by hand into unrelated Swift files. A
generated Swift contract file must be produced from the authoritative JSON
contract, committed, and checked for staleness by repository validation. The
generator must fail clearly if it encounters a contract shape it does not
understand.

Phone video-mode values are product configuration, not receiver configuration.
If Android and iOS share the same mode definitions, the implementation must
extract those definitions into one sender-owned machine-readable catalog and
generate or validate both platform representations. The receiver MUST NOT gain
a profile catalog or begin interpreting `profileId`.

### Shared sender video catalog directive

The implementation must add `sender/cambridge-video-modes.json` as the one
product source for the existing Android and new iOS phone modes. Its top-level
shape is fixed:

```json
{
  "catalogVersion": 1,
  "defaultModeId": "2k30",
  "modes": [
    {
      "id": "1080p30",
      "codedWidth": 1920,
      "codedHeight": 1080,
      "fps": 30,
      "minimumBitrateBps": 4000000,
      "defaultBitrateBps": 8000000,
      "maximumBitrateBps": 16000000,
      "bitrateStepBps": 1000000,
      "keyframeIntervalSeconds": 1,
      "availability": "product"
    }
  ]
}
```

The final catalog must contain the exact current Android definitions for
`720p30`, `1080p30`, `1080p60`, `2k30`, and `2k60`. `720p30` remains
`test-only`; the other four are `product`. Do not alter their current values as
part of extraction. Generate platform declarations or make the parity checker
parse their typed declarations. Do not make either application read repository
JSON at runtime.

The iOS app starts from these candidate modes and filters them through camera,
encoder, and receiver capabilities. It does not add 4K, arbitrary device
formats, or a scaling pipeline during this implementation. A mode absent from
the selected iPhone is correctly unavailable; it is not approximated.

## Apple design references

Follow the current Apple patterns rather than translating Android classes
literally:

- [AVCam: Building a camera app](https://developer.apple.com/documentation/avfoundation/avcam-building-a-camera-app)
  for an actor-isolated capture service and a responsive SwiftUI interface.
- [Managing model data in your app](https://developer.apple.com/documentation/swiftui/managing-model-data-in-your-app)
  for Observation and a single source of presentation state.
- [Organizing your code with local packages](https://developer.apple.com/documentation/xcode/organizing-your-code-with-local-packages)
  for the platform-neutral core boundary.
- [Managing files and folders in your Xcode project](https://developer.apple.com/documentation/xcode/managing-files-and-folders-in-your-xcode-project)
  for filesystem-backed project organization.
- [Encoding video for live streaming](https://developer.apple.com/documentation/videotoolbox/encoding-video-for-live-streaming)
  for VideoToolbox session construction and frame submission.
- [Swift API Design Guidelines](https://www.swift.org/documentation/api-design-guidelines/)
  for naming and public API design.

## Repository structure

The required top-level structure is:

```text
sender/ios/
├── README.md
├── CamBridge.xcodeproj/
├── Configuration/
│   ├── Debug.xcconfig
│   ├── Release.xcconfig
│   └── Shared.xcconfig
├── CamBridge/
│   ├── App/
│   ├── Features/
│   │   ├── StreamSetup/
│   │   ├── Webcam/
│   │   └── Settings/
│   ├── Platform/
│   │   ├── Camera/
│   │   ├── Encoding/
│   │   ├── Network/
│   │   ├── Persistence/
│   │   └── Diagnostics/
│   └── Resources/
├── Packages/
│   └── CamBridgeCore/
│       ├── Package.swift
│       ├── Sources/CamBridgeCore/
│       │   ├── Contract/
│       │   ├── Control/
│       │   ├── H264/
│       │   ├── RTP/
│       │   └── Session/
│       └── Tests/CamBridgeCoreTests/
├── CamBridgeTests/
└── CamBridgeUITests/
```

Filesystem folders and Xcode navigator folders should match. Source files
should normally contain one primary type. Do not create generic `Helpers`,
`Utils`, `Common`, or `Managers` dumping grounds. A type name must state the
role it owns, such as `CaptureService`, `ReceiverBrowser`, `ControlConnection`,
or `RTPH264Packetizer`.

The Xcode project must contain:

- one iOS application target named `CamBridge`;
- one application unit-test target named `CamBridgeTests`;
- one UI-test target named `CamBridgeUITests`;
- the local `CamBridgeCore` package dependency;
- shared schemes committed for command-line and CI use;
- Debug and Release configurations backed by committed `.xcconfig` files;
- automatic code signing with no committed development team;
- an explicit, reviewable Info.plist or equivalent generated keys in build
  configuration;
- no CocoaPods workspace and no checked-in signing assets.

Shared build configuration is fixed as follows:

```text
PRODUCT_NAME = CamBridge
PRODUCT_BUNDLE_IDENTIFIER = dev.cambridge.sender
IPHONEOS_DEPLOYMENT_TARGET = 17.0
TARGETED_DEVICE_FAMILY = 1
SWIFT_VERSION = 6.0
CODE_SIGN_STYLE = Automatic
DEVELOPMENT_TEAM = <unset in committed files>
```

`MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` must be generated from the
repository-root `VERSION` using a checked script; do not maintain a second
handwritten version. Debug and CI may override the bundle identifier from the
command line, but credentials and team identifiers are never build defaults.

The committed usage strings are:

```text
NSCameraUsageDescription = CamBridge uses your camera to stream video to your selected OBS computer.
NSLocalNetworkUsageDescription = CamBridge uses your local network to find and stream video to OBS.
NSBonjourServices = ["_cambridge._tcp"]
```

Declare all four iPhone interface orientations. Do not add microphone usage,
background audio, VOIP, local-network multicast entitlements, or unrelated
capabilities.

## Required file and type manifest

The agent must begin with this file map. A file may be split only when it grows
beyond one clear responsibility; a listed responsibility must not disappear or
move into an unrelated generic type.

### CamBridgeCore files

| File | Required primary type and responsibility |
| --- | --- |
| `Contract/CamBridgeContract.generated.swift` | Generated nested constants from the JSON contract. No handwritten values. |
| `Contract/StreamRotation.swift` | Four clockwise rotations and display-dimension swapping. |
| `Contract/VideoGeometry.swift` | Validated coded/display geometry and receiver-bound checks. |
| `Contract/VideoMode.swift` | Sender-authored mode ID, dimensions, FPS, bitrate range/step, keyframe interval. |
| `Control/ControlMessage.swift` | Strong enum covering all six v6 message shapes. |
| `Control/ControlMessageCodec.swift` | Strict JSON encode/decode and post-decode validation. |
| `Control/ControlFrameEncoder.swift` | Big-endian length prefix for one validated JSON payload. |
| `Control/ControlFrameDecoder.swift` | Incremental bounded parser yielding zero or more complete payloads. |
| `H264/AVCDecoderConfiguration.swift` | Parse NAL-length width and SPS/PPS from valid AVC configuration data. |
| `H264/H264NALUnit.swift` | NAL type/header interpretation needed by normalization and RTP. |
| `H264/H264AccessUnitNormalizer.swift` | Length-prefixed-to-Annex-B conversion and SPS/PPS insertion on keyframes. |
| `RTP/RTPPacket.swift` | RTP header encoding into one complete datagram. |
| `RTP/RTPH264Packetizer.swift` | Single-NAL/FU-A packetization, timestamps, marker, sequence wrap. |
| `Session/ReceiverEndpoint.swift` | Validated host/service endpoint description without Network.framework types. |
| `Session/ReceiverCapabilities.swift` | Receiver identity and geometry limits. |
| `Session/SessionIdentity.swift` | Session ID, nonzero generation, and matching rules. |
| `Session/StreamConfiguration.swift` | Immutable exact mode, bitrate, geometry, and orientation for one start. |
| `Session/StreamFailure.swift` | Typed user-relevant terminal failure categories. |
| `Session/StreamState.swift` | Idle/Connecting/Streaming/Stopping/Failed values. |
| `Session/StreamStateMachine.swift` | Legal transitions and idempotent stop rules. |
| `Session/NewestItemBuffer.swift` | Contract-bounded newest-item replacement policy. |

The core public surface must be small. Tests should use public initializers and
methods; test-only access must not cause production fields to become mutable.
At minimum, callers need these operations, regardless of exact Swift syntax:

```text
ControlMessageCodec.encode(message) -> Data
ControlMessageCodec.decode(data) -> ControlMessage
ControlFrameEncoder.frame(payload) -> Data
ControlFrameDecoder.append(data) -> [Data]
H264AccessUnitNormalizer.normalize(sample, nalLengthBytes, parameterSets, isKeyframe) -> Data
RTPH264Packetizer.packetize(annexBAccessUnit, presentationTimeMicroseconds) -> [Data]
StreamStateMachine.beginStart(identity, configuration)
StreamStateMachine.accept(acceptedMessage)
StreamStateMachine.beginStop()
StreamStateMachine.finishStop()
StreamStateMachine.fail(typedFailure)
```

These operations throw typed errors for invalid input. They do not return an
empty success value after malformed input.

### iOS app files

| File | Required primary type and responsibility |
| --- | --- |
| `App/CamBridgeApp.swift` | SwiftUI entry point and root environment construction. |
| `App/AppEnvironment.swift` | Owns concrete long-lived services and injects protocols. |
| `App/AppModel.swift` | Main-actor observable root navigation and presentation state. |
| `App/AppLifecycleController.swift` | Scene phase, idle timer, and terminal background handling. |
| `App/StreamSessionCoordinator.swift` | Actor coordinating exactly one start/stop and all cleanup. |
| `Features/StreamSetup/StreamSetupScreen.swift` | Complete setup composition only. |
| `Features/StreamSetup/StreamSetupModel.swift` | Main-actor setup state/actions and service orchestration. |
| `Features/StreamSetup/ReceiverSelectionView.swift` | Discovered/manual receiver controls. |
| `Features/StreamSetup/VideoModeSelectionView.swift` | Resolution, FPS, bitrate, orientation choices. |
| `Features/Webcam/WebcamScreen.swift` | Live preview/status/control composition. |
| `Features/Webcam/WebcamModel.swift` | Main-actor live presentation and camera actions. |
| `Features/Webcam/CameraPreviewView.swift` | SwiftUI-to-preview-layer bridge only. |
| `Features/Webcam/CameraControlsView.swift` | Zoom/lens/stabilization controls. |
| `Features/Settings/SettingsScreen.swift` | Settings and diagnostics composition. |
| `Features/Settings/SettingsModel.swift` | Main-actor persisted settings presentation/actions. |
| `Platform/Camera/CaptureService.swift` | Actor owning camera/session/output/preview lifecycle. |
| `Platform/Camera/CameraCapabilityProbe.swift` | Exact camera format/FPS mode intersection. |
| `Platform/Camera/CameraModels.swift` | Immutable device, lens, stabilization, and state values. |
| `Platform/Camera/SessionOrientationResolver.swift` | Clockwise wire rotation and preview orientation resolution. |
| `Platform/Encoding/VideoToolboxEncoder.swift` | VTCompressionSession lifecycle and encoded output. |
| `Platform/Encoding/EncoderCapabilityProbe.swift` | Temporary hardware H.264 feasibility/range checks. |
| `Platform/Encoding/EncodedAccessUnit.swift` | Sendable encoded Data, PTS, keyframe metadata. |
| `Platform/Encoding/EncodedAccessUnitQueue.swift` | Actor applying core newest-item policy and telemetry. |
| `Platform/Network/BonjourReceiverBrowser.swift` | NWBrowser lifecycle and TXT/service endpoint extraction. |
| `Platform/Network/CamBridgeReceiverProbe.swift` | Side-effect-free probe and response validation. |
| `Platform/Network/CamBridgeControlConnection.swift` | Persistent framed TCP send/read/EOF lifecycle. |
| `Platform/Network/RTPDatagramSender.swift` | Connected UDP path and serialized datagram completion. |
| `Platform/Persistence/SenderSettingsStore.swift` | Validated UserDefaults access. |
| `Platform/Diagnostics/CamBridgeLogger.swift` | Logger categories, privacy, and event helpers. |
| `Platform/Diagnostics/DiagnosticsReport.swift` | Redacted copyable report construction. |

Service protocols belong beside their concrete implementation when there is
only one consumer, or in the consuming feature when they define what that
feature needs. Do not create a protocol for a value type or solely to increase
the file count. Do create seams for network, camera, encoder, persistence, and
session services used by automated tests.

### Required scripts and CI files

| File | Required behavior |
| --- | --- |
| `scripts/development/generate-cambridge-swift-contract.py` | Generate only the committed Swift contract file; `--check` compares without writing. |
| `scripts/development/generate-cambridge-sender-modes.py` | Generate/check Android and Swift typed mode declarations from the shared sender catalog. |
| `scripts/development/generate-ios-version.py` | Generate/check the committed iOS version xcconfig from root `VERSION`. |
| `scripts/sender/ios/check-core.sh` | Use installed Swift or pinned official Swift container; run format-independent package tests and parity checks. |
| `scripts/sender/ios/check-xcode.sh` | On macOS, build/test the committed project without signing and select an available simulator deterministically. |
| `scripts/sender/ios/cambridge-swift-fixture/` | Test-only executable/adapter that sends core-produced control and RTP to the native receiver. |
| `.github/workflows/ios-core.yml` | Linux package/parity job with pinned Swift. |
| `.github/workflows/ios.yml` | macOS Xcode build/test job with a recorded Xcode selection. |

Scripts must resolve paths from their own location, use `set -euo pipefail`,
avoid workstation-specific names, and print the retained log directory when a
runtime test fails.

## Module ownership and dependency direction

`CamBridgeCore` is a platform-neutral Swift package. It may import Foundation,
but MUST NOT import SwiftUI, UIKit, AVFoundation, VideoToolbox, Network, OSLog,
or other Apple-only UI/media frameworks. It owns:

- generated contract constants;
- validated control-message models;
- control JSON encoding and decoding;
- incremental big-endian control framing;
- H.264 AVCC/Annex-B parsing and normalization;
- RFC 6184 RTP/H.264 packetization;
- stream geometry and orientation value types;
- immutable stream configuration and session identity;
- deterministic session state-transition rules;
- bounded queue policies expressed independently of camera APIs.

The `CamBridge` app target owns all Apple-platform adapters. It may depend on
`CamBridgeCore`; the core package must never depend on the app target.

Views depend on presentation models and immutable view state. Views MUST NOT
open sockets, configure capture sessions, call VideoToolbox, persist settings,
or encode protocol messages.

Presentation models depend on small service protocols. Platform services
implement those protocols. Dependency construction occurs in `AppEnvironment`
and the SwiftUI `App` entry point. Do not introduce a global mutable service
locator.

## Concurrency and state ownership

The implementation must compile with strict concurrency checking enabled.

- UI-observed models are `@MainActor` and use `@Observable`.
- `CaptureService` is an actor and exclusively owns `AVCaptureSession`, active
  inputs, outputs, device configuration, and capture lifecycle.
- `VideoEncoder` exclusively owns the `VTCompressionSession` and the lifetime
  of its callback context.
- `ReceiverBrowser` exclusively owns `NWBrowser`.
- Each TCP or UDP `NWConnection` has one owning service and one serialized event
  context.
- `StreamSessionCoordinator` is an actor and is the sole owner of the active
  session state machine.
- Types crossing actor boundaries are immutable `Sendable` values.
- Continuations must be resumed exactly once and cancellation handlers must
  release their underlying Apple operation.
- Delegates and C callbacks must immediately move data into their owning
  serialized context; they must not mutate UI state directly.

The following Apple objects are non-transferable implementation details:
`AVCaptureSession`, `AVCaptureDevice`, `CMSampleBuffer`, `CVPixelBuffer`,
`VTCompressionSession`, `NWBrowser`, and `NWConnection`. Do not place them in
core models, mark them `@unchecked Sendable` merely to silence the compiler, or
pass them between unrelated actors. A captured pixel buffer is submitted to the
encoder on the capture pipeline's owning serial executor before the capture
callback returns. Only copied, immutable encoded `Data` plus scalar timestamp
and keyframe metadata crosses into the RTP delivery actor.

Use the serial-executor pattern demonstrated by Apple's current AVCam sample
for capture-session configuration and delegate callbacks. If the compiler or
SDK version changes the exact executor API, preserve the ownership rule and
verify it in macOS CI; do not fall back to main-thread capture configuration.

The application-level stream states are exactly:

```text
Idle → Connecting → Streaming → Stopping → Idle
                  ↘ Failed ───────────────→ Idle after explicit retry/stop
```

Receiver discovery/readiness is separate from stream state. Discovery can be
checking, selection-required, available, or unavailable without pretending a
stream is active.

Only one start or stop operation may execute at a time. Repeated taps, view
recreation, scene notifications, and cancellation must not create two capture
sessions, two control sessions, or two cleanup operations.

## Control-plane contract

The Swift control implementation must support all v6 message types:

- `probe`
- `capabilities`
- `hello`
- `accepted`
- `stop`
- `error`

Messages use compact UTF-8 JSON preceded by the contract-defined unsigned
32-bit big-endian byte length. The decoder must be incremental: one network
read may contain part of a header, part of a body, one frame, or multiple
frames. It must reject empty, oversized, malformed, incompatible, and
unexpected messages without unbounded allocation.

Control models must use explicit coding keys for wire names. Incoming messages
must be validated after decoding; synthesized `Codable` alone is not sufficient
because it can ignore unknown fields and bypass domain initializers.

The probe flow is side-effect-free:

1. Create a unique request ID.
2. Open a TCP control connection with the contract timeout.
3. Send `probe`.
4. Receive one `capabilities` or `error` response.
5. Validate version, type, request ID, receiver identity, and geometry bounds.
6. Close the probe connection.

The stream-start flow is:

1. Confirm camera permission, a selected receiver, and a supported immutable
   stream configuration.
2. Resolve final coded geometry and clockwise display rotation.
3. Allocate a unique session ID and a monotonically increasing generation.
4. Prepare camera and encoder resources without emitting media.
5. Open the TCP control connection and keep it open for the session.
6. Send `hello` containing the exact phone-authored configuration.
7. Receive and validate `accepted` or fail the start.
8. Validate protocol version, session ID, generation, profile ID, receiver
   geometry, and returned media port.
9. Create the connected UDP media path to the returned port.
10. Start control reading, encoding, capture, and RTP delivery.
11. Publish `Streaming` only after all required resources are active.

The persistent TCP connection is a session lease. EOF, failure, incompatible
input, or a receiver `error` during streaming is terminal. Cleanup follows and
the state becomes `Failed`; the app does not reconnect automatically.

Stopping must be idempotent. It stops capture, prevents new encoder output from
entering the RTP queue, cancels media delivery, sends a best-effort matching
`stop`, closes control, invalidates VideoToolbox resources, clears bounded
queues, releases camera resources, and returns to `Idle`. Cleanup must still
finish if the stop message cannot be delivered.

## Receiver discovery

Discovery runs only for the Stream Setup lifecycle and uses `NWBrowser` for the
contract Bonjour service type. The app Info.plist must include:

- a clear `NSLocalNetworkUsageDescription`;
- `_cambridge._tcp` in `NSBonjourServices`;
- a clear `NSCameraUsageDescription`.

Each Bonjour result may produce the service endpoint resolved by
Network.framework plus bounded IPv4 candidates from the contract `address<N>`
TXT metadata. The implementation must validate the TXT key index and IPv4
unicast address before using it. It must not scan ports, infer a port when an
SRV record supplies one, or treat discovery as proof of compatibility.

Discovered and manually configured endpoints are probed. Successful results
are deduplicated by `receiverId`, not merely by host string. A saved receiver is
preferred by receiver ID; a single available receiver is selected
automatically; multiple unmatched receivers require explicit selection.

Manual entry remains available when Bonjour is denied, unavailable, or unable
to traverse the current network. A manual endpoint uses the contract default
control port unless the product later exposes an advanced port setting.

## Camera and video-mode capability model

The app defaults to a rear camera. It discovers rear camera devices and their
supported formats through AVFoundation rather than assuming all iPhones have
the same lenses or modes.

A mode is offered only when all of these are true:

1. The selected camera can deliver the exact coded dimensions.
2. A supported frame-rate range contains the requested fixed FPS.
3. The format can sustain the requested frame duration.
4. VideoToolbox can create and prepare a real-time hardware H.264 encoder for
   the coded dimensions and FPS.
5. The stepped bitrate range intersects the encoder and product bounds.
6. The selected receiver supports both coded and rotated display geometry.

Unsupported modes are disabled or omitted with a diagnostic reason. The app
must not silently downgrade an accepted selection. Changing resolution, FPS,
bitrate, selected camera, or orientation while streaming requires Stop and a
new Start.

Do not invent a 2560×1440 scaling path solely to make an iPhone claim Android
mode parity. First use exact output formats supported by AVFoundation. A future
explicit pixel-transfer/scaling feature requires its own bounded-latency and
physical-device validation because it adds memory bandwidth and GPU/ISP work.

The capability probe must be reusable by setup UI and start validation so the
UI cannot offer one answer while the engine enforces another.

## Camera behavior

`CaptureService` owns:

- camera authorization state;
- rear-camera discovery and selection;
- active format and fixed frame-duration configuration;
- `AVCaptureVideoDataOutput` producing a native bi-planar YUV format suitable
  for VideoToolbox;
- `AVCaptureVideoPreviewLayer` integration through a narrowly scoped UIKit
  bridge;
- zoom bounds and active zoom;
- supported stabilization modes and the observed active mode;
- session interruption and runtime-error notifications;
- system-pressure and thermal diagnostics.

The video data output must discard late frames. The capture callback must not
wait for networking and must not build an unbounded buffer. Preview is not fed
from encoded frames and must remain responsive when network output drops.

Camera controls use iOS-native semantics:

- Zoom uses the active device's supported video zoom bounds.
- Lens/camera choices use public AVFoundation device discovery and stable
  user-facing labels.
- Stabilization choices are Off plus the supported iOS video stabilization
  modes appropriate to the active format. The UI reports the observed active
  mode rather than assuming the request succeeded.
- Android's explicit optical/electronic terminology must not be reused where
  AVFoundation does not expose an equivalent guarantee.
- An anti-flicker selector is not shown unless a public AVFoundation API can
  enforce and observe the requested behavior. Do not simulate support with an
  unrelated exposure setting.

## Orientation and preview

The selected stream orientation is immutable for one session and supports all
four right-angle orientations. The encoded coded dimensions remain those in
the selected profile. The sender reports the clockwise rotation needed by the
receiver; 90 and 270 degrees swap display geometry.

The capture output must not accidentally rotate pixel buffers while also
reporting rotation metadata, which would rotate twice at OBS. Preview
orientation is configured independently from encoded-buffer geometry. Preview
mirroring must also be independent from transmitted mirroring; no transmitted
mirror is added unless explicitly introduced as a product feature.

Orientation calculations require table-driven tests for every supported
orientation and camera position. The streaming interface should be locked to
the selected session orientation while active and restored after cleanup.

## VideoToolbox encoder contract

The encoder accepts timestamped native pixel buffers and emits complete H.264
access units. It must configure:

- H.264 codec type;
- real-time encoding;
- expected frame rate;
- average bitrate and bounded data-rate limits derived from the selected mode;
- frame reordering disabled;
- no B-frame dependency;
- the contract keyframe interval;
- hardware acceleration required for product modes;
- color metadata consistent with the input pixel buffers.

Every VideoToolbox status is checked and mapped to a structured failure. The
compression session is prepared before capture begins and invalidated exactly
once during cleanup.

VideoToolbox H.264 output must be normalized as follows:

1. Inspect the sample attachments to determine keyframe status.
2. Read the NAL length-field size and parameter sets from the format
   description; do not assume a length-field size without checking it.
3. Parse every length-prefixed NAL with overflow and bounds checks.
4. Convert the access unit to Annex-B start-code form.
5. Prepend the current SPS and PPS to every keyframe access unit.
6. Reject empty, malformed, or contract-oversized access units.

Parameter sets are encoder output, not hand-authored configuration. They are
refreshed when VideoToolbox supplies a changed format description.

## Bounded media path

The sender must preserve the repository's latency-first policy:

```text
AVCaptureVideoDataOutput
    → VideoToolbox
    → bounded newest-access-unit queue
    → RTP/H.264 packetizer
    → connected UDP NWConnection
```

The encoded queue uses the contract in-flight bound. When full, it discards
stale queued work and retains the newest complete access unit. It records the
drop and maximum occupancy. It never partially replaces an access unit.

The sender must not retain camera pixel buffers longer than required by
VideoToolbox submission. UDP send completions must not retain the entire capture
pipeline. Backpressure must result in bounded drops or a terminal transport
failure, never growing memory.

## RTP/H.264 contract

The core packetizer implements RFC 6184 non-interleaved H.264 transport matching
the Android sender and native receiver:

- RTP version 2;
- contract dynamic payload type;
- contract clock rate;
- one SSRC per stream session;
- a random initial 16-bit sequence number;
- timestamp conversion from presentation time to the RTP clock with defined
  wraparound;
- single NAL packets when the NAL fits;
- FU-A fragmentation when it does not;
- no STAP-A aggregation;
- marker bit only on the final packet of the complete access unit;
- every datagram at or below the contract MTU;
- sequence wraparound from the maximum value to zero;
- maximum access-unit enforcement before allocation or packetization.

The packetizer receives an Annex-B access unit and yields complete datagrams or
passes each complete datagram to a throwing send closure. It does not own a
socket and does not import Network.framework.

## SwiftUI product flow

The app contains three feature areas matching the Android product structure.

### Stream Setup

- Starts lifecycle-scoped receiver discovery.
- Shows receiver checking, selection-required, ready, and unavailable states.
- Supports explicit receiver selection and manual host fallback.
- Probes the selected receiver before enabling Start.
- Shows only phone/encoder/receiver-supported resolution and FPS combinations.
- Provides bounded stepped bitrate selection.
- Provides four stream orientations.
- Provides supported iOS stabilization choices.
- Starts only after camera permission, receiver readiness, and configuration
  validation succeed.

### Webcam

- Shows the live camera preview.
- Shows connection and active-mode status.
- Provides supported rear-camera/lens selection before a new session where a
  live switch cannot preserve immutable geometry.
- Provides zoom and stabilization controls where AVFoundation permits a safe
  live update.
- Provides a screen-dimmed presentation that darkens app content without
  backgrounding or allowing automatic device lock.
- Requires confirmation before stopping through navigation or dismissal.
- Exposes copyable diagnostics for failures.

### Settings

- Shows persisted receiver and stream preferences.
- Shows app, protocol, and build versions.
- Exposes diagnostics without exposing credentials or private signing data.
- Does not mutate immutable active-session configuration; it requires Stop
  first.

SwiftUI views should be small declarative compositions. Reusable controls live
inside their feature folder until a demonstrated cross-feature use justifies a
shared component.

## Persistence

Use `UserDefaults` behind `SenderSettingsStore`. Persist only stable product
preferences:

- selected supported profile/mode identifier;
- bitrate;
- stream orientation;
- stabilization preference;
- selected receiver ID, display name, host, and control port.

Loaded values are validated against the current contract and current device
capabilities. Invalid or no-longer-supported values fall back to a documented
default and are not allowed to crash startup.

Do not persist session IDs, generations, SSRCs, active sockets, permissions,
encoder objects, Apple Account information, or signing information.

## Lifecycle and interruptions

The app observes SwiftUI scene phase and AVFoundation interruption/runtime
notifications.

- Entering the background while connecting or streaming triggers terminal
  cleanup and a best-effort `stop`.
- Returning to the foreground does not automatically restart.
- Camera use by another client, system-pressure interruption, encoder failure,
  control EOF, and terminal network failure produce a typed failure and clean
  resources.
- Temporary UI overlays that leave the scene active must not accidentally stop
  the session.
- The app disables idle display sleep only for an active or connecting stream
  and restores the previous behavior during every cleanup path.

Apple documents normal camera use as unavailable in the background; the app
must not claim a VOIP or unrelated background mode to bypass that constraint.

## Diagnostics

Use `Logger` with the app bundle identifier as subsystem and separate categories
for app, discovery, control, camera, encoder, RTP, and session. Values that can
identify a user's network are private by default.

Each run has a run ID; each accepted stream has its session ID and generation.
Structured diagnostics include:

- app version, build, protocol version, iOS version, device model;
- selected camera and active format;
- coded/display geometry, rotation, FPS, and bitrate;
- requested and active stabilization;
- encoder identity and configured properties that can be queried safely;
- encoded access units, keyframes, bytes, queue occupancy, and queue drops;
- RTP packets/bytes, UDP failures, and maximum send duration;
- control state transitions and terminal failure category;
- capture and encoder frame cadence summaries;
- first encoded access unit and first sent RTP access unit milestones.

Do not log full video payloads, parameter-set bytes, Apple Account identifiers,
or signing material. Diagnostics shown to the user should be copyable plain text
or JSON with sensitive network values redacted.

## Test contract

### Linux Swift package tests

`CamBridgeCore` must build and test on Linux with Swift Package Manager. Tests
must cover at least:

- generated contract parity and stale-generation failure;
- every control message round trip;
- exact field names for contract examples;
- rejection of incorrect versions, types, identities, bounds, unknown fields,
  malformed JSON, and oversized input;
- big-endian framing with partial header, partial payload, multiple frames,
  empty frames, and oversized frames;
- geometry for all four rotations;
- AVCC-to-Annex-B conversion with supported NAL length sizes;
- SPS/PPS extraction and keyframe insertion;
- malformed and truncated H.264 input;
- one-NAL RTP packets;
- FU-A start, middle, and end packets;
- marker placement across multi-NAL access units;
- RTP timestamps and sequence wraparound;
- MTU and access-unit bounds;
- deterministic session-state transitions and idempotent stop;
- bounded newest-item queue behavior.

Use Swift Testing for value-level tests. Tests must use named fixture values and
must not depend on execution order.

Create these suites rather than one monolithic test file:

```text
ContractGenerationTests.swift
VideoGeometryTests.swift
ControlMessageCodecTests.swift
ControlFrameCodecTests.swift
AVCDecoderConfigurationTests.swift
H264AccessUnitNormalizerTests.swift
RTPPacketTests.swift
RTPH264PacketizerTests.swift
StreamStateMachineTests.swift
NewestItemBufferTests.swift
```

Port behavior, not Kotlin test syntax. Shared golden messages and packet bytes
belong in named Swift fixtures under the test target. Do not load test data from
an Android build output.

### Cross-language receiver tests

Add a test-only Swift fixture executable that uses `CamBridgeCore` to create
control frames and RTP datagrams. On Linux it may use a small test-only POSIX
transport adapter. It must be able to feed an Annex-B H.264 fixture into the
real native receiver path.

The integration harness must verify:

- probe compatibility;
- hello acceptance;
- media-port validation;
- session acceptance in OBS logs;
- packet receipt and H.264 decode;
- first-frame presentation;
- portrait rotation metadata;
- clean explicit stop and receiver invalidation.

The production iOS app must not use the POSIX fixture adapter.

### Apple-platform automated tests

macOS GitHub Actions must run unsigned simulator compilation and tests using a
committed scheme. Tests cover:

- app dependency construction;
- presentation-state mapping;
- discovery result/TXT parsing with fixtures;
- Network.framework adapters through injected fakes;
- camera format and bitrate capability reducers with recorded descriptors;
- scene lifecycle and interruption reducers;
- SwiftUI navigation and Start/Stop enablement without a real camera.

UI tests must not pretend the simulator validates camera or hardware encoding.

### Physical-device validation

One real iPhone and the real Linux OBS plugin are required before declaring iOS
supported. Record:

- iPhone model and iOS version;
- Xcode version;
- discovered camera formats and selected encoder;
- 1080p30 operation;
- every additional offered resolution/FPS combination;
- all four orientations;
- preview/OBS orientation agreement;
- each exposed lens/camera and zoom range;
- requested versus active stabilization;
- Start, Stop, background, interruption, control loss, and restart behavior;
- at least one sustained session with queue/drop/thermal diagnostics;
- OBS hardware decode and CPU fallback where the Linux host permits both.

Unsupported device modes are acceptable when correctly omitted. Offering a mode
that repeatedly fails on the validating device is not acceptable.

## CI and repository checks

Add an iOS-core workflow for Linux Swift package tests and a macOS workflow for
Xcode build/test. Path filters include the iOS sender, protocol contract,
sender-mode catalog, relevant scripts, and the workflow itself.

Repository validation must:

- check generated Swift contract output without rewriting it;
- check shared sender video-mode parity when that catalog is introduced;
- run `swift test` for `CamBridgeCore`;
- build the native receiver tests used for interoperability;
- leave Android and native checks unchanged except for intentional shared
  catalog generation/parity work;
- ensure `git status` remains clean after generators run in check mode.

The complete local check may use an installed Swift toolchain or a pinned
official Swift container image. The selected version must also be recorded in
CI rather than using an unpinned `latest` tag.

## Implementation sequence

The implementation agent must work in this order so platform code is built on
tested boundaries. These are development checkpoints, not permission to stop
with a reduced product.

### 1. Project and core boundary

- Add the native Xcode project, configurations, shared schemes, app resources,
  local package, initial CI, and iOS README.
- Add signing-safe build settings with no development team.
- Add camera/local-network/Bonjour usage descriptions.
- Verify the empty app and package compile in macOS CI before accumulating
  Apple-only code.

### 2. Contract and control core

- Generate Swift contract constants from the JSON contract.
- Implement validated message models, JSON codec, and incremental frame codec.
- Extend parity checks and add complete Linux tests.

### 3. H.264 and RTP core

- Implement AVCC/Annex-B normalization and parameter-set handling.
- Implement the independent RTP/H.264 packetizer.
- Port the meaningful Android and native RTP test vectors and add malformed
  input coverage.

### 4. Session domain

- Implement immutable configuration, session identity, generation allocation,
  typed failures, deterministic lifecycle transitions, and bounded queue
  policies.
- Test concurrency-independent rules in `CamBridgeCore`.

### 5. Discovery and control adapters

- Implement `NWBrowser`, TXT candidates, probe coordination, manual fallback,
  candidate deduplication, persistent control connection, and timeouts.
- Exercise adapters through fakes in macOS CI.

### 6. Camera capability and preview

- Implement permission, rear-camera discovery, exact format/FPS capability
  selection, preview, zoom, stabilization observation, orientation, and
  interruptions.
- Keep camera ownership inside `CaptureService`.

### 7. VideoToolbox and live media

- Implement encoder capability probing, live encoder, access-unit
  normalization, bounded queue, UDP sender, telemetry, and terminal failure
  handling.
- Connect the complete start/stop lifecycle without weakening validation.

### 8. Complete SwiftUI product flow

- Implement Stream Setup, Webcam, Settings, confirmation dialogs, permission
  UX, receiver selection, camera controls, dim-screen behavior, diagnostics,
  persistence, accessibility labels, and dynamic type behavior.

### 9. Interoperability and hardening

- Add the Swift-to-native Linux fixture.
- Add stress, cancellation, repeated Start/Stop, malformed receiver response,
  and lifecycle tests.
- Update architecture, development, installation, and known-limitations docs.

### 10. Physical install candidate

- Require all Linux and macOS CI checks to be green.
- Produce a concise Mac-lab checklist and expected capability report.
- Install and validate on the physical iPhone and OBS receiver.
- Fix evidence-backed device issues before marking support complete.

## Mandatory stage exit gates

The agent must not advance past a row until every listed result is true. If a
command is introduced by that stage, add it before declaring the gate complete.

| Stage | Required exit evidence |
| --- | --- |
| 1 | `swift package dump-package` and an empty-package test pass through `check-core.sh`; the committed Xcode project builds in macOS CI with `CODE_SIGNING_ALLOWED=NO`; no team ID exists in tracked files. |
| 2 | Contract generation `--check`, sender-mode generation `--check`, iOS version generation `--check`, the existing contract checker, Android unit tests affected by catalog extraction, and all control-core Swift tests pass. |
| 3 | All H.264/RTP Swift tests pass, including Android-equivalent vectors and malformed inputs; no datagram exceeds the contract MTU. |
| 4 | State-machine and bounded-buffer tests pass under repeated start/stop/failure sequences; package tests pass with strict concurrency enabled. |
| 5 | Fake-driven discovery/control tests and macOS app tests pass; probes close their connection and stream control remains open; cancellation tests show no double resume. |
| 6 | The Apple target compiles with no concurrency errors; recorded-format unit fixtures pass; preview/camera code has no network or protocol dependency. |
| 7 | Encoder and media pipeline compile in macOS CI; synthetic callback tests prove parameter-set insertion, queue bounds, cleanup, and terminal transport failure behavior. |
| 8 | UI model, navigation, permission, manual receiver, selection, start enablement, stop confirmation, and background reducers pass; accessibility identifiers exist for emulator-safe UI tests. |
| 9 | Swift-to-native fixture presents a frame and cleans the session; `check-all.sh`, `check-core.sh`, and `check-xcode.sh` all pass in their supported environments; docs are updated but public iOS support remains unchanged. |
| 10 | The pre-install gate below is green, then the physical-device matrix and retained diagnostics pass. |

The steady-state verification commands are:

```bash
python3 scripts/development/generate-cambridge-swift-contract.py --check
python3 scripts/development/generate-cambridge-sender-modes.py --check
python3 scripts/development/generate-ios-version.py --check
python3 scripts/development/check-cambridge-stream-contract.py
./scripts/sender/ios/check-core.sh
./scripts/receiver/linux/build-cambridge-obs-plugin.sh
./scripts/development/check-all.sh
```

On macOS, also run:

```bash
./scripts/sender/ios/check-xcode.sh
```

Every script must be noninteractive in CI. Before handoff, also run:

```bash
git diff --check
git status --short
```

`git status --short` may list only the implementation changes intended for the
handoff; generated build products and signing files must not appear.

## Pre-install acceptance gate

Before using limited Mac-lab time for a physical install, all of these must be
true:

- the worktree is clean;
- generated contract and sender catalog checks pass;
- all `CamBridgeCore` Linux tests pass;
- the Swift fixture interoperates with the native receiver on Linux;
- existing Android and native checks pass;
- macOS CI builds the iOS app for an unsigned simulator destination;
- macOS CI runs package and application tests;
- the committed Xcode project and shared scheme are the ones CI used;
- no signing team or credential is required for CI;
- the remaining unverified items are explicitly limited to physical camera,
  hardware encoder, local-network permission, signing, and glass-to-glass
  behavior.

## Mac-lab handoff

The developer should only need to:

1. Clone the repository and open `sender/ios/CamBridge.xcodeproj`.
2. Confirm the installed Xcode version meets the recorded project requirement.
3. Add the Apple Account in Xcode Settings if it is not already present.
4. Select the CamBridge target, enable automatic signing if necessary, and
   select the local Personal Team or paid team.
5. Change the bundle identifier locally only if Xcode reports a collision.
6. Connect and trust the iPhone, enable Developer Mode if iOS requests it, and
   select the phone as the Run destination.
7. Run the test action, then install the Debug app.
8. Grant camera and local-network access.
9. Export the app capability report before the first stream.
10. Run the physical-device validation matrix and retain phone/OBS diagnostics.

No password, two-factor code, signing certificate, provisioning profile, or
private key is part of the implementation-agent handoff.

## Completion definition

The iOS sender is complete only when:

- the full required UI and lifecycle exist;
- the iOS app interoperates with the unchanged v6 receiver;
- unsupported hardware choices are capability-gated rather than guessed;
- memory and latency queues remain bounded;
- Linux, macOS CI, and physical-device validation pass;
- installation and development documentation are current;
- README platform support and release packaging are updated only after the
  physical-device evidence exists.

## Copyable implementation-agent directive

Use this exact instruction when handing the work to an implementation agent:

> Implement the complete iOS sender defined in
> `internal/implementation-contracts/ios-sender.md`. Read that file, `AGENTS.md`,
> the v6 JSON/schema/docs, Android sender lifecycle/RTP code, and native
> control/RTP tests before editing. Put all ten stages in your working plan and
> execute them in order. Treat every MUST, required file/type, forbidden
> shortcut, stage exit gate, and pre-install gate as acceptance criteria. Do
> not stop at a skeleton or MVP, do not change the wire protocol or OBS
> receiver to make iOS easier, do not commit signing data, and do not claim
> Apple-only compilation without macOS CI evidence. Continue until every
> pre-install check that does not require a physical iPhone passes, then return
> the retained CI/test evidence and the Mac-lab checklist.
