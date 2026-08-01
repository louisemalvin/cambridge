#!/usr/bin/env bash
set -euo pipefail

find_loopback_device() {
  shopt -s nullglob
  local entry
  local driver
  local sysfs_target
  for entry in /sys/class/video4linux/video*; do
    driver="$(readlink -f "${entry}/device/driver" 2>/dev/null || true)"
    sysfs_target="$(readlink -f "${entry}" 2>/dev/null || true)"
    if [[ "${driver}" == */v4l2loopback ]] || \
      { [[ "${sysfs_target}" == /sys/devices/virtual/video4linux/video* ]] && [[ -e /sys/module/v4l2loopback ]]; }; then
      printf '/dev/%s\n' "${entry##*/}"
      return 0
    fi
  done
  return 1
}

DEVICE="${1:-}"
if [[ -z "${DEVICE}" ]]; then
  DEVICE="$(find_loopback_device || true)"
fi

if [[ -z "${DEVICE}" ]]; then
  echo "No v4l2loopback device found. Run scripts/linux/install-receiver.sh first." >&2
  exit 1
fi
if [[ ! -e "${DEVICE}" ]]; then
  echo "Virtual-camera device does not exist: ${DEVICE}" >&2
  exit 1
fi
if ! command -v gst-launch-1.0 >/dev/null 2>&1; then
  echo "Required command is unavailable: gst-launch-1.0" >&2
  exit 1
fi
for element in videotestsrc videoconvert v4l2sink; do
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
