# Persistent Virtual-Camera Lifecycle

## Runtime ownership

The receiver owns one persistent Linux virtual-camera writer and one active
SRT session:

| Component | Responsibility |
| --- | --- |
| Persistent output | Keeps `/dev/video10` open and writes black standby frames when ingest is unavailable |
| Receiver service | Owns the fixed profile, SRT listener, session credentials, deadlines, and state |
| Sender | Selects a discovered receiver or uses the manual fallback, creates the session, and starts or stops its encoder |
| Demand monitor | Returns the output to standby when no virtual-camera consumer is present |

No subnet scan, phone-hosted listener, peer-address inference, or reverse camera
activation is required. Bonjour/NSD discovery populates the same receiver-origin
model as the manual fallback without changing the session contract.

The persistent producer uses a black `videotestsrc` standby branch and a live
`appsrc` branch selected by `input-selector`. Both branches feed fixed output
caps and bounded queues. Decoded frames arrive through a bounded `appsink`; old
frames are discarded. Stopping a sender switches to standby without closing
`v4l2sink` or removing the device.

## Demand detection

`receiver-platform-linux` subscribes to the upstream private
`V4L2_EVENT_PRI_CLIENT_USAGE` event using `VIDIOC_SUBSCRIBE_EVENT`, polls the
device, and dequeues with `VIDIOC_DQEVENT`. It emits effective active/inactive
transitions without process scanning, application-name detection, or `/proc`
polling.

Demand does not create a phone session by itself. The sender must have a
configured receiver origin and may start a stream while the output is in
standby. A consumer opening the device observes the persistent writer whether
the sender is connected or not.

## Session sequence

1. The sender calls authenticated `GET /v2/capabilities` and
   `POST /v2/sessions` on the configured receiver origin.
2. The receiver selects the first compatible codec, starts its SRT listener,
   and returns a session ID, fixed profile, stream ID, AES-256 passphrase, and
   deadlines.
3. The sender prepares its local encoder and starts video-only H.264 in
   MPEG-TS over SRT as the caller.
4. The receiver decodes frames into the persistent output and reports
   `receiving` through `GET /v2/sessions/{sessionId}`.
5. A temporary loss changes the session to `reconnecting`, selects standby, and
   accepts the same caller again within reconnect grace.
6. The sender stops its encoder and sends `DELETE /v2/sessions/{sessionId}`.
   The receiver keeps the virtual-camera writer available and makes repeated
   DELETE requests idempotent.

## Crash recovery

The sender owns bounded control retries and deletes its session during normal
cleanup. If the sender dies, the receiver expires the session after the
receiver-owned connect or reconnect deadline, stops the ingest pipeline, and
leaves the persistent output in standby. A new sender can then create a fresh
session.

If the receiver dies, the sender observes a bounded HTTP failure and stops its
local encoder. Restarting the receiver creates a new listener and session.

## iOS boundary

The iOS adapter uses the same v2 DTOs and SRT endpoint boundary. Runtime iOS
support is not claimed until its Swift implementation builds on macOS and
passes file-source conformance against the receiver.

## Verification

```bash
cargo test --manifest-path desktop/Cargo.toml --workspace
scripts/linux/test-srt-lifecycle.sh 20 55011 55010 /dev/video10
scripts/linux/test-srt-sustained.sh 55021 55020 /dev/video10
```

The Android emulator harness adds the sender-to-receiver gate using the manual
origin fallback because the emulator test network is intentionally deterministic.
OBS capture is a separate assertion because a physical capture consumer must
be open to verify changing `/dev/video10` frames.
