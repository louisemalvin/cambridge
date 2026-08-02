# Arch Linux and CachyOS setup

Run the one-time installer from the repository root:

```bash
./scripts/linux/install-receiver.sh
```

It installs the Rust and GStreamer dependencies, configures one persistent
`v4l2loopback` device with `exclusive_caps=1`, loads the module, verifies the
required GStreamer elements, and builds the receiver. The installer may ask
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

The receiver automatically discovers the loopback device and available phones.
It listens on TCP `5001` for control and allocates a UDP media port from
`50000-50099` for each session. Phone discovery probes TCP `53555` on bounded
local IPv4 subnets. The installer adds trusted-subnet UFW rules when UFW is
active.
Permit these only on the trusted local interface.

For troubleshooting, inspect the devices and GStreamer elements directly:

```bash
scripts/linux/inspect-video-devices.sh
gst-inspect-1.0 udpsrc tsparse tsdemux h264parse h265parse decodebin v4l2sink appsink
```

`x264enc` and `x265enc` are only needed for synthetic sender tests. A receiver
does not need a software encoder.
