# Persistent Virtual-Camera Lifecycle

## Runtime ownership

The receiver owns one persistent Linux virtual-camera writer and one active
SRT session:

| Component | Responsibility |
| --- | --- |
| Persistent output | Keeps `/dev/video10` open and writes black standby frames when ingest is unavailable |
| Receiver service | Owns the fixed profile, SRT listener, session credentials, deadlines, and state |
| Sender | Selects a discovered receiver or uses the manual fallback, keeps the control subscription alive, and owns media only while demand is active |
| Demand monitor | Returns the output to standby when no virtual-camera consumer is present |

No subnet scan, phone-hosted listener, peer-address inference, or reverse camera
activation is required. Bonjour/NSD discovery populates the same receiver-origin
model as the manual fallback without changing the session contract.

The persistent producer uses one bounded `appsrc` with fixed YUY2 output caps.
It pushes a named black sample in standby and the latest decoded sample in live
mode. Decoded frames arrive through a bounded `appsink`; old frames are
discarded. Stopping a sender switches to standby without closing `v4l2sink` or
removing the device.

## Demand detection

`receiver-platform-linux` subscribes to the upstream private
`V4L2_EVENT_PRI_CLIENT_USAGE` event using `VIDIOC_SUBSCRIBE_EVENT`, polls the
device, and dequeues with `VIDIOC_DQEVENT`. It emits effective active/inactive
transitions without process scanning, application-name detection, or `/proc`
polling.

Demand does not create a phone session by itself. The sender must have a
configured receiver origin, complete health and capability checks, and keep the
authenticated demand subscription open. A consumer opening the device observes
the persistent writer whether the sender is connected or not.

## Session sequence

1. The sender calls `GET /v2/health`, authenticated `GET /v2/capabilities`,
   and authenticated `GET /v2/demand/subscribe` on the configured receiver
   origin. It remains in connected standby after the initial inactive snapshot.
2. A sustained V4L2 consumer produces one generation-scoped active event. The
   sender then calls `POST /v2/sessions`; duplicate consumers do not create a
   second generation or session.
3. The receiver selects the first compatible codec, starts its SRT listener,
   and returns a session ID, fixed profile, stream ID, AES-256 passphrase, and
   deadlines.
4. The sender prepares its local encoder and starts video-only H.264 in
   MPEG-TS over SRT as the caller.
5. The receiver decodes frames into the persistent output and reports
   `receiving` through `GET /v2/sessions/{sessionId}`.
6. A temporary loss changes the session to `reconnecting`, selects standby, and
   accepts the same caller again within reconnect grace.
7. The final consumer release produces the matching inactive event. The sender
   stops its encoder, releases the foreground and camera resources, and sends
   `DELETE /v2/sessions/{sessionId}`. The receiver keeps the virtual-camera
   writer available and makes repeated DELETE requests idempotent.

## Crash recovery

The sender owns the demand subscription and deletes its session during normal
cleanup. If the sender dies, the receiver expires the session after the
receiver-owned connect or reconnect deadline, stops the ingest pipeline, and
leaves the persistent output in standby. A new sender can then subscribe and
create a fresh session for a new demand generation.

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
scripts/android/test-emulator-srt.sh
```

The Android emulator harness adds the sender-to-receiver gate using the manual
origin fallback because the emulator test network is intentionally deterministic.
It asserts connected standby, generation 1 activation, black standby after the
final release, and a fresh generation with changing generic V4L2 frame hashes.
