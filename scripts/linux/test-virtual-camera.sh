#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-/dev/video10}"

if [[ ! -e "$DEVICE" ]]; then
  echo "Virtual-camera device does not exist: $DEVICE" >&2
  exit 1
fi
for element in gst-launch-1.0 videotestsrc videoconvert v4l2sink; do
  if ! gst-inspect-1.0 "$element" >/dev/null 2>&1; then
    echo "Required GStreamer element is unavailable: $element" >&2
    exit 1
  fi
done

exec gst-launch-1.0 -e -v \
  videotestsrc is-live=true pattern=ball ! \
  video/x-raw,width=1920,height=1080,framerate=30/1 ! \
  videoconvert ! \
  v4l2sink device="$DEVICE" sync=false

