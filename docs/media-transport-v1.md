# MPEG-TS over UDP media transport v1

This document defines the media boundary shared by Android, iOS, and the Rust
receiver. Camera capture, hardware encoding, MPEG-TS packet construction, and
socket APIs remain platform-specific. A sender is compatible when the bytes it
produces satisfy this contract, regardless of the camera framework or encoder
that produced them.

The control plane selects a session and returns the UDP destination. The media
plane then follows this path:

```text
platform camera
  -> platform hardware encoder
  -> H.264 or H.265 elementary stream
  -> MPEG-TS program
  -> UDP unicast datagrams
  -> Rust/GStreamer receiver
```

## Version-one rules

The following rules apply to the current implementation:

| Item | Contract |
| --- | --- |
| Media direction | One sender to one negotiated receiver endpoint |
| IP transport | UDP unicast; no broadcast or multicast |
| Container | MPEG-TS with a single video program |
| TS packet size | Exactly 188 bytes |
| Datagram alignment | Each datagram contains an integer number of complete 188-byte TS packets; a TS packet must never be split across datagrams |
| Reference datagram | The Android transport sends six TS packets per full datagram, derived as `6 * 188 = 1128` bytes |
| Audio | Not present in version one |
| Required codec | H.264 |
| Optional codec | H.265, selected through the control protocol when both sides support it |
| Control identifier | `mpegts-udp` |
| Media security | None; the trusted-local-network boundary is an explicit phase-one limitation |
| Reliability | No retransmission, ordering, or congestion control is added above UDP |

Receivers should accept datagrams containing any positive whole-packet count so
that platform implementations can flush a final partial datagram. Senders
should use the reference six-packet payload when possible to stay within the
smallest supported local-network MTU profile. The payload limit is derived from
the MPEG-TS packet size and the Android transport limit, not copied as an
independent protocol value.

The receiver identifies MPEG-TS using `video/mpegts`, `systemstream=true`, and
`packetsize=188`. PAT and PMT data must be present so a receiver can discover
the video stream without sender-specific assumptions. The sender must not put
application framing or a custom header in front of the TS packets.

## Codec and encoder contract

H.264 is the interoperability baseline. H.265 is an optional capability, not a
requirement for an otherwise conforming sender. The selected codec is the
codec returned by `POST /v1/sessions/prepare`; the sender must not silently
switch codecs after preparation.

The encoder must produce a stream that the corresponding MPEG-TS demuxer and
codec parser can decode. The sender should make codec parameter sets available
at stream start and repeat them on keyframe boundaries when the platform
encoder supports that mode:

- H.264: SPS and PPS.
- H.265: VPS, SPS, and PPS.

The current GStreamer receiver configures `h264parse` and `h265parse` to retain
codec configuration across keyframes. This improves recovery after packet loss,
but it does not remove the sender requirement to make parameter sets available
when starting or restarting a stream.

Codec profile and level, bitrate, frame size, frame rate, and keyframe interval
are sender capabilities and negotiated configuration. The current JSON control
schema carries frame size, frame rate, and bitrate. A compatible control
extension should represent the remaining fields as follows:

| Field | Semantics |
| --- | --- |
| `codecProfile` | Codec-family profile name, such as H.264 `baseline`, `main`, or `high`; it is not an Android encoder name |
| `codecLevel` | Codec-family level identifier, such as H.264 `4.1`; the receiver rejects a level it cannot decode at the requested profile |
| `keyframeIntervalFrames` | Positive maximum distance between coded keyframes at the negotiated frame rate |
| `parameterSetRepeat` | `stream_start` or `keyframe`; `keyframe` is the preferred recovery mode |
| `orientation` | Display orientation applied by the sender before encoding; the media receiver does not rotate frames |

These values must be negotiated in the control plane rather than inferred from
a platform name or encoded into a custom media header. Until those optional
fields are added to the versioned control schema, the Android profile defaults
and native iOS media-spike results are implementation inputs, not additional
wire fields.

## Timing and orientation

Encoded timestamps must be monotonic within a session and must use the standard
90,000 Hz MPEG-TS media clock for PTS/DTS values. They represent media time, not
wall clock time. A receiver must not require the sender and receiver clocks to
be synchronised. The platform media engine owns conversion from its native
capture clock to the 90,000 Hz timestamp representation.

The version-one receiver consumes the encoded display orientation. A sender
must configure capture and encoding so the decoded picture has the requested
orientation. Orientation metadata is not currently a separate media-plane
field, and the receiver does not apply a platform-specific rotation policy.

## Sessions, restarts, and discontinuities

The UDP port is allocated per prepared session. A session ID is carried by the
control protocol and is not repeated in every UDP datagram. The tuple of the
negotiated session ID, selected codec, and media port identifies the active
stream epoch.

On a clean stop, the sender should stop sending before deleting the control
session. On a restart, the sender must use a newly prepared session, emit fresh
MPEG-TS tables and codec configuration, and reset its media timestamp state for
the new epoch. A receiver may discard late packets from the previous epoch.

Packet loss and a temporary lack of packets are recoverable. The receiver
reports a timeout after its configured inactivity period while keeping the
process and pipeline alive. A sender that resumes the same prepared session
must provide enough table and parameter-set information for the decoder to
recover; a sender that changes codec, destination, or timestamp epoch must
prepare a new session.

## Platform obligations

Android keeps RootEncoder as the media engine. Its existing UDP adapter owns
MediaCodec/RootEncoder output, MPEG-TS packetisation, and datagram sizing.

iOS will eventually use native AVFoundation and VideoToolbox boundaries. The
iOS skeleton in this repository intentionally does not implement capture,
encoded-buffer handling, NAL conversion, MPEG-TS muxing, or UDP packetisation.
Those decisions belong to a focused iOS media spike and must be validated by
the receiver compatibility tests before production implementation.

Neither platform should put camera frames, encoded buffers, MPEG-TS packets, or
native media objects through a shared Kotlin Multiplatform layer. If KMP is
introduced later, it is limited to control DTOs, pairing, negotiation, session
state, configuration, and coarse events.

## Compatibility evidence

The Rust receiver tests validate the standard 188-byte MPEG-TS caps and the
H.264/H.265 parser branches. The Android UDP transport tests validate the
derived six-packet payload limit and MTU safety. Synthetic H.264 and H.265
senders in [`docs/testing.md`](testing.md) provide receiver-side integration
coverage without depending on a particular mobile camera implementation.
