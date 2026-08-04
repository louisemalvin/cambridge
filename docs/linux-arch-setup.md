# Arch Linux and CachyOS setup

Run the one-time installer from the repository root:

```bash
./scripts/linux/install-receiver.sh
```

It installs the Rust and GStreamer dependencies, configures one persistent
`v4l2loopback` device with `exclusive_caps=1`, loads the module, verifies the
required GStreamer elements, validates a v4l2loopback version of at least
`0.15.0` for client-usage events, and builds the receiver. The installer may ask
for `sudo`. It never unloads a module or changes Secure Boot settings.

Start the desktop receiver with the normal user account:

```bash
mobile-webcam-desktop
```

It opens the decoded preview and exposes the same frames through the
`Mobile Webcam` virtual camera. A headless terminal-only receiver is available
when needed:

```bash
mobile-webcam-receiver
```

Do not run both binaries at the same time. Either one starts the control API and
virtual-camera output.

The receiver automatically discovers the loopback device, advertises itself as
`_mobile-webcam._tcp.local.`, and waits for a sender-initiated v2 session. It
listens on TCP `5001` for control and on SRT port `5000` for encrypted media.
SRT uses UDP transport, so allow UDP `5000` when UFW is active. Set
`MOBILE_WEBCAM_TRUSTED_SUBNET` before running the installer to add scoped TCP
control and SRT media rules.

For troubleshooting, inspect the devices and GStreamer elements directly:

```bash
scripts/linux/inspect-video-devices.sh
gst-inspect-1.0 srtsrc tsparse tsdemux h264parse h265parse decodebin v4l2sink appsink
```

The receiver uses the upstream private client-usage event to distinguish real
capture demand from enumeration. Probe it directly with:

```bash
scripts/linux/probe-v4l2loopback-demand.sh /dev/video10
```

If event subscription fails, the receiver reports an actionable error rather
than falling back to process scanning.

`x264enc` and `x265enc` are only needed for synthetic sender tests. A receiver
does not need a software encoder.
