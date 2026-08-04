#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
SUSTAINED_SECONDS="${SUSTAINED_SECONDS:-1800}"
CONTROL_PORT="${1:-55021}"
SRT_PORT="${2:-55020}"
DEVICE="${3:-/dev/video10}"
RECEIVER_BINARY="${RECEIVER_BINARY:-}"

RECEIVER_WAIT_SECONDS=20
RECEIVE_WAIT_SECONDS=20
STARTUP_POLL_INTERVAL_SECONDS=1
POLL_INTERVAL_SECONDS=5
REQUEST_H264_BITRATE_BPS=4000000
REQUEST_H265_BITRATE_BPS=7000000
MAX_RSS_GROWTH_KB=131072
MAX_THREAD_GROWTH=4

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
[[ -c "${DEVICE}" ]] || { echo "Virtual-camera device is unavailable: ${DEVICE}" >&2; exit 1; }

if [[ -z "${RECEIVER_BINARY}" ]]; then
  cargo build --manifest-path "${ROOT_DIR}/desktop/Cargo.toml" --release -p receiver-cli
  RECEIVER_BINARY="${ROOT_DIR}/desktop/target/release/mobile-webcam-receiver"
fi

"${RECEIVER_BINARY}" \
  --control-port "${CONTROL_PORT}" \
  --srt-port "${SRT_PORT}" \
  --advertise-host 127.0.0.1 \
  --device "${DEVICE}" \
  --log-level warn >"${RECEIVER_LOG}" 2>&1 &
RECEIVER_PID=$!

health_url="http://127.0.0.1:${CONTROL_PORT}/v2/health"
for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
  if curl -fsS "${health_url}" >/dev/null 2>&1; then
    break
  fi
  sleep "${STARTUP_POLL_INTERVAL_SECONDS}"
done
curl -fsS "${health_url}" >/dev/null

capabilities_json="$(curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v2/capabilities")"
profile_width="$(jq -r '.outputProfile.width' <<<"${capabilities_json}")"
profile_height="$(jq -r '.outputProfile.height' <<<"${capabilities_json}")"
profile_fps="$(jq -r '.outputProfile.fps' <<<"${capabilities_json}")"
session_json="$(curl -fsS -X POST "http://127.0.0.1:${CONTROL_PORT}/v2/sessions" \
  -H 'content-type: application/json' \
  --data "$(jq -nc \
    --argjson width "${profile_width}" \
    --argjson height "${profile_height}" \
    --argjson fps "${profile_fps}" \
    --argjson h264 "${REQUEST_H264_BITRATE_BPS}" \
    --argjson h265 "${REQUEST_H265_BITRATE_BPS}" \
    '{protocolVersion:2,preferredCodecs:["h264"],profile:{width:$width,height:$height,fps:$fps},bitrateByCodec:{h264:$h264,h265:$h265}}')")"

session_id="$(jq -r '.sessionId' <<<"${session_json}")"
session_host="$(jq -r '.transport.host' <<<"${session_json}")"
session_port="$(jq -r '.transport.port' <<<"${session_json}")"
session_stream_id="$(jq -r '.transport.streamId' <<<"${session_json}")"
session_latency="$(jq -r '.transport.latencyMs' <<<"${session_json}")"
session_passphrase="$(jq -r '.transport.passphrase' <<<"${session_json}")"
session_bitrate="$(jq -r '.video.bitrateBps' <<<"${session_json}")"

"${SCRIPT_DIR}/synthetic-srt-sender.sh" \
  "${session_host}" "${session_port}" "${session_stream_id}" "${session_passphrase}" \
  "${session_latency}" "${profile_width}" "${profile_height}" "${profile_fps}" \
  "${session_bitrate}" >"${SENDER_LOG}" 2>&1 &
SENDER_PID=$!

read_rss_kb() {
  awk '/^VmRSS:/ { print $2 }' "/proc/${RECEIVER_PID}/status"
}

read_thread_count() {
  awk '/^Threads:/ { print $2 }' "/proc/${RECEIVER_PID}/status"
}

last_decoded_frames=0
for _ in $(seq 1 "${RECEIVE_WAIT_SECONDS}"); do
  initial_status_json="$(curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}")"
  initial_state="$(jq -r '.state' <<<"${initial_status_json}")"
  initial_decoded_frames="$(jq -r '.metrics.decodedFrames // 0' <<<"${initial_status_json}")"
  if [[ "${initial_state}" == receiving ]] && (( initial_decoded_frames > 0 )); then
    last_decoded_frames="${initial_decoded_frames}"
    break
  fi
  sleep "${STARTUP_POLL_INTERVAL_SECONDS}"
done
[[ "${last_decoded_frames}" != 0 ]] || {
  echo "sustained run did not reach receiving state" >&2
  cat "${RECEIVER_LOG}" >&2
  exit 1
}
baseline_rss_kb="$(read_rss_kb)"
max_rss_kb="${baseline_rss_kb}"
baseline_thread_count="$(read_thread_count)"
max_thread_count="${baseline_thread_count}"
started_at_seconds="${SECONDS}"

while (( SECONDS - started_at_seconds < SUSTAINED_SECONDS )); do
  status_json="$(curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}")"
  state="$(jq -r '.state' <<<"${status_json}")"
  decoded_frames="$(jq -r '.metrics.decodedFrames // 0' <<<"${status_json}")"
  [[ "${state}" == receiving ]] || {
    echo "sustained run lost receiving state: ${state}" >&2
    cat "${RECEIVER_LOG}" >&2
    exit 1
  }
  (( decoded_frames >= last_decoded_frames )) || {
    echo "decoded frame count moved backwards" >&2
    exit 1
  }
  last_decoded_frames="${decoded_frames}"

  current_rss_kb="$(read_rss_kb)"
  current_thread_count="$(read_thread_count)"
  (( current_rss_kb > max_rss_kb )) && max_rss_kb="${current_rss_kb}"
  (( current_thread_count > max_thread_count )) && max_thread_count="${current_thread_count}"
  echo "sustained: ${decoded_frames} decoded frames, RSS ${current_rss_kb} KiB, threads ${current_thread_count}"
  sleep "${POLL_INTERVAL_SECONDS}"
done

kill -TERM "${SENDER_PID}" 2>/dev/null || true
wait "${SENDER_PID}" 2>/dev/null || true
SENDER_PID=""
curl -fsS -X DELETE "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}" -o /dev/null
curl -fsS -X DELETE "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}" -o /dev/null

rss_growth_kb=$((max_rss_kb - baseline_rss_kb))
thread_growth=$((max_thread_count - baseline_thread_count))
(( rss_growth_kb <= MAX_RSS_GROWTH_KB )) || {
  echo "receiver RSS grew by ${rss_growth_kb} KiB during sustained run" >&2
  exit 1
}
(( thread_growth <= MAX_THREAD_GROWTH )) || {
  echo "receiver thread count grew by ${thread_growth} during sustained run" >&2
  exit 1
}

v4l2_info="$(v4l2-ctl --all --device="${DEVICE}" 2>/dev/null || true)"
grep -q '1280/720' <<<"${v4l2_info}" || {
  echo "persistent output no longer advertises the configured profile" >&2
  exit 1
}

echo "SRT sustained run passed: ${SUSTAINED_SECONDS}s, decoded frames ${last_decoded_frames}, RSS growth ${rss_growth_kb} KiB, thread growth ${thread_growth}."
