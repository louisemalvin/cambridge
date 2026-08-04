#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-/dev/video10}"
REQUIRE_CAPTURE="${REQUIRE_CAPTURE:-1}"
OBS_PROCESS_NAME="${OBS_PROCESS_NAME:-obs}"
CAPTURE_TIMEOUT_SECONDS=10
EXPECTED_PROFILE='1280/720'

command -v pgrep >/dev/null 2>&1 || {
  echo "Required command is unavailable: pgrep" >&2
  exit 1
}
command -v v4l2-ctl >/dev/null 2>&1 || {
  echo "Required command is unavailable: v4l2-ctl" >&2
  exit 1
}
command -v gst-launch-1.0 >/dev/null 2>&1 || {
  echo "Required command is unavailable: gst-launch-1.0" >&2
  exit 1
}
[[ -c "${DEVICE}" ]] || { echo "Virtual-camera device is unavailable: ${DEVICE}" >&2; exit 1; }
pgrep -x "${OBS_PROCESS_NAME}" >/dev/null || {
  echo "OBS is not running; open OBS and add Mobile Webcam as a V4L2 source." >&2
  exit 1
}

v4l2_info="$(v4l2-ctl --all --device="${DEVICE}" 2>/dev/null || true)"
grep -q "${EXPECTED_PROFILE}" <<<"${v4l2_info}" || {
  echo "The virtual camera does not advertise the receiver-owned profile." >&2
  exit 1
}

if timeout "${CAPTURE_TIMEOUT_SECONDS}s" gst-launch-1.0 -q \
    v4l2src device="${DEVICE}" num-buffers=30 ! \
    videoconvert ! fakesink sync=false >/dev/null 2>&1; then
  echo "OBS virtual-camera gate passed: OBS is running and Mobile Webcam is capturable."
elif [[ "${REQUIRE_CAPTURE}" == 1 ]]; then
  echo "OBS is running but the virtual camera could not be captured." >&2
  exit 1
else
  echo "OBS process and virtual-camera profile are present; capture was not available."
fi
