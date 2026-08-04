#!/usr/bin/env bash
set -euo pipefail

HOST="${1:?receiver host is required}"
PORT="${2:?receiver SRT port is required}"
STREAM_ID="${3:?SRT stream ID is required}"
PASSPHRASE="${4:?SRT passphrase is required}"
LATENCY_MS="${5:?SRT latency is required}"
WIDTH="${6:?video width is required}"
HEIGHT="${7:?video height is required}"
FPS="${8:?video frame rate is required}"
BITRATE_BPS="${9:?video bitrate is required}"

SRT_KEY_LENGTH_BYTES=32
BITS_PER_KILOBIT=1000

if ! command -v gst-launch-1.0 >/dev/null 2>&1; then
  echo "Required command is unavailable: gst-launch-1.0" >&2
  exit 1
fi

for element in videotestsrc x264enc h264parse mpegtsmux srtsink; do
  if ! gst-inspect-1.0 "${element}" >/dev/null 2>&1; then
    echo "Required GStreamer element is unavailable: ${element}" >&2
    exit 1
  fi
done

BITRATE_KBPS=$((BITRATE_BPS / BITS_PER_KILOBIT))
if (( BITRATE_KBPS == 0 )); then
  echo "The video bitrate must be at least ${BITS_PER_KILOBIT} bits per second." >&2
  exit 1
fi

exec gst-launch-1.0 -e \
  videotestsrc is-live=true pattern=ball ! \
  "video/x-raw,width=${WIDTH},height=${HEIGHT},framerate=${FPS}/1" ! \
  x264enc tune=zerolatency bitrate="${BITRATE_KBPS}" key-int-max="${FPS}" ! \
  h264parse config-interval=1 ! \
  mpegtsmux ! \
  srtsink uri="srt://${HOST}:${PORT}?streamid=${STREAM_ID}&latency=${LATENCY_MS}&passphrase=${PASSPHRASE}&pbkeylen=${SRT_KEY_LENGTH_BYTES}" \
    authentication=true sync=false
