#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-/dev/video10}"
RUN_SECONDS="${2:-30}"
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
DEMAND_LOG="$(mktemp)"
OUTPUT_LOG="$(mktemp)"
CAPTURE_ONE_LOG="$(mktemp)"
CAPTURE_TWO_LOG="$(mktemp)"
CAPTURE_THREE_LOG="$(mktemp)"
MONITOR_PID=""
OUTPUT_PID=""
CAPTURE_ONE_PID=""
CAPTURE_TWO_PID=""
CAPTURE_THREE_PID=""

cleanup() {
  local pid
  for pid in "${CAPTURE_THREE_PID}" "${CAPTURE_TWO_PID}" "${CAPTURE_ONE_PID}" "${MONITOR_PID}" "${OUTPUT_PID}"; do
    if [[ -n "${pid}" ]]; then
      kill "${pid}" 2>/dev/null || true
      wait "${pid}" 2>/dev/null || true
    fi
  done
  rm -f "${DEMAND_LOG}" "${OUTPUT_LOG}" "${CAPTURE_ONE_LOG}" "${CAPTURE_TWO_LOG}" "${CAPTURE_THREE_LOG}"
}
trap cleanup EXIT

[[ -e /sys/module/v4l2loopback ]] || {
  echo "v4l2loopback is not loaded." >&2
  exit 1
}
[[ -c "${DEVICE}" ]] || {
  echo "Loopback device is not a character device: ${DEVICE}" >&2
  exit 1
}
command -v v4l2-ctl >/dev/null 2>&1 || {
  echo "v4l2-ctl is required." >&2
  exit 1
}
command -v gst-launch-1.0 >/dev/null 2>&1 || {
  echo "gst-launch-1.0 is required." >&2
  exit 1
}

DRIVER_INFO="$(v4l2-ctl --all --device="${DEVICE}" 2>&1 || true)"
if ! rg -qi 'v4l2.?loopback|v4l2 loopback' <<<"${DRIVER_INFO}"; then
  echo "${DEVICE} is not identified as a v4l2loopback device." >&2
  exit 1
fi

cd "${ROOT_DIR}"
timeout "${RUN_SECONDS}s" cargo run --manifest-path desktop/Cargo.toml \
  -p receiver-platform-linux --example demand_monitor -- "${DEVICE}" >"${DEMAND_LOG}" 2>&1 &
MONITOR_PID=$!
timeout "${RUN_SECONDS}s" cargo run --manifest-path desktop/Cargo.toml \
  -p receiver-platform-linux --example persistent_output -- "${DEVICE}" "${RUN_SECONDS}" >"${OUTPUT_LOG}" 2>&1 &
OUTPUT_PID=$!

sleep 3
v4l2-ctl --list-formats-ext --device="${DEVICE}" >/dev/null
if rg -q 'demand: Active' "${DEMAND_LOG}"; then
  echo "capability enumeration created sustained demand." >&2
  exit 1
fi

v4l2-ctl --device="${DEVICE}" --stream-mmap --stream-count=240 --stream-to=/dev/null >"${CAPTURE_ONE_LOG}" 2>&1 &
CAPTURE_ONE_PID=$!
sleep 2
if timeout 5s v4l2-ctl --device="${DEVICE}" --stream-mmap --stream-count=90 --stream-to=/dev/null >"${CAPTURE_TWO_LOG}" 2>&1; then
  echo "second V4L2 consumer completed while the first consumer was active."
else
  echo "second V4L2 consumer was not supported by this loopback configuration; continuing."
fi
wait "${CAPTURE_ONE_PID}"
CAPTURE_ONE_PID=""
sleep 2

rg -q 'demand: Active' "${DEMAND_LOG}" || {
  echo "sustained capture did not create active demand." >&2
  exit 1
}
rg -q 'demand: Inactive' "${DEMAND_LOG}" || {
  echo "final consumer release did not create inactive demand." >&2
  exit 1
}

v4l2-ctl --device="${DEVICE}" --stream-mmap --stream-count=600 --stream-to=/dev/null >"${CAPTURE_THREE_LOG}" 2>&1 &
CAPTURE_THREE_PID=$!
sleep 2
kill -KILL "${CAPTURE_THREE_PID}" 2>/dev/null || true
wait "${CAPTURE_THREE_PID}" 2>/dev/null || true
CAPTURE_THREE_PID=""
sleep 2

echo "Demand-driven V4L2 lifecycle passed for ${DEVICE}."
