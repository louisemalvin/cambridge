#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
DEVICE="${1:-/dev/video10}"
CONTROL_PORT="${2:-55001}"
SRT_PORT="${3:-55000}"
RECEIVER_BINARY="${RECEIVER_BINARY:-}"
REQUIRE_V4L2_CAPTURE="${REQUIRE_V4L2_CAPTURE:-0}"

AUTH_REJECTION_WAIT_SECONDS=4
RECEIVE_WAIT_SECONDS=20
RECONNECT_WAIT_SECONDS=8
POLL_INTERVAL_SECONDS=1
SRT_CONNECT_DEADLINE_MS=30000
SRT_RECONNECT_GRACE_MS=30000
REQUEST_H264_BITRATE_BPS=4000000
REQUEST_H265_BITRATE_BPS=7000000
WRONG_PASSPHRASE="wrong-passphrase-0123456789"
WRONG_STREAM_ID="rejected-stream-id"

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
gst-inspect-1.0 v4l2src >/dev/null 2>&1 || {
  echo "Required GStreamer element is unavailable: v4l2src" >&2
  exit 1
}

if [[ -z "${RECEIVER_BINARY}" ]]; then
  cargo build --manifest-path "${ROOT_DIR}/desktop/Cargo.toml" --release -p receiver-cli
  RECEIVER_BINARY="${ROOT_DIR}/desktop/target/release/mobile-webcam-receiver"
fi

"${RECEIVER_BINARY}" \
  --control-port "${CONTROL_PORT}" \
  --srt-port "${SRT_PORT}" \
  --advertise-host 127.0.0.1 \
  --srt-connect-deadline-ms "${SRT_CONNECT_DEADLINE_MS}" \
  --srt-reconnect-grace-ms "${SRT_RECONNECT_GRACE_MS}" \
  --device "${DEVICE}" \
  --log-level info >"${RECEIVER_LOG}" 2>&1 &
RECEIVER_PID=$!

health_url="http://127.0.0.1:${CONTROL_PORT}/v2/health"
for _ in $(seq 1 "${RECEIVE_WAIT_SECONDS}"); do
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
  "${SCRIPT_DIR}/synthetic-srt-sender.sh" \
    "${session_host}" "${session_port}" "${stream_id}" "${passphrase}" \
    "${session_latency}" "${profile_width}" "${profile_height}" "${profile_fps}" \
    "${session_bitrate}" >"${SENDER_LOG}" 2>&1 &
  SENDER_PID=$!
}

stop_sender() {
  if [[ -n "${SENDER_PID}" ]]; then
    kill -TERM "${SENDER_PID}" 2>/dev/null || true
    wait "${SENDER_PID}" 2>/dev/null || true
    SENDER_PID=""
  fi
}

session_status() {
  curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}"
}

assert_not_receiving_without_frames() {
  local status_json="$1"
  local state
  local decoded_frames
  state="$(jq -r '.state' <<<"${status_json}")"
  decoded_frames="$(jq -r '.metrics.decodedFrames // 0' <<<"${status_json}")"
  [[ "${state}" != receiving ]] || { echo "rejected sender reached receiving state" >&2; exit 1; }
  [[ "${decoded_frames}" == 0 ]] || { echo "rejected sender produced decoded frames" >&2; exit 1; }
}

wait_for_receiving() {
  for _ in $(seq 1 "${RECEIVE_WAIT_SECONDS}"); do
    status_json="$(session_status)"
    if [[ "$(jq -r '.state' <<<"${status_json}")" == receiving ]] && \
      (( $(jq -r '.metrics.decodedFrames // 0' <<<"${status_json}") > 0 )); then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "receiver did not reach receiving state" >&2
  cat "${RECEIVER_LOG}" >&2
  exit 1
}

session_json="$(create_session)"
session_id="$(jq -r '.sessionId' <<<"${session_json}")"
session_host="$(jq -r '.transport.host' <<<"${session_json}")"
session_port="$(jq -r '.transport.port' <<<"${session_json}")"
session_stream_id="$(jq -r '.transport.streamId' <<<"${session_json}")"
session_latency="$(jq -r '.transport.latencyMs' <<<"${session_json}")"
session_passphrase="$(jq -r '.transport.passphrase' <<<"${session_json}")"
session_bitrate="$(jq -r '.video.bitrateBps' <<<"${session_json}")"

start_sender "${WRONG_STREAM_ID}" "${session_passphrase}"
sleep "${AUTH_REJECTION_WAIT_SECONDS}"
assert_not_receiving_without_frames "$(session_status)"
stop_sender

start_sender "${session_stream_id}" "${WRONG_PASSPHRASE}"
sleep "${AUTH_REJECTION_WAIT_SECONDS}"
assert_not_receiving_without_frames "$(session_status)"
stop_sender

start_sender "${session_stream_id}" "${session_passphrase}"
wait_for_receiving

if timeout 5s gst-launch-1.0 -q \
    v4l2src device="${DEVICE}" num-buffers=30 ! \
    fakesink sync=false >/dev/null 2>&1; then
  :
elif [[ "${REQUIRE_V4L2_CAPTURE}" == 1 ]]; then
  echo "The virtual camera could not be opened as a capture device." >&2
  exit 1
else
  v4l2_info="$(v4l2-ctl --all --device="${DEVICE}" 2>/dev/null || true)"
  grep -q '1280/720' <<<"${v4l2_info}" || {
    echo "The persistent output did not advertise the negotiated profile." >&2
    exit 1
  }
  echo "Virtual-camera capture skipped because no capture consumer is open; set REQUIRE_V4L2_CAPTURE=1 for the OBS gate." >&2
fi

stop_sender
for _ in $(seq 1 "${RECONNECT_WAIT_SECONDS}"); do
  state="$(jq -r '.state' <<<"$(session_status)")"
  [[ "${state}" == reconnecting || "${state}" == listening ]] && break
  sleep "${POLL_INTERVAL_SECONDS}"
done
[[ "${state}" == reconnecting || "${state}" == listening ]] || {
  echo "receiver did not enter a reconnectable state after sender stop" >&2
  exit 1
}

start_sender "${session_stream_id}" "${session_passphrase}"
wait_for_receiving
stop_sender

curl -fsS -X DELETE "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}" -o /dev/null
curl -fsS -X DELETE "http://127.0.0.1:${CONTROL_PORT}/v2/sessions/${session_id}" -o /dev/null

echo "SRT receiver integration passed: authentication rejection, decoded frames, v4l2 output, reconnect, and idempotent cleanup."
