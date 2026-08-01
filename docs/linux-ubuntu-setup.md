# Ubuntu setup

Install the Rust build tools, GStreamer runtime and development packages,
codec plugins, V4L2 tools, and loopback module:

```bash
sudo apt update
sudo apt install -y build-essential pkg-config \
  libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
  gstreamer1.0-tools gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav \
  v4l-utils v4l2loopback-dkms
```

Check the required elements:

```bash
gst-inspect-1.0 udpsrc tsparse tsdemux h264parse h265parse decodebin v4l2sink
```

The `x264enc` and `x265enc` elements are only required for synthetic sender
tests. The receiver uses `decodebin` and the installed decoder plugins.

Build and prepare the receiver:

```bash
cargo build --manifest-path desktop/Cargo.toml --release -p receiver-cli
scripts/linux/inspect-video-devices.sh
scripts/linux/setup-v4l2loopback.sh 10
sudo modprobe v4l2loopback devices=1 video_nr=10 \
  card_label="Mobile Webcam" exclusive_caps=1
```

Run it as a user who can open `/dev/video10`:

```bash
desktop/target/release/receiver-cli \
  --listen 0.0.0.0 --control-port 5001 --media-port 5000 \
  --device /dev/video10 --output-format auto
```

Ubuntu Secure Boot may reject an unsigned DKMS module. Install the supported
signed package or enroll the required key according to the host policy. Do
not disable Secure Boot as an automatic troubleshooting step.
