# CamBridge protocol

CamBridge uses protocol version **7**. Protocol versions are strict
compatibility boundaries. The authoritative definition is
[`protocol/cambridge-stream-contract.json`](../protocol/cambridge-stream-contract.json);
the schema and examples are generated validation fixtures.

## Connections

The sender uses one control connection and two media sockets:

```text
sender -> length-prefixed JSON over TCP -> receiver control listener
sender -> RTP/H.264 over UDP -> receiver RTP listener
sender <- RTCP over UDP <- receiver RTCP sender
```

Control messages use a big-endian 32-bit length followed by UTF-8 JSON. The
default control, receiver RTP, receiver RTCP, and sender RTCP ports are
55031, 55032, 55033, and 55033 respectively. RTP and RTCP are separate
transport roles even when their default numeric value is shared by the
contract.

When mDNS/Avahi is available, the receiver advertises `_cambridge._tcp`.
Discovery is a candidate source only. Android probes the resolved IPv4
addresses and bounded TXT address candidates over TCP before Start. Manual
receiver addressing remains available when discovery is unavailable.

## Session messages

- `probe` and `capabilities` report receiver identity and hard geometry limits.
- `hello` carries the immutable phone-authored coded geometry, FPS, bitrate,
  rotation, profile ID, and sender RTCP port.
- `accepted` returns the receiver RTP and RTCP ports and geometry limits.
- `stop` ends the active session.
- `error` reports a rejected request or terminal receiver media failure.

Every session includes a session ID and monotonic generation. Resolution, frame
rate, codec, bitrate, and rotation are fixed for the session. Changing them
requires Stop followed by a new Start.

## Video configuration ownership

The Android sender selects a Camera2 and MediaCodec-supported resolution, frame
rate, and bitrate. Those exact coded values are carried in the v7 `hello`.
`profileId` is an opaque sender-authored diagnostic identifier; the receiver
does not look it up or substitute a preset.

The receiver validates wire and resource bounds and either accepts the exact
values or returns an error. It never silently downgrades the stream.

## Media contract

Media is H.264 over RTP/UDP with RTCP feedback. The shared media values are:

| Value | Contract setting |
| --- | ---: |
| RTP payload type | 96 |
| RTX payload type | 97 |
| RTP clock | 90000 Hz |
| RTP MTU | 1200 bytes |
| TWCC extension ID | 1 |
| Receiver jitter latency | 40 ms |
| RTX history | 150 ms |
| Maximum access unit | 8 MiB |
| Sender queue | 2 access units |

The Android sender supplies Annex-B access units to GStreamer `appsrc` with
presentation timestamps. GStreamer performs H.264 parsing, RTP packetization,
RTX, RTCP, TWCC, and GCC. No application RTP packetizer or pacing loop is
part of the supported path.

The receiver's GStreamer `rtpbin` uses its jitter buffer and
`rtprtxreceive`. One lost packet is requested with NACK and repaired when it is
still in sender history. Loss that cannot be repaired produces PLI/FIR feedback;
the sender requests a MediaCodec sync frame, and the receiver waits for a clean
keyframe before delivering more access units.

The wire protocol does not require a virtual camera device. The current
supported receiver is the native Linux OBS source.
