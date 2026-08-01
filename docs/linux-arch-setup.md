# Arch Linux setup

Package names can vary with enabled repositories. Install the GStreamer
runtime, development files, codec plugins, V4L2 tools, and v4l2loopback:

```bash
sudo pacman -S --needed base-devel pkgconf \
  gstreamer gst-plugins-base gst-plugins-good gst-plugins-bad \
  gst-plugins-ugly gst-libav v4l-utils v4l2loopback-dkms
```

Check the media elements before debugging the application:

```bash
gst-inspect-1.0 udpsrc tsparse tsdemux h264parse h265parse decodebin v4l2sink
```

`x264enc` and `x265enc` are needed for the synthetic sender scripts. A
receiver does not need a software encoder. If a package does not provide an
element, install the repository package that owns that plugin and rerun the
check.

Build the receiver:

```bash
cargo build --manifest-path desktop/Cargo.toml --release -p receiver-cli
```

Prepare the virtual camera. Review existing `/dev/video*` devices first:

```bash
scripts/linux/inspect-video-devices.sh
scripts/linux/setup-v4l2loopback.sh 10
sudo modprobe v4l2loopback devices=1 video_nr=10 \
  card_label="Mobile Webcam" exclusive_caps=1
```

Add the user running the receiver to the `video` group if the device access
check reports permission denied, then start a new login session. The setup
script never unloads a module or changes Secure Boot settings.

Start the receiver:

```bash
desktop/target/release/receiver-cli \
  --listen 0.0.0.0 --control-port 5001 --media-port 5000 \
  --device /dev/video10 --output-format auto \
  --demux-latency-ms 0 --queue-frames 2
```

Open TCP port `5001` and UDP port `5000` in the host firewall only on the
trusted local interface.
