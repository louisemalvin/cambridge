# Known limitations

- Physical Android hardware validation is required for camera permission,
  MediaCodec support, RootEncoder preparation, background streaming, thermal
  behavior, and actual H.264/H.265 output.
- The API 35 emulator covers installation, launch with the manual receiver
  origin fallback, encrypted SRT H.264 receiver decoding, stop/restart teardown, and
  receiver-side output. The current environment has no open v4l2 capture
  consumer, so OBS and changing virtual-camera frames remain unverified.
- H.265 support depends on the phone encoder, requested profile, installed
  receiver parser, decoder, and output consumer.
- 1440p30 is optional. 4K30 is experimental and may be rejected by the phone,
  decoder, virtual camera, browser, or call application.
- Android Gradle tests, lint, and debug APK assembly are build-verified in the
  current environment, but a physical phone is still required for camera and
  hardware-encoder validation.
- RootEncoder 2.8.0 physical lens selection depends on Android 9+ and the
  vendor's logical multi-camera metadata. The sender exposes the physical IDs
  reported by the device, but those IDs are vendor-specific and are not stable
  lens names across phones. Video stabilization remains unsupported by the
  current RootEncoder `Camera2Source` adapter.
- A desktop preview requires GTK 4 development/runtime libraries and the
  GStreamer App plugin. Hosts without those packages can still use the CLI
  receiver, but cannot build or launch the preview application.
- Physical Android hardware, USB tethering, and long-duration thermal tests
  remain unverified in the current environment.
- SRT recovery and authentication are host-tested, but physical Wi-Fi, USB
  tethering, and long-duration thermal behavior remain unverified.
- Decoder hardware/software classification is best effort. `decodebin` is
  used instead of a vendor-specific decoder.
- Activity recreation keeps the application session owner but cannot restore
  a session after process death. A new stream must be started.
- Audio, microphone input, recording, filters, accounts, cloud
  relays, WebRTC, AV1, 60 FPS,
  Windows, macOS, and Tauri are deferred.
