#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
CYCLES="${1:-20}"
CONTROL_PORT="${2:-55011}"
SRT_PORT="${3:-55010}"
DEVICE="${4:-/dev/video10}"
RECEIVER_BINARY="${RECEIVER_BINARY:-}"

RECEIVER_WAIT_SECONDS=20
RECEIVE_WAIT_SECONDS=20
POLL_INTERVAL_SECONDS=1
DISCONNECT_WAIT_SECONDS=8
REQUEST_H264_BITRATE_BPS=4000000
REQUEST_H265_BITRATE_BPS=7000000
MAX_RSS_GROWTH_KB=131072
MEMORY_WARMUP_CYCLES=2
MAX_STEADY_RSS_GROWTH_KB=32768

RECEIVER_PID=""
SENDER_PID=""
RECEIVER_LOG="$(mktemp)"
SENDER_LOG="$(mktemp)"

cleanup() {
  if [[ -n "${SENDER_PID}" ]]; then
    kill -TERM "${SENDER_PID}" 2>/dev/null || true
    wait "${SENDER_PID}" 2>/dev/null || true
  fi
  if [[ -n "${RECEIVER_PID}" ]]; then
    kill -INT "${RECEIVER_PID}" 2>/dev/null || true
    wait "${RECEIVER_PID}" 2>/dev/null || true
  fi
  rm -f "${RECEIVER_LOG}" "${SENDER_LOG}"
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command is unavailable: $1" >&2
    exit 1
  }
}

require_command curl
require_command jq
require_command v4l2-ctl
require_command gst-launch-1.0
[[ -c "${DEVICE}" ]] || { echo "Virtual-camera device is unavailable: ${DEVICE}" >&2; exit 1; }

if [[ -z "${RECEIVER_BINARY}" ]]; then
  cargo build --manifest-path "${ROOT_DIR}/desktop/Cargo.toml" --release -p receiver-cli
  RECEIVER_BINARY="${ROOT_DIR}/desktop/target/release/mobile-webcam-receiver"
fi

"${RECEIVER_BINARY}" \
  --control-port "${CONTROL_PORT}" \
  --srt-port "${SRT_PORT}" \
  --device "${DEVICE}" \
  --log-level warn >"${RECEIVER_LOG}" 2>&1 &
RECEIVER_PID=$!

health_url="http://127.0.0.1:${CONTROL_PORT}/v2/health"
for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
  if curl -fsS "${health_url}" >/dev/null 2>&1; then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
curl -fsS "${health_url}" >/dev/null

capabilities_json="$(curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v2/capabilities")"
profile_width="$(jq -r '.outputProfile.width' <<<"${capabilities_json}")"
profile_height="$(jq -r '.outputProfile.height' <<<"${capabilities_json}")"
profile_fps="$(jq -r '.outputProfile.fps' <<<"${capabilities_json}")"

create_session() {
  curl -fsS -X POST "http://127.0.0.1:${CONTROL_PORT}/v2/sessions" \
    -H 'content-type: application/json' \
    --data "$(jq -nc \
      --argjson width "${profile_width}" \
      --argjson height "${profile_height}" \
      --argjson fps "${profile_fps}" \
      --argjson h264 "${REQUEST_H264_BITRATE_BPS}" \
      --argjson h265 "${REQUEST_H265_BITRATE_BPS}" \
      '{protocolVersion:2,preferredCodecs:["h264"],profile:{width:$width,height:$height,fps:$fps},bitrateByCodec:{h264:$h264,h265:$h265}}')"
}

start_sender() {
  local stream_id="$1"
  local passphrase="$2"
  local latency_ms="$3"
  local bitrate_bps="$4"
  "${SCRIPT_DIR}/synthetic-srt-sender.sh" \
    "${session_host}" "${session_port}" "${stream_id}" "${passphrase}" \
    "${latency_ms}" "${profile_width}" "${profile_height}" "${profile_fps}" \
    "${bitrate_bps}" >"${SENDER_LOG}" 2>&1 &
  SENDER_PID=$!
}

stop_sender() {
  if [[ -n "${SENDER_PID}" ]]; then
    kill -TERM "${SENDER_PID}" 2>/dev/null || true
    wait "${SENDER_PID}" 2>/dev/null || true
    SENDER_PID=""
  fi
}

wait_for_frames() {
  local session_id="$1"
  local status_json
  for _ in $(seq 1 "${RECEIVE_WAIT_SECONDS}"); do
    status_json="$(curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}")"
    if [[ "$(jq -r '.state' <<<"${status_json}")" == receiving ]] && \
      (( $(jq -r '.metrics.decodedFrames // 0' <<<"${status_json}") > 0 )); then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "cycle did not produce decoded frames" >&2
  cat "${RECEIVER_LOG}" >&2
  exit 1
}

wait_for_disconnect_state() {
  local session_id="$1"
  local state
  for _ in $(seq 1 "${DISCONNECT_WAIT_SECONDS}"); do
    state="$(curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}" | jq -r '.state')"
    if [[ "${state}" == reconnecting || "${state}" == listening ]]; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "cycle did not enter a reconnectable state" >&2
  exit 1
}

read_rss_kb() {
  awk '/^VmRSS:/ { print $2 }' "/proc/${RECEIVER_PID}/status"
}

baseline_rss_kb="$(read_rss_kb)"
max_rss_kb="${baseline_rss_kb}"
warmup_rss_kb=""
max_steady_rss_kb=""

for cycle in $(seq 1 "${CYCLES}"); do
  session_json="$(create_session)"
  session_id="$(jq -r '.sessionId' <<<"${session_json}")"
  session_host="$(jq -r '.transport.host' <<<"${session_json}")"
  session_port="$(jq -r '.transport.port' <<<"${session_json}")"
  session_stream_id="$(jq -r '.transport.streamId' <<<"${session_json}")"
  session_latency="$(jq -r '.transport.latencyMs' <<<"${session_json}")"
  session_passphrase="$(jq -r '.transport.passphrase' <<<"${session_json}")"
  session_bitrate="$(jq -r '.video.bitrateBps' <<<"${session_json}")"

  start_sender "${session_stream_id}" "${session_passphrase}" \
    "${session_latency}" "${session_bitrate}"
  wait_for_frames "${session_id}"
  stop_sender
  wait_for_disconnect_state "${session_id}"

  curl -fsS -X DELETE "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}" -o /dev/null
  curl -fsS -X DELETE "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}" -o /dev/null
  status_code="$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}")"
  [[ "${status_code}" == 404 ]] || {
    echo "deleted session remained addressable in cycle ${cycle}" >&2
    exit 1
  }

  current_rss_kb="$(read_rss_kb)"
  (( current_rss_kb > max_rss_kb )) && max_rss_kb="${current_rss_kb}"
  if (( cycle == MEMORY_WARMUP_CYCLES )); then
    warmup_rss_kb="${current_rss_kb}"
    max_steady_rss_kb="${current_rss_kb}"
  elif (( cycle > MEMORY_WARMUP_CYCLES && current_rss_kb > max_steady_rss_kb )); then
    max_steady_rss_kb="${current_rss_kb}"
  fi
  echo "cycle ${cycle}/${CYCLES}: receiver RSS ${current_rss_kb} KiB"
done

rss_growth_kb=$((max_rss_kb - baseline_rss_kb))
if (( CYCLES >= MEMORY_WARMUP_CYCLES )); then
  steady_rss_growth_kb=$((max_steady_rss_kb - warmup_rss_kb))
else
  steady_rss_growth_kb=0
fi
(( rss_growth_kb <= MAX_RSS_GROWTH_KB )) || {
  echo "receiver RSS grew by ${rss_growth_kb} KiB including startup warm-up" >&2
  exit 1
}
(( steady_rss_growth_kb <= MAX_STEADY_RSS_GROWTH_KB )) || {
  echo "receiver RSS grew by ${steady_rss_growth_kb} KiB after the first ${MEMORY_WARMUP_CYCLES} lifecycle cycles" >&2
  exit 1
}

v4l2_info="$(v4l2-ctl --all --device="${DEVICE}" 2>/dev/null || true)"
grep -q '1280/720' <<<"${v4l2_info}" || {
  echo "persistent output no longer advertises the configured profile" >&2
  exit 1
}

echo "SRT lifecycle passed: ${CYCLES} cycles, idempotent cleanup, persistent output, RSS growth ${rss_growth_kb} KiB including warm-up and ${steady_rss_growth_kb} KiB after warm-up."
