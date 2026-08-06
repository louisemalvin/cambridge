# Diagnostics

Android writes structured `MobileWebcam` events with schema
`mobile-webcam-diagnostics-v1`. Useful events include:

- `control_connection_started`
- `session_created`
- `encoder_prepared` with coded and display geometry
- `stream_started`, `stream_stopped`, and `stream_resources_released`
- `stream_failed` followed by same-endpoint recovery while Connect remains
  desired
- `stream_resources_released`

The native source logs a matching identity line, protocol v2 bounds, coded and
display geometry, session generation, decoder mode, frame publication, render
mode, and recovery events. Use the source properties dialog to write a
diagnostics snapshot containing session identity, coded/display dimensions,
rotation, mode, queue counts, drops, IDR requests, and frame age.

For an emulator run, the harness prints paths for:

- `android.log`: filtered sender process log
- `obs.log`: isolated OBS and native source log
- `emulator.log`: emulator and camera backend log
- `plugin-build.log`: native build, tests, and staged artifact information

Expected successful presentation markers are:

```text
session_accepted
decoder_ready:h264/VAAPI
first_frame_published:mode=0
render_mode=dma_buf_direct
```

`decoder_ready:h264/software`, `mode=2`, and `render_mode=cpu_nv12_upload` are
valid fallback markers when VAAPI or DMA-BUF import is unavailable.
