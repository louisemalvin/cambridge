#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
NETNS_CHILD="${NETNS_CHILD:-0}"

if [[ "${NETNS_CHILD}" != 1 ]]; then
  command -v unshare >/dev/null 2>&1 || {
    echo "Required command is unavailable: unshare" >&2
    exit 1
  }
  exec unshare --user --map-root-user --net \
    env NETNS_CHILD=1 "${BASH_SOURCE[0]}" "$@"
fi

command -v ip >/dev/null 2>&1 || {
  echo "Required command is unavailable: ip" >&2
  exit 1
}
command -v tc >/dev/null 2>&1 || {
  echo "Required command is unavailable: tc" >&2
  exit 1
}

NETEM_LOSS_PERCENT=2
NETEM_DELAY_MILLISECONDS=20
NETEM_DELAY_JITTER_MILLISECONDS=5
NETEM_REORDER_PERCENT=10
NETEM_CORRELATION_PERCENT=50
CONTROL_PORT="${1:-55041}"
SRT_PORT="${2:-55040}"
DEVICE="${3:-/dev/video10}"

ip link set lo up
tc qdisc add dev lo root netem \
  loss "${NETEM_LOSS_PERCENT}%" \
  delay "${NETEM_DELAY_MILLISECONDS}ms" "${NETEM_DELAY_JITTER_MILLISECONDS}ms" \
  reorder "${NETEM_REORDER_PERCENT}%" "${NETEM_CORRELATION_PERCENT}%"

cleanup() {
  tc qdisc del dev lo root >/dev/null 2>&1 || true
}
trap cleanup EXIT

RECEIVER_BINARY="${RECEIVER_BINARY:-${ROOT_DIR}/desktop/target/release/mobile-webcam-receiver}"
if [[ ! -x "${RECEIVER_BINARY}" ]]; then
  cargo build --manifest-path "${ROOT_DIR}/desktop/Cargo.toml" --release -p receiver-cli
fi

RECEIVER_BINARY="${RECEIVER_BINARY}" \
  "${SCRIPT_DIR}/test-srt-receiver.sh" \
  "${DEVICE}" "${CONTROL_PORT}" "${SRT_PORT}"
