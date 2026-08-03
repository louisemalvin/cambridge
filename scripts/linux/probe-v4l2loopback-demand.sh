#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-/dev/video10}"

if [[ ! -e /sys/module/v4l2loopback ]]; then
  echo "v4l2loopback is not loaded. Run scripts/linux/setup-v4l2loopback.sh first." >&2
  exit 1
fi
if [[ ! -e "${DEVICE}" ]]; then
  echo "Loopback device does not exist: ${DEVICE}" >&2
  exit 1
fi

echo "Starting the client-usage event monitor for ${DEVICE}."
echo "Use another terminal to enumerate or capture the device, then press Ctrl-C."
exec cargo run --manifest-path desktop/Cargo.toml \
  -p receiver-platform-linux --example demand_monitor -- "${DEVICE}"
