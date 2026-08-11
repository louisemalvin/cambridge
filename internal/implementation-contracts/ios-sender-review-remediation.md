# iOS Sender Review Remediation Contract

Internal status: implementation hold and remediation handoff. This document may
be written and reviewed before the hold is released, but it does not authorize
an iOS implementation change while the macOS receiver baseline remains local
only.

This contract addresses the findings from the pre-Mac-lab review of the iOS
sender. It supplements, and does not replace,
`internal/implementation-contracts/ios-sender.md`.

## Objective

Turn the current iOS sender into an evidence-backed physical-install candidate
without changing the macOS receiver, the Linux receiver, the CamBridge v6 wire
contract, or another sender platform.

The implementation MUST NOT begin until the current macOS receiver work is in
a clean commit history and the intended receiver tip is proven to be present on
the intended remote branch. Preparing this document is the only remediation
activity allowed before that entry gate passes.

## Normative language

`MUST`, `MUST NOT`, `SHOULD`, and `SHOULD NOT` describe implementation
requirements. A `SHOULD` may be violated only when the handoff records a
specific Apple-platform or CI-platform constraint and supplies equivalent
behavior and evidence.

## Relationship to the original implementation contract

The original iOS sender contract remains authoritative for product scope,
architecture, the protocol boundary, and physical-device behavior. This
document is authoritative for the reviewed defects, the receiver-push hold,
the remediation sequence, and the additional acceptance evidence.

If these documents appear to conflict:

1. The JSON wire contract remains authoritative for protocol behavior.
2. The no-receiver-change and receiver-push hold in this document wins for this
   remediation.
3. The original iOS contract wins for all unaffected product requirements.
4. The implementation agent MUST stop and report any remaining material
   conflict rather than silently selecting a weaker requirement.

## Review snapshot

At contract authoring time, the repository had this relevant state:

- local branch: `main`;
- reviewed local tip: `015727b`;
- observed `origin/main`: `baa66f35d2324175a995e8ae1f260c9c649dc689`;
- local commits not yet reachable from the observed `origin/main`: 21;
- local worktree before adding this document: clean;
- local `main` had no configured upstream in `git branch -vv`;
- the iOS source tree at the local tip matched the iOS source tree at the
  observed `origin/main`.

These values describe the review, not the future entry-gate result. The
implementation agent MUST fetch and record fresh values instead of assuming
that these references are still current.

## Absolute entry gate: receiver committed and pushed

No production code, test code, Xcode project, CI workflow, resource, or iOS
documentation remediation may be edited until every item below is true.

1. The owner identifies the exact macOS receiver baseline commit as
   `RECEIVER_BASELINE_SHA` and the intended remote ref as
   `RECEIVER_REMOTE_REF`. The expected default is `origin/main`, but the agent
   MUST record the actual ref rather than infer it.
2. All intended macOS receiver work is committed. No receiver change is left in
   the index, worktree, or untracked-file set.
3. This contract is committed or otherwise removed from the worktree before
   implementation begins, so the implementation starts from a genuinely clean
   checkout.
4. The intended receiver commit has been pushed successfully.
5. A fresh fetch proves that `RECEIVER_BASELINE_SHA` is reachable from
   `RECEIVER_REMOTE_REF`.
6. The remediation branch is created from that verified receiver baseline, or
   from a later commit which contains it.
7. The worktree is clean immediately before the first implementation edit.

The minimum proof is equivalent to:

```bash
git status --short
git fetch origin
git rev-parse "${RECEIVER_BASELINE_SHA}^{commit}"
git rev-parse "${RECEIVER_REMOTE_REF}^{commit}"
git merge-base --is-ancestor "${RECEIVER_BASELINE_SHA}" "${RECEIVER_REMOTE_REF}"
git diff --quiet
git diff --cached --quiet
```

The agent MUST also inspect untracked files through `git status --short`; the
two `git diff` commands do not cover them. A successful local commit, an empty
`git diff`, or a successful `git push` message alone is not proof that the
intended remote ref contains the commit.

If the gate is closed, the agent MUST stop after reporting the failing item. It
MUST NOT begin an “independent” iOS fix, stage iOS changes for later, or modify
receiver files to help close the gate.

## Scope freeze

### Allowed remediation scope after the entry gate

Only the following areas may be changed, and only where a stage below requires
the change:

- `sender/ios/**`;
- `scripts/sender/ios/**`;
- `.github/workflows/ios.yml` and `.github/workflows/ios-core.yml`;
- iOS-specific generation or validation code under `scripts/development/**`
  when an iOS artifact genuinely requires it;
- iOS-specific documentation;
- this remediation contract.

### Forbidden scope

The remediation MUST NOT edit:

- `receiver/**`;
- `scripts/receiver/**`;
- `.github/workflows/native.yml`;
- macOS receiver packaging, release metadata, entitlements, implementation, or
  documentation;
- `protocol/cambridge-stream-contract.json`, its schema, or v6 semantics;
- Android application behavior or Android resources;
- public support claims or release artifacts before physical-device evidence
  exists.

Existing receiver and cross-platform checks may be run read-only after the
entry gate. If one fails, retain and report the evidence. Do not fix, revert,
or reformat receiver code under this contract.

## Fixed remediation decisions

- The receiver remains an unchanged compatibility target.
- The protocol remains CamBridge v6 and retains its trusted-local-network,
  unauthenticated, unencrypted security model.
- There is no audio, relay, transcoding, scaling, automatic reconnect, or new
  stream mode in this remediation.
- iOS remains iPhone-first with an iOS 17 deployment target.
- Hardware H.264 through VideoToolbox remains mandatory.
- Exact phone camera formats remain capability-gated; unsupported modes are not
  approximated.
- Signing stays local. No team ID, Apple Account, certificate, provisioning
  profile, password, token, or private key is committed.
- CI may use an unsigned simulator. Physical camera, hardware encoder,
  local-network permission, and signing claims require a real iPhone.
- Queue and datagram bounds MUST NOT be weakened to hide throughput or timing
  failures.
- No finding may be closed with a `TODO`, disabled test, hard-coded test-only
  success in production, unexplained sleep, or undocumented retry increase.
- New numeric limits and conversion factors MUST be named or derived from an
  authoritative contract or platform unit.

## Reviewed findings and required dispositions

| Priority | Finding | Required disposition |
| --- | --- | --- |
| P0 | `VideoToolboxEncoder.setProperties` supplies `kVTCompressionPropertyKey_DataRateLimits` as a nested array and uses bits where Apple requires bytes. | Supply the exact flat alternating byte-count/window array with checked unit conversion and a deterministic unit test. |
| P0 | The `iOS app` workflow selects `/Applications/Xcode_16.4.app` on `macos-14`; the observed run failed before any Swift or Xcode check executed because that path did not exist. | Select a runner/toolchain combination that exists, record it, and obtain a real unsigned app build and test result. |
| P0 | The Mac-lab checklist requires a capability report before streaming, but the app exposes only post-cleanup session diagnostics. | Add a pre-stream, copyable capability report containing the required device, camera, mode, encoder, stabilization, and zoom evidence. |
| P1 | Encoder capability lookup discards `encoderIDOut`, while live metrics assign the generic string `VideoToolbox-H264`. | Capture and report the actual VideoToolbox encoder identifier; represent an unavailable identifier as unavailable with a reason, not a fabricated identity. |
| P1 | Settings and Stream Setup each load persistence independently. Settings changes do not refresh Setup, and the attempted streaming-time reload occurs after Settings is marked active and is therefore ignored. | Establish one app-scoped preference source and prove changes propagate in both directions while active-stream mutation remains prohibited. |
| P1 | The UI test suite only verifies that the Start button exists after launch. | Add deterministic, dependency-injected UI scenarios covering the required navigation, permission, selection, start, stop, and recovery states. |
| P1 | The send loop packetizes a complete access unit and awaits Network.framework completion for each UDP datagram. This is bounded but has not been shown to sustain the highest offered mode. | Add deterministic backpressure/error tests and require physical 2K60 throughput evidence before support is claimed; refactor only if evidence shows the sequential path misses the acceptance bounds. |
| Distribution | The target has no reviewed AppIcon asset catalog configuration. | Add an approved complete app icon set before distribution; do not synthesize or silently choose brand artwork. |
| Distribution | The target has no privacy manifest despite persisted preferences using `UserDefaults`. | Audit current Apple required-reason API rules and include a correct target-owned privacy manifest before distribution. |

P0 means the Mac-lab install candidate is blocked. P1 means the corresponding
behavior and automated evidence must be completed before the physical matrix is
declared successful. Distribution findings do not prevent a local developer
install, but they block a distribution-ready claim.

## Implementation-agent operating rules

1. Read this document, the original iOS contract, `AGENTS.md`, the iOS source
   and tests named by the findings, and the authoritative protocol files before
   editing.
2. Put every remediation stage below in the plan, in order, with no more than
   one stage in progress.
3. Execute Stage 0 before opening or modifying an iOS implementation file.
4. Make small, reviewable stage commits. Do not combine receiver work with an
   iOS remediation commit.
5. At each stage boundary, inspect the full diff, run the stage checks, run
   `git diff --check`, and inspect changed production code for unexplained
   numeric literals.
6. Preserve existing behavior outside the finding being addressed.
7. Prefer a pure helper or injected adapter where Apple framework behavior
   otherwise prevents deterministic tests.
8. Tests MUST assert externally meaningful behavior, not private method names
   or a duplicate of the implementation expression.
9. A simulator pass MUST NOT be described as hardware encoder, camera,
   local-network, thermal, or performance evidence.
10. A failed remote or macOS-only check remains failed until a later retained
    run proves it passed. Do not infer success from local Linux checks.
11. If an implementation requires a receiver or protocol edit, stop and return
    the smallest reproducible evidence. That edit is outside this contract.
12. Do not commit generated build products, test result bundles, device logs,
    signing state, or machine-specific Xcode settings.

## Stage 0: release the receiver hold

Record in the handoff:

- `RECEIVER_BASELINE_SHA`;
- `RECEIVER_REMOTE_REF`;
- output of `git status --short --branch` before implementation;
- fetched remote commit SHA;
- successful ancestor check;
- remediation branch name and base SHA.

Exit gate: every absolute entry-gate requirement passes. No iOS implementation
diff exists yet.

## Stage 1: restore real macOS compilation evidence

Correct the iOS workflow so its selected Xcode installation actually exists on
its selected GitHub-hosted runner. The workflow MUST:

- record `sw_vers`, `xcode-select -p`, and `xcodebuild -version` before checks;
- use a supported runner/Xcode pairing rather than a stale filesystem guess;
- run `./scripts/sender/ios/check-core.sh`;
- run `./scripts/sender/ios/check-xcode.sh`;
- build and test the committed `CamBridge` scheme for an available iPhone
  simulator with signing disabled;
- retain or surface the useful Xcode failure log;
- fail when there is no available iPhone simulator;
- remain noninteractive and free of signing credentials.

If an explicit `DEVELOPER_DIR` remains, its existence MUST be checked with a
clear diagnostic and the runner image must be pinned to a compatible value. If
the workflow selects Xcode dynamically, the selection policy and observed
version MUST be recorded. Merely deleting `DEVELOPER_DIR` without proving which
toolchain ran is insufficient.

Add validation around the workflow or script where practical so a missing
developer directory produces a useful preflight failure rather than skipping
all compilation evidence.

Exit gate: a fresh macOS CI run reaches both scripts, the package checks pass,
and `xcodebuild test` succeeds for the unsigned simulator target. Retain the run
URL and Xcode version.

## Stage 2: correct the VideoToolbox data-rate limit

Apple defines `kVTCompressionPropertyKey_DataRateLimits` as a flat array of
alternating byte counts and time intervals. The current nested `[[Int]]` value
and bit-count calculation MUST be removed.

The implementation MUST:

- derive the allowed byte count from the configured bitrate in bits per second
  and a named window duration;
- use a named bits-per-byte conversion factor;
- use overflow-checked arithmetic;
- document and test the rounding rule if a future bitrate is not evenly
  divisible into bytes;
- produce a flat, two-element Core Foundation-compatible array for the one
  configured window: `[allowedBytes, windowSeconds]`;
- preserve `AverageBitRate`, real-time mode, disabled frame reordering, expected
  FPS, and keyframe properties;
- report the VideoToolbox property name and status when the property is
  rejected;
- avoid requiring a physical encoder for the representation/unit test.

At minimum, a test using 8,000,000 bits per second and a two-second window MUST
expect 2,000,000 bytes followed by `2` in one flat array. It MUST reject or
safely handle arithmetic overflow. The test MUST fail for the old nested shape
and for an eight-times-too-large bit count.

Use the official Apple property definition as the platform reference:

- <https://developer.apple.com/documentation/videotoolbox/kvtcompressionpropertykey_dataratelimits>

Exit gate: deterministic app-target tests cover shape, units, rounding, and
overflow; macOS CI builds the adapter and runs the tests; no physical encoder
claim is made yet.

## Stage 3: implement truthful pre-stream capability reporting

Add a deterministic `Codable` capability-report model separate from the
post-session `DiagnosticsReport`. It MUST be constructible before Start and
copyable from the setup or settings flow without first creating or cleaning up
a stream.

The report MUST include, as available:

- app version, build version, operating-system version, and device model;
- camera authorization state;
- every considered rear-camera descriptor, including stable in-app descriptor,
  human-readable name, position, and virtual-device state;
- the selected camera;
- minimum and maximum zoom factors for each offered camera;
- supported stabilization choices and the currently requested choice;
- every product mode considered;
- whether each mode is offered and, when it is not, a concrete reason;
- exact selected camera format identifier, coded dimensions, and frame-rate
  ranges for each offered mode;
- receiver capability constraints used in the offer decision, when a receiver
  is selected;
- encoder minimum and maximum bitrate for each offered mode;
- actual encoder identifier returned by VideoToolbox for each successful
  capability probe;
- selected mode, bitrate, orientation, and receiver display identity, when
  available;
- generation time and a report schema version.

Unique camera identifiers and network addresses MUST be treated consistently
with the existing diagnostics privacy policy. Receiver host data MUST remain
redacted. If raw camera identifiers are needed for the Mac-lab record, the UI
must label the report as local diagnostic data and the documentation must say
what is included.

The encoder probe MUST capture the actual identifier returned by the
VideoToolbox capability API instead of discarding `encoderIDOut`. A live encoder
must query the session's `kVTCompressionPropertyKey_EncoderID` or select the
probed encoder explicitly through
`kVTVideoEncoderSpecification_EncoderID`. `VideoToolbox-H264` is a
framework/codec label, not proof of the encoder selected. Required hardware
acceleration and actual hardware use MUST be separate facts; query
`kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder` rather than
setting the actual-use field to `true` merely because hardware was requested.
When Apple returns no identity or actual-use property, the report MUST use
`nil` or an explicit unavailable field plus a reason.

Official encoder-selection references:

- <https://developer.apple.com/documentation/videotoolbox/vtcopysupportedpropertydictionaryforencoder(width:height:codectype:encoderspecification:encoderidout:supportedpropertiesout:)>;
- <https://developer.apple.com/documentation/videotoolbox/kvtvideoencoderspecification_encoderid>;
- <https://developer.apple.com/documentation/videotoolbox/kvtcompressionpropertykey_encoderid>;
- <https://developer.apple.com/documentation/videotoolbox/kvtcompressionpropertykey_usinghardwareacceleratedvideoencoder>.

Report generation MUST NOT start camera capture, connect media UDP, allocate a
stream identity, or mutate persisted settings. It may perform the same bounded
capability probes already required to decide whether Start is enabled.

Tests MUST cover:

- multiple cameras and exact formats;
- supported and unsupported modes with reasons;
- encoder ranges and returned identity;
- absent encoder identity;
- stabilization and zoom bounds;
- no selected receiver;
- receiver-constrained modes;
- redaction and stable JSON encoding fields;
- an injected clock so generation-time tests remain deterministic;
- availability before any stream diagnostics exist.

Exit gate: a user can copy the capability report before Start, and fake-driven
tests prove all required fields and side-effect boundaries.

## Stage 4: establish one preference source of truth

Replace independent, one-time preference loads with one app-scoped observable
preference owner or an equivalent single-source design. `SettingsModel` and
`StreamSetupModel` MUST consume the same current state.

Required behavior:

- editing Settings while idle immediately updates the values shown and used by
  Stream Setup;
- changing setup selections and successfully starting a session persists the
  accepted values and makes them visible in Settings;
- entering `.connecting`, `.streaming`, or `.stopping` prevents user mutation
  of settings which would disagree with the active configuration;
- the transition ordering cannot set the active flag and then silently discard
  the refresh it intended to perform;
- returning to `.idle` or `.failed` restores editing using the latest persisted
  or app-owned values;
- an unsupported saved mode, bitrate, stabilization choice, camera, or receiver
  is validated and safely normalized when capabilities are refreshed;
- persistence failures or corrupt data continue to fall back safely;
- persistence remains bounded to the existing sender preferences and does not
  acquire receiver or protocol state.

Do not solve this by adding reciprocal model callbacks or repeated blind loads
at arbitrary route changes. The state owner and the moments at which validated
setup values are persisted MUST be explicit.

Tests MUST exercise Settings-to-Setup propagation, Setup-to-Settings
propagation, active-stream write rejection, transition back to editable state,
and invalid persisted values.

Exit gate: the tests fail against the old disconnected-model behavior and pass
with one observable preference state.

## Stage 5: make UI behavior deterministic and testable

Provide a test-only launch configuration that injects application adapters or
fixtures without using the real camera, Bonjour, TCP, UDP, or VideoToolbox. It
MUST be unavailable in Release behavior and MUST NOT provide a production
backdoor for enabling Start without validated capabilities.

The UI suite MUST cover at least:

1. setup launch and navigation to Settings and back;
2. camera permission not determined, denied/restricted, and authorized states;
3. no receiver, one discovered receiver, and multiple-receiver selection;
4. valid and invalid manual receiver input, success, and failure;
5. unsupported and supported mode presentation with reasons;
6. Start disabled until every required selection and capability is valid;
7. Start progress, successful transition to Webcam, and duplicate-Start
   prevention;
8. stop confirmation cancel and confirm paths;
9. terminal failure return to Setup and explicit retry/restart behavior;
10. Settings editing while idle and locking while a stream is active;
11. the pre-stream capability-report action;
12. stable accessibility identifiers for every interacted control.

Tests SHOULD use state-based waits rather than arbitrary sleeps. Launch
fixtures, accessibility identifiers, and failure messages MUST be named and
shared where doing so prevents string drift.

Keep model-level tests for lifecycle races, cancellation, backgrounding,
control failure, and terminal media failure. UI tests supplement those tests;
they do not replace them.

Exit gate: the full simulator UI suite passes repeatedly in macOS CI and no test
contacts real hardware or a receiver.

## Stage 6: complete the distribution metadata audit

This stage has two independently reported results.

### Privacy manifest

Audit every required-reason API used directly by the app and included code
against current Apple documentation. Because sender preferences use
`UserDefaults`, determine and record the applicable reason for the app's actual
use; do not copy a reason code without verifying that the declared use matches
it. Add a syntactically valid `PrivacyInfo.xcprivacy`, include it in the app
target, and test that it is present in the built product.

Official references:

- <https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api>
- <https://developer.apple.com/documentation/bundleresources/app-privacy-configuration>

### App icon

Add a complete AppIcon asset configuration using artwork approved by the
owner. Do not generate, reinterpret, or choose the CamBridge brand asset under
this implementation contract. Confirm the target's asset-catalog app-icon
setting selects the committed set and that Xcode reports no missing required
slots for the supported destinations.

If approved icon artwork is not supplied, report only the icon substage as
blocked. That does not invalidate a local developer-device install, but the app
MUST NOT be called distribution-ready.

Official reference:

- <https://developer.apple.com/documentation/xcode/configuring-your-app-icon>

Exit gate: the privacy audit and built-product check pass. The icon check either
passes with approved artwork or is explicitly recorded as the only remaining
distribution-artwork blocker.

## Stage 7: prove bounded media behavior before refactoring it

The current newest-access-unit queue is bounded, while packet transmission for
one retained access unit is sequential. Preserve the bounded-latency intent.

Before changing transport concurrency, add deterministic tests with an
injected datagram sender that can delay, fail, and cancel completions. Prove:

- only one complete newest access unit is retained according to the contract;
- slow sends produce bounded access-unit drops rather than unbounded memory;
- an access unit's RTP packets preserve packetizer order;
- cancellation resumes no continuation twice and closes the sender;
- a terminal send failure triggers one cleanup path;
- Start/Stop can be repeated after cleanup;
- datagrams never exceed the protocol MTU;
- transport metrics retain packet, byte, failure, and maximum-duration values.

Do not introduce an unbounded task per packet or an unbounded in-flight
datagram collection. If bounded pipelining is required by measured physical
performance, its concurrency limit, ordering rule, failure semantics, cleanup,
and metrics MUST be named and tested.

The physical test MUST attempt every mode the capability report offers. For
2K60, when offered, retain sustained-session evidence for encoded cadence,
queue maximum occupancy, queue drops, RTP packet/byte counts, maximum send
duration, CPU/thermal/system-pressure state, and OBS presentation. CPU evidence
may come from a retained Xcode Instruments run rather than app self-monitoring,
but the device, build, interval, average, and peak must be recorded.

The measurable physical thresholds are:

- exclude no more than the first ten seconds as warm-up;
- run every offered non-highest mode for at least three measured minutes;
- run the highest offered mode for at least ten measured minutes;
- maintain measured encoded cadence at or above 95 percent of requested FPS;
- keep complete-access-unit queue drops at or below 1 percent of encoded access
  units produced during the measured interval;
- observe no terminal encoder/transport failure, media-send timeout, critical
  thermal state, or serious-or-worse system pressure;
- observe no continuous OBS presentation freeze of one second or longer;
- keep every RTP datagram within the protocol MTU and preserve packet order.

These thresholds are fixed acceptance criteria. Changing one requires owner
approval and recorded device evidence; it is not a routine implementation
choice. A mode which misses a threshold on the tested device MUST not remain
offered on that device.

Exit gate before the Mac lab: deterministic backpressure/error tests pass and
the physical evidence fields can be exported. Final throughput disposition is
completed in Stage 9.

## Stage 8: full automated pre-install gate

Run from a clean checkout of the exact remediation commit:

```bash
python3 scripts/development/generate-cambridge-swift-contract.py --check
python3 scripts/development/generate-cambridge-sender-modes.py --check
python3 scripts/development/generate-ios-version.py --check
python3 scripts/development/check-cambridge-stream-contract.py
./scripts/sender/ios/check-core.sh
./scripts/sender/ios/check-fixture.sh
./scripts/development/check-all.sh
git diff --check
git status --short
```

On macOS, also run:

```bash
./scripts/sender/ios/check-xcode.sh
```

The fixture and repository checks may execute unchanged receiver code. A
receiver-side failure is reported as a blocker and MUST NOT be repaired in this
remediation.

The pre-install gate requires:

- all generators and parity checks pass;
- all CamBridgeCore tests pass;
- the Swift-to-native fixture passes without a receiver edit;
- repository-wide checks pass;
- the app and all app/UI tests pass in the selected macOS CI environment;
- the CI run uses the committed Xcode project and shared scheme;
- no signing identity is needed for CI;
- no signing or machine-specific files appear in the patch;
- all changed files are inside the allowed scope;
- no unexplained numeric literals were introduced;
- the worktree is clean after the remediation commit;
- the remediation commit is pushed and the recorded CI run tests that commit.

Exit gate: every item above is green. The only permissible unresolved item is
owner-supplied app icon artwork, which must be labeled as a distribution-only
blocker.

## Stage 9: physical Mac-lab validation

Update `sender/ios/MAC-LAB-CHECKLIST.md` so every action the app actually
supports is precise and discoverable. Then use the existing checklist plus
these remediation checks:

1. Record the remediation commit, Xcode version, iPhone model, and iOS version.
2. Export the capability report before Start and confirm it contains camera
   descriptors, zoom bounds, stabilization options, exact offered formats,
   encoder identities, and bitrate bounds.
3. Start every offered mode, including 1080p30 and 2K60 when offered.
4. Confirm requested bitrate no longer fails because of the DataRateLimits
   property and record any returned VideoToolbox status.
5. Exercise all rotations, offered rear cameras, zoom bounds, and stabilization
   choices.
6. Exercise explicit Start/Stop, backgrounding, interruption, control loss,
   terminal media failure where safely injectable, and restart after failure.
7. Confirm a Settings edit made while idle changes Setup, the active stream
   locks incompatible changes, and the accepted configuration appears in
   Settings after stopping.
8. Retain sustained diagnostics for each offered high-rate mode and compare
   encoded cadence, queue drops, send duration, thermals, and OBS behavior.
9. Repeat the receiver observation with hardware decode and CPU fallback where
   supported, without changing receiver code.
10. Record every omission or failure instead of removing it from the matrix.

The app is a successful physical-install candidate only when every capability
it offers on the tested phone completes the required matrix. A device-specific
unsupported mode may be omitted by truthful capability gating. A repeatedly
failing offered mode is a defect.

## Stage exit summary

| Stage | Required evidence |
| --- | --- |
| 0 | Receiver baseline SHA is committed, fetched, reachable from the recorded remote ref, and the implementation checkout is clean. |
| 1 | Fresh macOS CI records a real Xcode version and successfully runs core plus unsigned simulator app tests. |
| 2 | Flat byte-based DataRateLimits value, checked arithmetic tests, and macOS adapter compilation pass. |
| 3 | A truthful capability report is copyable before Start and includes actual encoder identity or an explicit unavailable result. |
| 4 | One preference owner passes bidirectional propagation and active-stream locking tests. |
| 5 | Deterministic UI launch scenarios cover navigation, permission, discovery/manual selection, Start/Stop, failure, Settings, and report export. |
| 6 | Privacy manifest audit passes; approved AppIcon passes or remains a named distribution-only blocker. |
| 7 | Bounded transport tests pass and physical diagnostics expose every performance acceptance field. |
| 8 | All Linux/repository/macOS checks pass on the pushed remediation commit with no receiver edit. |
| 9 | Every mode offered on the physical iPhone passes the Mac-lab matrix with retained reports. |

## Required handoff evidence

The implementation handoff MUST contain:

- receiver baseline SHA and remote-ref ancestor proof;
- remediation branch and final commit SHA;
- changed-file list demonstrating scope compliance;
- exact local and CI commands with pass/fail results;
- macOS CI URL, runner image, selected developer directory, and Xcode version;
- test names added for each reviewed defect;
- a sample redacted pre-stream capability report;
- privacy-manifest reason audit and built-product evidence;
- AppIcon status without implying artwork approval;
- physical matrix and redacted diagnostics, if Stage 9 was performed;
- remaining blockers with owners and whether they block local installation,
  physical validation, distribution, or public support.

Do not include signing data, unique device secrets, unredacted receiver hosts,
or private Apple Account information in the handoff.

## Completion definition

The remediation is complete only when:

- the receiver hold was released before the first implementation edit;
- receiver and protocol files remained unchanged;
- the VideoToolbox rate limit is structurally and dimensionally correct;
- macOS CI genuinely compiles and tests the iOS app;
- the user can export truthful capabilities before streaming;
- the actual encoder identity is reported when Apple supplies it;
- Settings and Setup share one validated preference state;
- UI behavior is covered through deterministic injected scenarios;
- transport remains bounded and every offered physical mode passes sustained
  validation;
- privacy metadata is correct;
- any missing approved AppIcon is the only explicitly allowed
  distribution-only blocker;
- public iOS support remains unchanged until physical evidence passes.

## Copyable implementation-agent directive

> Implement
> `internal/implementation-contracts/ios-sender-review-remediation.md` only
> after Stage 0 proves that the macOS receiver baseline is committed, pushed,
> fetched, reachable from the recorded remote ref, and the checkout is clean.
> Read that contract, `internal/implementation-contracts/ios-sender.md`,
> `AGENTS.md`, and the named iOS source/tests before editing. Put Stages 0–9 in
> your plan and execute them in order. Do not edit any receiver, receiver
> workflow, protocol, or Android file. Correct the VideoToolbox rate-limit
> shape and units, restore real macOS CI evidence, add the pre-stream capability
> report with actual encoder identity, unify preference state, expand
> deterministic UI tests, audit privacy/AppIcon metadata, and prove bounded
> transport behavior. Stop and report evidence if a receiver/protocol change
> appears necessary. Do not claim Apple-only compilation without a green macOS
> run or physical behavior without retained iPhone evidence.
