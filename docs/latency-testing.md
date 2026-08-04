# Latency testing

The project targets time to first frame under three seconds, typical local
end-to-end latency under 150 ms, and a desired result under 100 ms. These are
engineering targets, not guarantees for every phone, decoder, network, or
consumer application.

## Repeatable visual test

Use a phone camera pointed at a display showing a large digital clock or a
flashing LED driven by a timestamp source. Record the phone display and the
desktop output with a second high-frame-rate camera. Compare the displayed
time or LED transitions frame by frame. Repeat at least ten times and report
median, minimum, maximum, and test conditions.

Do not include OBS preview buffering in a receiver-only measurement unless
that is the user experience being measured. Record whether the browser,
OBS, or call application applies its own buffering.

## Receiver observations

Query the session endpoint while testing:

```bash
curl -s http://127.0.0.1:5001/v2/sessions/SESSION_ID | jq
```

Record `receivedBitrateBps`, `timeoutCount`, `decoder`, and state transitions.
The receiver uses a zero-millisecond MPEG-TS demux latency and a two-frame
downstream leaky queue by default. If latency grows, inspect the decoder,
raw conversion, virtual-camera consumer, CPU scheduling, and network path
before increasing a queue.

## Network interruption

Stop the sender or disconnect Wi-Fi for several seconds, then restore it.
The receiver should remain alive, report `timed_out`, and return to
`receiving` when the sender resumes. A prolonged absence releases the old
session after the configured grace period so a new preparation can succeed.

Measure first-frame time after a restart separately from steady-state
latency. A keyframe interval of one second is used to bound recovery time.

## Structured performance baseline

The default workflow is automatic: run the Android app, let the stream run for
the desired condition, stop it, and tell the assistant the run is finished.
The assistant reads the latest completed receiver run from:

```text
http://127.0.0.1:5001/v2/diagnostics/latest
```

When ADB is connected, the assistant also pulls the structured Android Logcat
events directly. You do not need to find a session ID or export logs.

The receiver-side inspector remains available for a bounded live capture. It
polls the v2 per-session diagnostics endpoint:

```bash
mobile-webcam-receiver \
  --inspect-session SESSION_ID \
  --network wifi \
  --consumer obs \
  --sender-log sender-logcat.txt \
  --receiver-log receiver.jsonl \
  --output baseline-wifi.json
```

The inspector prints a human-readable summary and writes machine-readable
JSON when `--output` is supplied. Use `--json-logs` on the receiver when a
structured receiver log is needed. Android events are structured JSON Logcat
lines and are supplied separately with `--sender-log`; they are not coupled to
the media or control data paths.

Repeat a 60-second run at 1080p30 over Wi-Fi and USB tethering. Keep the phone
model/API, receiver host, codec, profile, selected lens, stabilization state,
decoder, output consumer, timeout count, and continuity count with each
report. Compare first-frame time, observed FPS, interval p50/p95/max, recent
bitrate, and the report categories before changing pipeline behavior. See
[`diagnostics.md`](diagnostics.md) for the event vocabulary and classification
rules.
