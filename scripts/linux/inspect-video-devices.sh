#!/usr/bin/env bash
set -euo pipefail

if command -v v4l2-ctl >/dev/null 2>&1; then
  v4l2-ctl --list-devices
else
  echo "v4l2-ctl is unavailable; showing sysfs video devices instead."
fi

shopt -s nullglob
for entry in /sys/class/video4linux/video*; do
  device="/dev/${entry##*/}"
  name="$(cat "$entry/name" 2>/dev/null || true)"
  driver="$(readlink -f "$entry/device/driver" 2>/dev/null || true)"
  printf '%s\tname=%s\tdriver=%s\n' "$device" "${name:-unknown}" "${driver:-unknown}"
done

