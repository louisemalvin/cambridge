# CamBridge quality review - 2026-08-20

## Scope

This review covers the Android sender and the native OBS receiver on `main`,
with `v0.3.2` (`fa82498`) as the baseline. The phone was deliberately not
tested because it is locked. The review therefore separates repository and
host evidence from physical-device evidence.

The durable test policy is in [Testing strategy](../testing-strategy.md).

## Executive result

The repository has meaningful protocol, policy, UI, and lifecycle tests, but
the previous test set left several high-risk decisions either indirectly
covered or untested at the platform boundary. This pass addressed three
concrete defects and added decision-level tests:

- mixed resolution/FPS capability decisions no longer show a 60 fps failure
  reason on an otherwise supported resolution;
- failed manual receiver probes no longer overwrite the last known-good
  persisted endpoint;
- camera permission loss is classified as `CameraPermissionDenied`, and the
  webcam route can recover from permanent denial through Android app settings
  and refresh state on resume.

The Android JVM tests, lint, and instrumentation-test compilation pass. The
native OBS CPU fixture also passed at 2K30 and with 90-degree rotation. The
physical phone, connected instrumentation execution, and DroidCam/scene
regression checks remain pending for this review.

## 60 fps finding

60 fps is not blocked by the CamBridge protocol or by the product catalog. The
catalog contains 1080p60 and 2K60, and the contract allows up to 120 fps.

The sender blocks 60 fps when any required capability intersection fails:

- Camera2 does not expose the requested size and sustainable FPS;
- the H.264 MediaCodec does not support the exact size/FPS pair;
- the encoder bitrate range has no overlap with the product's stepped range.

The previous UI correctly disabled an unsupported 60 fps option, but its
resolution aggregation could attach that 60 fps reason to the enabled 1080p
resolution. That made the UI appear internally contradictory. The resolver now
shows a resolution reason only when every FPS for that resolution is
unsupported. The specific 60 fps reason remains on the 60 fps option.

On the Vivo, the next physical check must retain the `video_capabilities_resolved`
event for all four product modes. That event is the evidence needed to say
whether 60 fps is blocked by Camera2, MediaCodec, or bitrate overlap.

## Changes made

### Capability decision quality

`StreamSetupOptionResolver` now owns resolution and frame-rate option mapping.
Its tests cover:

- 1080p30 supported and 1080p60 unsupported;
- unsupported 60 fps reason preserved on the FPS option;
- a resolution disabled only when all of its FPS modes are unsupported.

### Permission recovery

Permission checks now use a typed `CameraPermissionRequiredException`. The
session controller maps that failure to `StreamFailure.CameraPermissionDenied`
instead of presenting it as a video quality or OBS failure.

The setup route already handled settings recovery. The webcam route now also:

- tracks permanent denial;
- opens Android app settings instead of repeatedly launching a request that
  cannot succeed;
- refreshes permission on `ON_RESUME`;
- carries the blocked state into the permission dialog.

The controller test verifies the typed failure classification. Route-level
Android lifecycle execution is still pending.

### Receiver selection safety

Manual receiver configuration is now persisted only after a successful probe.
The coordinator test verifies that a failed manual probe leaves the previous
known-good endpoint intact.

### Lifecycle tests

The session controller tests now cover:

- unsupported encoder capability before engine preparation;
- permission failure classification;
- engine start failure cleanup;
- idle versus active bitrate updates;
- existing idempotent stop and disconnect cleanup paths.

### CI signal

The Android workflow and the local `check-all.sh` now compile the instrumentation
test source. This prevents UI tests from silently drifting out of build scope.
They still require a configured emulator or device for execution.

## Evidence captured in this pass

| Check | Result | Meaning |
| --- | --- | --- |
| `./gradlew testDebugUnitTest lint` | Passed | Android JVM decision and presentation tests pass. |
| `./gradlew compileDebugAndroidTestKotlin` | Passed | Android instrumentation source compiles. |
| Phone installation or stream start | Not run | Phone was locked by request. |
| Physical 60 fps capability matrix | Pending | Requires the phone and Android capability log. |
| Native CTest and OBS fixture | Passed | 12/12 CTest targets passed; CPU 2K30 recording and 90-degree rotation fixtures reached OBS startup complete, first-frame publication, and clean session invalidation. |
| DroidCam and existing OBS scenes | Pending | Must be checked after the isolated Cambridge profile test. |

There is no line-coverage or branch-coverage threshold configured. The
decision matrix is the current quality gate; adding a percentage without
platform execution would overstate confidence.

The native fixture was run with `CAMBRIDGE_DECODER_MODE=cpu` and retained its
artifact directories. The logs show `Startup complete`, Cambridge module load,
`session_accepted`, `first_frame_published`, and
`control_disconnected_session_invalidated`. No OBS coredump was listed by
`coredumpctl` for the run window. The fixture does not yet automate that
coredump query, so the no-coredump result is host evidence rather than a CI
gate.

## Decision traces still not fully covered

The highest-risk remaining gaps are:

1. Setup and webcam route lifecycle tests that exercise actual Android settings
   return and permission revocation rather than only pure mapping and fake
   controller behavior.
2. Android `CamBridgeControlConnection` framing against a real local socket,
   including partial reads, malformed JSON, timeout, and oversized frames.
3. `CamBridgeRtpStreamEngine` output conversion from MediaCodec AVC
   length-prefixed buffers and codec configuration records.
4. Session cancellation and concurrent start/stop ordering, including a
   foreground-service start failure.
5. Discovery failure, duplicate receiver identity, and mixed probe success and
   failure under concurrent discovery updates.
6. Native plugin discovery and OBS startup under the target OBS/libobs and
   FFmpeg ABI, rather than only CTest, dependency inspection, and policy tests.
7. Actual native VAAPI/DRM PRIME and software fallback frame presentation on the
   target laptop.
8. Physical DroidCam and existing-scene regression behavior after Cambridge is
   enabled and disabled.

## Review checklist for tomorrow

Before calling the update accepted:

- [ ] Run the full repository check with the target JDK and native dependencies.
- [ ] Run connected Android instrumentation tests on an API 35 emulator or
      available phone.
- [ ] Reproduce the permission-denied path, permanently deny Camera, open app
      settings, grant permission, return, and verify capability probing resumes.
- [ ] Capture the four-mode capability record and explain any disabled 60 fps
      option from its reason.
- [ ] Start an isolated OBS profile containing only Cambridge and retain the
      startup log, no-coredump result, first-frame evidence, and clean stop.
- [ ] Verify DroidCam and existing OBS scenes still load and stream normally.
- [ ] Review the remaining gaps in the decision matrix and explicitly mark any
      unexecuted branch as pending rather than passing.

## Recommendation

The repository changes are suitable for code review after the targeted checks
pass, but the release should not claim physical-phone 60 fps support or full
permission recovery until the locked-phone acceptance run and isolated OBS
startup run are retained. The next highest-value investment is route-level
instrumentation plus a Linux OBS process smoke test, because those two layers
cover the current boundary gaps that pure tests cannot establish.
