# Performance diagnostics

The receiver automatically samples diagnostics while a session is active and
retains a bounded timeline for the latest completed run. The existing session
response and the MPEG-TS/UDP media contract are unchanged.

## Assistant-driven capture

For the normal workflow, start the receiver once, run the Android app, and
stop the stream. Then tell the assistant that the run is finished. The
assistant can fetch:

```text
GET /v1/diagnostics/latest
```

The response contains the run/session identity, start and completion times,
and timestamped receiver snapshots. No session ID lookup, Logcat export, or
manual report assembly is required. When a device is connected through ADB,
the assistant can also pull the structured sender events directly from the
Logcat buffer and correlate them with the receiver run. Sender logs remain
separate from the media and control paths.

The latest run is held in receiver memory and is lost if the receiver process
restarts. Capture the run before restarting the receiver.

The session-specific endpoint remains available for live troubleshooting:
`GET /v1/sessions/{sessionId}/diagnostics`.

## Diagnostic vocabulary

Both Android Logcat exports and receiver JSON logs use one event field and a
flat set of context fields:

| Event | Meaning |
| --- | --- |
| `stream_start_requested` | Android accepted a start request and created a run ID. |
| `sender_environment` | Phone model/API and requested profile conditions. |
| `receiver_health_checked` / `receiver_capabilities_received` | Sender control-plane readiness observations. |
| `codec_negotiated` | Sender and receiver selected the codec and profile. |
| `camera_configuration` | Camera capabilities, lens, and stabilization context were observed. |
| `encoder_prepared` | RootEncoder accepted the negotiated video configuration. |
| `media_stream_starting` | The sender is about to send MPEG-TS/UDP media. |
| `stream_started` | Sender lifecycle reached streaming. |
| `encoder_connected` / `encoder_connection_failed` | RootEncoder transport callbacks. |
| `encoder_bitrate_changed` | A sampled encoder bitrate callback. |
| `receiver_state_changed` | Receiver state transition. |
| `receiver_first_frame` | First decoded frame reached the output queue. |
| `receiver_stream_timed_out` / `receiver_stream_resumed` | UDP input interruption and recovery. |
| `receiver_decoder_selected` | GStreamer selected a video decoder. |
| `receiver_continuity_warning` | MPEG-TS continuity warning observed on the bus. |
| `receiver_pipeline_warning` / `receiver_pipeline_error` | Other GStreamer warning or error. |
| `camera_lens_selected` / `camera_stabilization_changed` | User camera choices. |
| `stream_start_failed` / `stream_failed` / `stream_stopped` | Sender lifecycle outcome. |

Android events include `schema`, `source`, `event`, `timestampMs`, `runId`,
and, once negotiated, `sessionId`. Receiver events include `schema`,
`source`, `event`, `timestampMs`, `sessionId`, and typed event fields. A run ID
is intentionally out-of-band: it correlates an exported sender log with the
receiver session without changing the control protocol.

## Receiver metrics

The diagnostics response reports the target profile and bitrate, receiver
state, decoder, first-frame elapsed time, lifetime and recent received
bitrate, decoded frame count, timeout count, continuity/pipeline warning
counts, and a recent frame-interval window. The interval window reports
sample count, min, mean, p50, p95, max, and mean absolute jitter in
milliseconds, plus observed FPS.

Queue diagnostics report the configured output queue size, current depth,
maximum observed depth, and the number of sampled high-watermark observations.
The receiver classifies the current observation as one of:

- `starting`
- `waiting_for_packets`
- `steady_state`
- `packet_interruption`
- `decoder_stall`
- `output_backpressure`
- `pipeline_error`
- `failed`

Classification is descriptive. It does not tune queues, add buffering, or
change the media pipeline.

## Inspector report

`mobile-webcam-receiver --inspect-session SESSION_ID` polls the diagnostics
surface for a bounded run and writes a JSON report when `--output` is supplied.
The report contains:

- run/session identity and capture timestamps;
- phone/API, network, receiver host, consumer, codec, profile, lens, and
  stabilization conditions when supplied or present in sender events;
- sender lifecycle events and receiver state/diagnostic events;
- sampled receiver observations;
- final frame cadence, bitrate, timeout, continuity, decoder, queue, and
  classification data; and
- warnings and categories that distinguish startup delay, steady-state jitter,
  packet interruption, decoder stall, and output backpressure.

The sender log is optional and remains a separate input for the manual
inspector. The receiver does not read Logcat.

## Manual inspector procedure

1. Start the receiver with JSON logs enabled when a receiver log is useful:
   `mobile-webcam-receiver --json-logs`.
2. Start one Android session and record the negotiated session ID from the
   sender log or the control response. Record phone model/API, codec, profile,
   selected lens, stabilization state, receiver host, and output consumer.
3. Run the inspector for the bounded capture, for example:

   ```bash
   mobile-webcam-receiver \
     --inspect-session SESSION_ID \
     --network wifi \
     --consumer obs \
     --sender-log sender-logcat.txt \
     --receiver-log receiver.jsonl \
     --output baseline-wifi.json
   ```

4. Repeat the same 60-second condition over Wi-Fi and USB tethering. Keep the
   JSON reports with the phone model/API, receiver host, decoder, output
   consumer, and whether the run ended with timeouts or continuity warnings.
   Compare median, p95, maximum, and interruption counts across runs before
   changing media behavior.
