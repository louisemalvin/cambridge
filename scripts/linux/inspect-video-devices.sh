#!/usr/bin/env bash
set -euo pipefail

if command -v v4l2-ctl >/dev/null 2>&1; then
  v4l2-ctl --list-devices
else
  echo "v4l2-ctl is unavailable; showing sysfs video devices instead."
fi

shopt -s nullglob
is_loopback_entry() {
  local entry="$1"
  local driver
  local sysfs_target
  driver="$(readlink -f "${entry}/device/driver" 2>/dev/null || true)"
  sysfs_target="$(readlink -f "${entry}" 2>/dev/null || true)"
  [[ "${driver}" == */v4l2loopback ]] || \
    { [[ "${sysfs_target}" == /sys/devices/virtual/video4linux/video* ]] && [[ -e /sys/module/v4l2loopback ]]; }
}

for entry in /sys/class/video4linux/video*; do
  device="/dev/${entry##*/}"
  name="$(cat "$entry/name" 2>/dev/null || true)"
  driver="$(readlink -f "$entry/device/driver" 2>/dev/null || true)"
  if is_loopback_entry "${entry}"; then
    driver="v4l2loopback"
  fi
  printf '%s\tname=%s\tdriver=%s\n' "$device" "${name:-unknown}" "${driver:-unknown}"
done
