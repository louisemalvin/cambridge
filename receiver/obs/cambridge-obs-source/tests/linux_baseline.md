# Linux native baseline

The Linux receiver baseline exercises the current GStreamer to FFmpeg media
boundary and the native OBS presentation path. It is not a packetizer or raw
UDP socket baseline.

## Required evidence

Record the following for each accepted run:

- protocol version 7 and the negotiated RTP and RTCP ports;
- GStreamer runtime version and the `rtpgccbwe` factory;
- sender and receiver GStreamer integration cases A through F;
- selected decoder and renderer path;
- access units delivered, bytes delivered, transport errors, and first-frame
  publication;
- clean Stop and control disconnect behavior.

The receiver must keep the RTP and RTCP listeners distinct, use the 40 ms
jitter buffer, and expose no custom packetizer, reorder queue, or pacing loop.
The native path must remain locked for the session. A missing VAAPI or DRM
capability is a separate software-path result, not a transport fallback.

## Commands

```bash
cmake --fresh -S receiver/obs/cambridge-obs-source \
  -B build/cambridge-shared-tests \
  -DCAMBRIDGE_BUILD_PLUGIN=OFF \
  -DCAMBRIDGE_BUILD_SHARED_TESTS_ONLY=ON
cmake --build build/cambridge-shared-tests --parallel
GST_PLUGIN_PATH=/path/to/gst-plugin-rtp \
  ctest --test-dir build/cambridge-shared-tests --output-on-failure
```

The integration test returns the CTest skip code when the Rust RTP plugin or
the x264 test encoder is unavailable. A release record must include a real
run of the test, not only a successful compile.
