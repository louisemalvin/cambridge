# Known limitations

- Physical Android hardware validation is required for camera permission,
  MediaCodec support, RootEncoder preparation, background streaming, thermal
  behavior, and actual H.264/H.265 output.
- H.265 support depends on the phone encoder, requested profile, installed
  receiver parser, decoder, and output consumer.
- 1440p30 is optional. 4K30 is experimental and may be rejected by the phone,
  decoder, virtual camera, browser, or call application.
- The current local environment has no Java or Android SDK, so Android Gradle
  tests, lint, and APK assembly were not run here.
- The current local GStreamer installation has the parsers and encoders but
  lacks `udpsrc`, `udpsink`, and `v4l2sink`, so live UDP and virtual-camera
  tests were skipped. Pipeline and parser tests still run.
- v4l2loopback, OBS, browser capture, USB tethering, and privileged module
  setup require a configured Linux host and were not verified here.
- UDP has no retransmission, encryption, or authentication in Phase 1. Packet
  loss can produce visible artifacts or a timeout.
- Decoder hardware/software classification is best effort. `decodebin` is
  used instead of a vendor-specific decoder.
- Activity recreation keeps the application session owner but cannot restore
  a session after process death. A new stream must be started.
- Audio, microphone input, recording, filters, physical lens selection,
  discovery, pairing, accounts, cloud relays, WebRTC, SRT, AV1, 60 FPS,
  Windows, macOS, and Tauri are deferred.
