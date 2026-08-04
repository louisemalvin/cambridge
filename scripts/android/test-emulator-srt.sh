#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-}"
if [[ -z "${SDK_DIR}" && -f "${ROOT_DIR}/android/local.properties" ]]; then
  SDK_DIR="$(sed -n 's/^sdk.dir=//p' "${ROOT_DIR}/android/local.properties")"
fi

ADB="${ADB:-${SDK_DIR}/platform-tools/adb}"
EMULATOR="${EMULATOR:-${SDK_DIR}/emulator/emulator}"
AVD_NAME="${AVD_NAME:-codex-phone-webcam-api35}"
EMULATOR_PORT="${EMULATOR_PORT:-5554}"
RECEIVER_CONTROL_PORT="${RECEIVER_CONTROL_PORT:-55031}"
RECEIVER_SRT_PORT="${RECEIVER_SRT_PORT:-55030}"
RECEIVER_DEVICE="${RECEIVER_DEVICE:-/dev/video10}"
RECEIVER_BINARY="${RECEIVER_BINARY:-}"
APK_PATH="${APK_PATH:-${ROOT_DIR}/android/app/build/outputs/apk/debug/app-debug.apk}"

VIDEO_WIDTH=1280
VIDEO_HEIGHT=720
VIDEO_FPS=30
VIDEO_DURATION_SECONDS=60
VIDEO_BYTES_PER_PIXEL=2
CONSUMER_FRAME_COUNT=900
SECOND_CONSUMER_FRAME_COUNT=90
STANDBY_FRAME_COUNT=1
MIN_DISTINCT_FRAME_HASHES=2
MIN_DECODED_FRAMES=2
RECEIVER_WAIT_SECONDS=45
EMULATOR_BOOT_WAIT_SECONDS=120
ADB_DEVICE_WAIT_SECONDS=30
POLL_INTERVAL_SECONDS=1
DEMAND_STREAM_TIMEOUT_SECONDS=600
CAMERA_PERMISSION="android.permission.CAMERA"
PACKAGE_NAME="dev.mobilewebcam.sender"
ACTIVITY_NAME="dev.mobilewebcam.sender.app.MainActivity"
EXPECTED_INACTIVE_GENERATION=0
EXPECTED_FIRST_GENERATION=1
ARTIFACT_DIR="${ROOT_DIR}/.artifacts/reliable-streaming-v2"
SAMPLE_VIDEO="${ARTIFACT_DIR}/emulator-moving-sample.mp4"
RECEIVER_LOG="${ARTIFACT_DIR}/emulator-receiver.log"
ANDROID_LOG="${ARTIFACT_DIR}/emulator-android.log"
EMULATOR_CONSOLE_LOG="${ARTIFACT_DIR}/emulator-console.log"
DEMAND_EVENTS="${ARTIFACT_DIR}/emulator-demand-events.log"
FIRST_FRAME_HASHES="${ARTIFACT_DIR}/emulator-v4l2-first.framemd5"
SECOND_FRAME_HASHES="${ARTIFACT_DIR}/emulator-v4l2-reopen.framemd5"
STANDBY_FRAME="${ARTIFACT_DIR}/emulator-v4l2-standby.raw"
FIRST_CAPTURE_LOG="${ARTIFACT_DIR}/emulator-v4l2-first.log"
SECOND_CAPTURE_LOG="${ARTIFACT_DIR}/emulator-v4l2-reopen.log"
STANDBY_CAPTURE_LOG="${ARTIFACT_DIR}/emulator-v4l2-standby.log"
FRAME_BYTES=$((VIDEO_WIDTH * VIDEO_HEIGHT * VIDEO_BYTES_PER_PIXEL))

RECEIVER_PID=""
EMULATOR_PID=""
DEMAND_PID=""
FIRST_CONSUMER_PID=""
SECOND_CONSUMER_PID=""
EMULATOR_SERIAL=""
FIRST_SESSION_ID=""
SECOND_SESSION_ID=""

cleanup_process() {
  local pid="$1"
  if [[ -n "${pid}" ]]; then
    kill "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
  fi
}

adb_target() {
  "${ADB}" -s "${EMULATOR_SERIAL}" "$@"
}

cleanup() {
  cleanup_process "${SECOND_CONSUMER_PID}"
  cleanup_process "${FIRST_CONSUMER_PID}"
  cleanup_process "${DEMAND_PID}"
  if [[ -n "${EMULATOR_SERIAL}" && -x "${ADB}" ]]; then
    adb_target logcat -d >"${ANDROID_LOG}" 2>/dev/null || true
    adb_target emu kill >/dev/null 2>&1 || true
  fi
  cleanup_process "${EMULATOR_PID}"
  cleanup_process "${RECEIVER_PID}"
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command is unavailable: $1" >&2
    exit 1
  }
}

require_command curl
require_command ffmpeg
require_command jq
require_command rg
require_command sha256sum
require_command sort
require_command stat
require_command tr
require_command od
require_command v4l2-ctl
require_command timeout
[[ -x "${ADB}" ]] || { echo "Android adb is unavailable: ${ADB}" >&2; exit 1; }
[[ -x "${EMULATOR}" ]] || { echo "Android emulator is unavailable: ${EMULATOR}" >&2; exit 1; }
[[ -c "${RECEIVER_DEVICE}" ]] || {
  echo "Virtual-camera device is unavailable: ${RECEIVER_DEVICE}" >&2
  exit 1
}

mkdir -p "${ARTIFACT_DIR}"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=${VIDEO_WIDTH}x${VIDEO_HEIGHT}:rate=${VIDEO_FPS}" \
  -t "${VIDEO_DURATION_SECONDS}" -an -c:v libx264 -pix_fmt yuv420p \
  -profile:v baseline -movflags +faststart "${SAMPLE_VIDEO}"

if [[ -z "${RECEIVER_BINARY}" ]]; then
  cargo build --manifest-path "${ROOT_DIR}/desktop/Cargo.toml" --release -p receiver-cli
  RECEIVER_BINARY="${ROOT_DIR}/desktop/target/release/mobile-webcam-receiver"
fi

"${RECEIVER_BINARY}" \
  --control-port "${RECEIVER_CONTROL_PORT}" \
  --srt-port "${RECEIVER_SRT_PORT}" \
  --advertise-host 10.0.2.2 \
  --device "${RECEIVER_DEVICE}" \
  --log-level info >"${RECEIVER_LOG}" 2>&1 &
RECEIVER_PID=$!

health_url="http://127.0.0.1:${RECEIVER_CONTROL_PORT}/v2/health"
capabilities_url="http://127.0.0.1:${RECEIVER_CONTROL_PORT}/v2/capabilities"
demand_url="http://127.0.0.1:${RECEIVER_CONTROL_PORT}/v2/demand/subscribe"
for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
  if curl -fsS "${health_url}" >/dev/null 2>&1; then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
curl -fsS "${health_url}" >/dev/null

v4l2_info="$(v4l2-ctl --all --device="${RECEIVER_DEVICE}" 2>/dev/null)"
grep -q 'Video Capture' <<<"${v4l2_info}" || {
  echo "${RECEIVER_DEVICE} is not advertising V4L2 capture capability in standby" >&2
  exit 1
}
v4l2-ctl --list-formats-ext --device="${RECEIVER_DEVICE}" >/dev/null

timeout "${DEMAND_STREAM_TIMEOUT_SECONDS}s" curl -fsSN "${demand_url}" >"${DEMAND_EVENTS}" 2>"${ARTIFACT_DIR}/emulator-demand-events.err" &
DEMAND_PID=$!

resolve_emulator_serial() {
  local expected_serial="emulator-${EMULATOR_PORT}"
  local serial
  local state
  while IFS=$'\t ' read -r serial state _; do
    if [[ "${serial}" == "${expected_serial}" && "${state}" == "device" ]]; then
      EMULATOR_SERIAL="${serial}"
      return 0
    fi
  done < <("${ADB}" devices -l | awk 'NR > 1 && $1 != "" { print $1 "\t" $2 }')
  return 1
}

"${EMULATOR}" \
  -avd "${AVD_NAME}" \
  -port "${EMULATOR_PORT}" \
  -camera-back "videofile:${SAMPLE_VIDEO}" \
  -no-window -no-audio -no-boot-anim >"${EMULATOR_CONSOLE_LOG}" 2>&1 &
EMULATOR_PID=$!

for _ in $(seq 1 "${ADB_DEVICE_WAIT_SECONDS}"); do
  if resolve_emulator_serial; then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
[[ -n "${EMULATOR_SERIAL}" ]] || {
  echo "AVD ${AVD_NAME} did not expose the expected emulator serial" >&2
  cat "${EMULATOR_CONSOLE_LOG}" >&2
  exit 1
}

if ! timeout "${ADB_DEVICE_WAIT_SECONDS}s" "${ADB}" -s "${EMULATOR_SERIAL}" wait-for-device; then
  echo "Resolved emulator ${EMULATOR_SERIAL} did not become ready" >&2
  cat "${EMULATOR_CONSOLE_LOG}" >&2
  exit 1
fi
for _ in $(seq 1 "${EMULATOR_BOOT_WAIT_SECONDS}"); do
  if [[ "$(adb_target shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
[[ "$(adb_target shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
  echo "Android emulator ${EMULATOR_SERIAL} did not finish booting" >&2
  exit 1
}

"${ROOT_DIR}/android/gradlew" -p "${ROOT_DIR}/android" assembleDebug
adb_target install -r "${APK_PATH}" >/dev/null
adb_target shell am force-stop "${PACKAGE_NAME}"
adb_target shell pm clear "${PACKAGE_NAME}" >/dev/null
adb_target shell pm grant "${PACKAGE_NAME}" "${CAMERA_PERMISSION}"
adb_target logcat -c
adb_target shell am start -n "${PACKAGE_NAME}/${ACTIVITY_NAME}" \
  --es dev.mobilewebcam.sender.receiverHost 10.0.2.2 \
  --ei dev.mobilewebcam.sender.receiverControlPort "${RECEIVER_CONTROL_PORT}" \
  --es dev.mobilewebcam.sender.receiverName "Emulator receiver" >/dev/null

wait_for_android_log_event() {
  local event_name="$1"
  for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
    adb_target logcat -d >"${ANDROID_LOG}"
    if rg -q "\"event\":\"${event_name}\"" "${ANDROID_LOG}"; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "Android did not report event ${event_name}" >&2
  rg -n 'MobileWebcam|AndroidRuntime|FATAL EXCEPTION' "${ANDROID_LOG}" >&2 || true
  exit 1
}

wait_for_android_log_event_count() {
  local event_name="$1"
  local expected_count="$2"
  local event_count
  for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
    adb_target logcat -d >"${ANDROID_LOG}"
    event_count="$(rg -c "\"event\":\"${event_name}\"" "${ANDROID_LOG}" || true)"
    if (( ${event_count:-0} >= expected_count )); then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "Android reported fewer than ${expected_count} ${event_name} events" >&2
  exit 1
}

wait_for_demand_event() {
  local generation="$1"
  local demand="$2"
  for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
    if rg -q "\"generation\":${generation},\"demand\":\"${demand}\"" "${DEMAND_EVENTS}"; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "Demand event ${demand} generation ${generation} was not observed" >&2
  sed -n '1,160p' "${DEMAND_EVENTS}" >&2 || true
  exit 1
}

latest_active_generation() {
  local event
  event="$(rg '"demand":"active"' "${DEMAND_EVENTS}" | tail -1 || true)"
  [[ -n "${event}" ]] || return 1
  jq -r '.generation' <<<"${event#data: }"
}

wait_for_new_active_generation() {
  local previous_generation="$1"
  local candidate
  for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
    candidate="$(latest_active_generation || true)"
    if [[ "${candidate}" =~ ^[0-9]+$ ]] && (( candidate > previous_generation )); then
      printf '%s\n' "${candidate}"
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "A new active demand generation was not observed after ${previous_generation}" >&2
  exit 1
}

assert_receiver_inactive() {
  local capabilities
  capabilities="$(curl -fsS "${capabilities_url}")"
  [[ "$(jq -r '.active' <<<"${capabilities}")" == false ]] || {
    echo "Receiver unexpectedly owns an active media session" >&2
    return 1
  }
}

wait_for_receiving() {
  local previous_session_id="$1"
  local candidate_session_id
  local status_json
  for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
    candidate_session_id="$(rg -oE 'session_id[^0-9a-f]+[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "${RECEIVER_LOG}" | tail -1 | rg -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' || true)"
    if [[ -n "${candidate_session_id}" && "${candidate_session_id}" != "${previous_session_id}" ]]; then
      status_json="$(curl -fsS "http://127.0.0.1:${RECEIVER_CONTROL_PORT}/v2/sessions/${candidate_session_id}" || true)"
      if [[ -n "${status_json}" ]] && [[ "$(jq -r '.state' <<<"${status_json}")" == receiving ]] && \
        (( $(jq -r '.metrics.decodedFrames // 0' <<<"${status_json}") >= MIN_DECODED_FRAMES )); then
        if [[ -z "${previous_session_id}" ]]; then
          FIRST_SESSION_ID="${candidate_session_id}"
        else
          SECOND_SESSION_ID="${candidate_session_id}"
        fi
        return 0
      fi
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "Android emulator stream did not reach receiving state" >&2
  cat "${RECEIVER_LOG}" >&2
  exit 1
}

start_v4l2_capture() {
  local output_file="$1"
  local log_file="$2"
  local frame_count="$3"
  ffmpeg -hide_banner -loglevel error \
    -f v4l2 \
    -video_size "${VIDEO_WIDTH}x${VIDEO_HEIGHT}" \
    -framerate "${VIDEO_FPS}" \
    -i "${RECEIVER_DEVICE}" \
    -frames:v "${frame_count}" \
    -f framemd5 "${output_file}" >"${log_file}" 2>&1 &
  FIRST_CONSUMER_PID=$!
}

assert_distinct_frame_hashes() {
  local hash_file="$1"
  local distinct_hash_count
  distinct_hash_count="$(rg -o '[0-9a-f]{32}$' "${hash_file}" | sort -u | wc -l | tr -d ' ')"
  (( distinct_hash_count >= MIN_DISTINCT_FRAME_HASHES )) || {
    echo "Expected at least ${MIN_DISTINCT_FRAME_HASHES} distinct V4L2 frame hashes in ${hash_file}" >&2
    cat "${hash_file}" >&2 || true
    return 1
  }
}

assert_android_log_sane() {
  if grep -Eiq 'passphrase|streamid' "${ANDROID_LOG}"; then
    echo "Android logs contain an unredacted SRT credential" >&2
    return 1
  fi
}

assert_black_standby_frame() {
  local frame_size
  local first_byte
  local second_byte
  local third_byte
  local fourth_byte
  frame_size="$(stat -c '%s' "${STANDBY_FRAME}")"
  [[ "${frame_size}" == "${FRAME_BYTES}" ]] || {
    echo "Standby frame size ${frame_size} does not match ${FRAME_BYTES}" >&2
    return 1
  }
  read -r first_byte second_byte third_byte fourth_byte < <(od -An -v -tu1 -N 4 "${STANDBY_FRAME}")
  [[ "${first_byte}" == 16 && "${second_byte}" == 128 &&
    "${third_byte}" == 16 && "${fourth_byte}" == 128 ]] || {
    echo "Standby frame does not begin with limited-range black YUY2" >&2
    return 1
  }
}

wait_for_android_log_event connected_standby
assert_receiver_inactive
wait_for_demand_event "${EXPECTED_INACTIVE_GENERATION}" inactive
adb_target logcat -d >"${ANDROID_LOG}"
if rg -q '"event":"stream_start_requested"' "${ANDROID_LOG}"; then
  echo "Android opened media before the first V4L2 consumer" >&2
  exit 1
fi

start_v4l2_capture "${FIRST_FRAME_HASHES}" "${FIRST_CAPTURE_LOG}" "${CONSUMER_FRAME_COUNT}"
wait_for_demand_event "${EXPECTED_FIRST_GENERATION}" active
wait_for_receiving ""

if ! kill -0 "${FIRST_CONSUMER_PID}" 2>/dev/null; then
  echo "The first generic V4L2 consumer exited before a second consumer could overlap" >&2
  cat "${FIRST_CAPTURE_LOG}" >&2 || true
  exit 1
fi
ffmpeg -hide_banner -loglevel error \
  -f v4l2 \
  -video_size "${VIDEO_WIDTH}x${VIDEO_HEIGHT}" \
  -framerate "${VIDEO_FPS}" \
  -i "${RECEIVER_DEVICE}" \
  -frames:v "${SECOND_CONSUMER_FRAME_COUNT}" \
  -f null - >"${ARTIFACT_DIR}/emulator-v4l2-second-consumer.log" 2>&1 &
SECOND_CONSUMER_PID=$!
wait "${SECOND_CONSUMER_PID}"
SECOND_CONSUMER_PID=""
wait "${FIRST_CONSUMER_PID}"
FIRST_CONSUMER_PID=""
assert_distinct_frame_hashes "${FIRST_FRAME_HASHES}"

wait_for_demand_event "${EXPECTED_FIRST_GENERATION}" inactive
for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
  if assert_receiver_inactive; then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
assert_receiver_inactive
wait_for_android_log_event_count stream_stopped 1
adb_target logcat -d >"${ANDROID_LOG}"
first_start_count="$(rg -c '"event":"stream_start_requested"' "${ANDROID_LOG}" || true)"
[[ "${first_start_count:-0}" == 1 ]] || {
  echo "Expected exactly one Android media start for generation ${EXPECTED_FIRST_GENERATION}, got ${first_start_count:-0}" >&2
  exit 1
}

if ! v4l2-ctl --device="${RECEIVER_DEVICE}" --stream-mmap \
  --stream-count="${STANDBY_FRAME_COUNT}" --stream-to="${STANDBY_FRAME}" \
  >"${STANDBY_CAPTURE_LOG}" 2>&1; then
  cat "${STANDBY_CAPTURE_LOG}" >&2
  exit 1
fi
assert_black_standby_frame

standby_generation="$(latest_active_generation || true)"
if [[ "${standby_generation}" =~ ^[0-9]+$ ]] && (( standby_generation > EXPECTED_FIRST_GENERATION )); then
  wait_for_demand_event "${standby_generation}" inactive
fi

start_v4l2_capture "${SECOND_FRAME_HASHES}" "${SECOND_CAPTURE_LOG}" "${CONSUMER_FRAME_COUNT}"
SECOND_CONSUMER_PID="${FIRST_CONSUMER_PID}"
FIRST_CONSUMER_PID=""
reopen_generation="$(wait_for_new_active_generation "${EXPECTED_FIRST_GENERATION}")"
wait_for_receiving "${FIRST_SESSION_ID}"
wait "${SECOND_CONSUMER_PID}"
SECOND_CONSUMER_PID=""
assert_distinct_frame_hashes "${SECOND_FRAME_HASHES}"
wait_for_demand_event "${reopen_generation}" inactive
assert_android_log_sane
adb_target logcat -d >"${ANDROID_LOG}"
wait_for_android_log_event_count stream_stopped 2
second_start_count="$(rg -c '"event":"stream_start_requested"' "${ANDROID_LOG}" || true)"
[[ "${second_start_count:-0}" == 2 ]] || {
  echo "Expected one new Android media start after reopen, got ${second_start_count:-0}" >&2
  exit 1
}

echo "APK sha256: $(sha256sum "${APK_PATH}")"
echo "Resolved emulator serial: ${EMULATOR_SERIAL} (AVD ${AVD_NAME})"
echo "Demand-driven emulator gate passed: sessions ${FIRST_SESSION_ID} then ${SECOND_SESSION_ID}, generations ${EXPECTED_FIRST_GENERATION} then ${reopen_generation}, receiver output ${RECEIVER_DEVICE}, black standby and distinct live frame hashes verified."
