#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PORT="${2:-5000}"

for element in gst-launch-1.0 videotestsrc x264enc h264parse mpegtsmux udpsink; do
  if ! gst-inspect-1.0 "$element" >/dev/null 2>&1; then
    echo "Required GStreamer element is unavailable: $element" >&2
    exit 1
  fi
done

exec gst-launch-1.0 -e -v \
  videotestsrc is-live=true pattern=ball ! \
  video/x-raw,width=1920,height=1080,framerate=30/1 ! \
  x264enc tune=zerolatency bitrate=10000 key-int-max=30 ! \
  h264parse config-interval=-1 ! \
  mpegtsmux ! \
  udpsink host="$HOST" port="$PORT" sync=false

