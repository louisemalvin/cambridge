# ADR 0002: Use MPEG-TS over UDP

## Context

The target is a low-latency local webcam path where recent frames matter more
than retransmitting old frames. The transport must carry both H.264 and H.265
without a custom packetizer.

## Decision

Use MPEG-TS over UDP unicast. RootEncoder owns sender-side packaging and the
GStreamer receiver owns demuxing. The default media port is `5000`.

## Consequences

The path is simple and has no retransmission delay, but it is unencrypted and
can lose packets. A local timeout is recoverable. RTP, SRT, WebRTC, and cloud
relays remain deferred.
