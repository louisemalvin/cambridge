# Codecs and profiles

Keep these terms separate:

- H.264 and H.265 are video codecs.
- MPEG-TS is the media container that packages the compressed video.
- SRT is the network transport used to send the MPEG-TS stream to one receiver.

The v2 data path is H.264 in MPEG-TS over encrypted SRT unicast. It is not raw
H.264, raw H.265, RTP, or UDP broadcast.

The receiver-owned v2 contract defines the packet, timestamp, restart, and
parameter-set boundary. H.264 is the required interoperability baseline; H.265
is an optional negotiated capability.

## Selection policy

The sender exposes three preferences:

| Preference | Behavior |
| --- | --- |
| Auto - prefer H.265 | Try H.265, then H.264 if both endpoints support the selected profile |
| H.264 | Require H.264 |
| H.265 | Require H.265 |

The Android capability probe enumerates encoders with `MediaCodecList`, checks
the codec MIME type, resolution, frame rate, and hardware/software metadata.
Known software encoders are not automatically selected for 1440p or 4K. A
forced mode can attempt a software encoder, but actual RootEncoder preparation
is still authoritative and failures are reported.

The receiver maps H.264 to `h264parse` and H.265 to `h265parse`, then uses
`decodebin`. The selected decoder factory is logged when GStreamer exposes it;
the receiver does not claim hardware acceleration when the plugin does not
identify it.

The current control schema negotiates dimensions, frame rate, and bitrate. A
future compatible extension must carry codec profile and level, explicit
keyframe interval, parameter-set repetition policy, and any orientation
metadata needed by a sender. These values must remain transport-contract
fields rather than Android- or iOS-specific configuration.

## Profiles

| Profile | Size | FPS | H.264 starting bitrate | H.265 starting bitrate | Support level |
| --- | ---: | ---: | ---: | ---: | --- |
| `720p30` | 1280 x 720 | 30 | 4 Mbps | 7 Mbps | Required and default |
| `1080p30` | 1920 x 1080 | 30 | 10 Mbps | 7 Mbps | Optional |
| `1440p30` | 2560 x 1440 | 30 | 18 Mbps | 12 Mbps | Optional |
| `4k30` | 3840 x 2160 | 30 | 32 Mbps | 20 Mbps | Experimental |

These are typed profiles, not universal quality guarantees. A phone can
advertise H.265 at 1080p and not support H.265 at 4K30. The receiver also
checks the requested dimensions and frame rate before accepting a session.

## Output formats

The raw output policy prefers YUY2 at 1080p for broad consumer compatibility.
At 1440p and 4K, Auto prefers NV12 to reduce conversion bandwidth, while YUY2
and I420 remain explicit choices. A browser or conferencing application may
reject high-resolution virtual-camera formats even when the receiver pipeline
can produce them.
