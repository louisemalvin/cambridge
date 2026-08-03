# Demand-Driven Webcam Lifecycle

## Runtime Ownership

The Linux desktop keeps these dimensions independent:

| Dimension | States |
| --- | --- |
| Sender reachability | unavailable, discovered, paired/reachable |
| Virtual-camera demand | inactive, active with an effective consumer count |
| Media lifecycle | idle, starting, streaming, stopping, failed |

Discovery is side-effect free. It probes sender-control v2 `describe` and
selects the persisted preferred sender, but it never prepares a receiver
session or sends `start`. The desktop starts a persistent virtual-camera
producer first, then the Linux v4l2loopback demand monitor, receiver HTTP
service, and discovery service.

The persistent producer uses a black `videotestsrc` standby branch and a live
`appsrc` branch selected by `input-selector`. Both branches feed fixed YUY2
1920x1080/30 output caps and bounded downstream-leaky queues. Decoded frames
arrive through a bounded `appsink`; the newest frame is pushed to the live
branch and old frames are discarded. Stopping a phone stream switches the
selector to standby without closing `v4l2sink` or removing the device.

## Demand Detection

`receiver-platform-linux` subscribes to the upstream private
`V4L2_EVENT_PRI_CLIENT_USAGE` event using `VIDIOC_SUBSCRIBE_EVENT`, polls the
device, and dequeues with `VIDIOC_DQEVENT`. The raw client-usage payload is
converted relative to the configured producer baseline, saturating at zero.
The monitor emits only effective inactive/active transitions. Activation is
debounced for 200 ms and release for 100 ms, so capability inspection and
short-lived enumeration opens do not start the phone camera.

The installed v4l2loopback 0.15.4 driver reports an effective capture-in-use
flag rather than a true multi-consumer count. The conversion and state machine
still handle count-capable drivers, while the real-device test treats a second
consumer as supported only when the configured driver accepts it. No process
scanning, application-name detection, or `/proc` polling is used.

## Start and Stop

1. A sustained V4L2 consumer changes demand from inactive to active.
2. The desktop creates a fresh UUID `streamId` and sends authenticated v2
   `start`; retries and approval retries reuse that ID.
3. Android authenticates the receiver and stream generation, prepares the
   receiver HTTP session, starts the foreground service, camera, encoder, and
   UDP engine, then returns `accepted`.
4. The desktop receiver decodes frames into the persistent output, which
   switches from black standby to live video.
5. When the final consumer leaves, demand changes active to inactive. The
   desktop immediately selects standby and sends authenticated `stop` with the
   active `streamId`.
6. Android stops media and releases the foreground service before deleting the
   receiver HTTP session, clears the active generation, and returns `stopped`.
   Repeated stop returns `already_stopped`; an old generation returns
   `stale_stream` without affecting a newer stream.
7. The desktop confirms or bounds the stop, forces local receiver-session
   cleanup after a failed stop, and remains paired in standby.

Desktop shutdown stops demand intake, joins the demand relay, sends the active
or starting generation's stop, performs bounded local cleanup, then stops the
demand monitor, persistent output, and HTTP service.

## Crash Recovery

While Android is streaming, one watchdog polls
`GET /v1/sessions/{sessionId}` every 2 seconds. It accepts prepared, waiting,
receiving, and timed-out session states, resets its failure counter after a
successful check, ignores transient failures, and stops media after three
consecutive failures or not-found responses. Normal stop cancels the watchdog
immediately. Closing the one-shot sender-control TCP connection does not stop
media.

## Protocol And iOS

Sender-control v2 is the binding contract in
[`protocol/sender-control-v2.schema.json`](../protocol/sender-control-v2.schema.json).
It uses one newline-delimited request/response per TCP connection on port
53555, discriminated actions, stable receiver/sender IDs, authenticated
`start`/`stop`, availability advertisement, and UUID `streamId` generation
identity. The receiver HTTP `sessionId` remains a separate layer identifier.

When iOS work resumes, the sender must implement the same v2 describe, start,
and stop DTOs, pairing-token and peer-address authentication, stream-generation
idempotency/stale-stop rules, camera-off standby, cleanup ordering, and the
receiver-session watchdog. No iOS code is part of this lifecycle change.

## Verification

Automated Rust checks:

```bash
cd desktop
cargo fmt --all --check
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

Android checks require a JDK:

```bash
cd android
./gradlew test lint
```

Real Linux loopback verification:

```bash
scripts/linux/setup-v4l2loopback.sh 10
scripts/linux/test-demand-driven-webcam.sh /dev/video10
```

Manual application checks should repeat preview/capture with OBS, Firefox,
Chromium, and a video-call client. A physical Android device is required to
verify camera permission, hardware encoding, foreground-service behavior, and
actual end-to-end live frames.
