# Arch Linux and CachyOS setup

Run the one-time installer from the repository root:

```bash
./scripts/linux/install-receiver.sh
```

It installs the Rust and GStreamer dependencies, configures one persistent
`v4l2loopback` device with `exclusive_caps=1`, loads the module, verifies the
required GStreamer elements, and builds the receiver. The installer may ask
for `sudo`. It never unloads a module or changes Secure Boot settings.

Start the receiver with the normal user account:

```bash
mobile-webcam-receiver
```

The receiver automatically discovers the loopback device. It listens on TCP
`5001` for control and UDP `5000` for MPEG-TS media. Open those ports in the
firewall only on the trusted local interface.

For troubleshooting, inspect the devices and GStreamer elements directly:

```bash
scripts/linux/inspect-video-devices.sh
gst-inspect-1.0 udpsrc tsparse tsdemux h264parse h265parse decodebin v4l2sink
```

`x264enc` and `x265enc` are only needed for synthetic sender tests. A receiver
does not need a software encoder.
