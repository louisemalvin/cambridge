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
ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-${EMULATOR_PORT}}"
RECEIVER_CONTROL_PORT="${RECEIVER_CONTROL_PORT:-55031}"
RECEIVER_SRT_PORT="${RECEIVER_SRT_PORT:-55030}"
RECEIVER_DEVICE="${RECEIVER_DEVICE:-/dev/video10}"
RECEIVER_BINARY="${RECEIVER_BINARY:-}"
APK_PATH="${APK_PATH:-${ROOT_DIR}/android/app/build/outputs/apk/debug/app-debug.apk}"
REQUIRE_V4L2_CAPTURE="${REQUIRE_V4L2_CAPTURE:-0}"

VIDEO_WIDTH=1280
VIDEO_HEIGHT=720
VIDEO_FPS=30
VIDEO_DURATION_SECONDS=20
RECEIVER_WAIT_SECONDS=30
EMULATOR_BOOT_WAIT_SECONDS=120
ADB_DEVICE_WAIT_SECONDS=30
POLL_INTERVAL_SECONDS=2
CAMERA_PERMISSION="android.permission.CAMERA"
PACKAGE_NAME="dev.mobilewebcam.sender"
ACTIVITY_NAME="dev.mobilewebcam.sender.app.MainActivity"
ARTIFACT_DIR="${ROOT_DIR}/.artifacts/reliable-streaming-v2"
SAMPLE_VIDEO="${ARTIFACT_DIR}/emulator-moving-sample.mp4"
RECEIVER_LOG="${ARTIFACT_DIR}/emulator-receiver.log"
ANDROID_LOG="${ARTIFACT_DIR}/emulator-android.log"
V4L2_CAPTURE="${ARTIFACT_DIR}/emulator-v4l2.raw"

RECEIVER_PID=""
EMULATOR_PID=""

cleanup() {
  if [[ -n "${EMULATOR_PID}" && -x "${ADB}" ]]; then
    "${ADB}" -s "${ANDROID_SERIAL}" logcat -d >"${ANDROID_LOG}" 2>/dev/null || true
    "${ADB}" -s "${ANDROID_SERIAL}" emu kill >/dev/null 2>&1 || true
    wait "${EMULATOR_PID}" 2>/dev/null || true
  fi
  if [[ -n "${RECEIVER_PID}" ]]; then
    kill -INT "${RECEIVER_PID}" 2>/dev/null || true
    wait "${RECEIVER_PID}" 2>/dev/null || true
  fi
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
require_command sha256sum
require_command jq
require_command gst-launch-1.0
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
for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
  if curl -fsS "${health_url}" >/dev/null 2>&1; then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
curl -fsS "${health_url}" >/dev/null

"${EMULATOR}" \
  -avd "${AVD_NAME}" \
  -port "${EMULATOR_PORT}" \
  -camera-back "videofile:${SAMPLE_VIDEO}" \
  -no-window -no-audio -no-boot-anim >"${ARTIFACT_DIR}/emulator-console.log" 2>&1 &
EMULATOR_PID=$!

if ! timeout "${ADB_DEVICE_WAIT_SECONDS}s" "${ADB}" -s "${ANDROID_SERIAL}" wait-for-device; then
  echo "Android emulator did not expose an adb device" >&2
  cat "${ARTIFACT_DIR}/emulator-console.log" >&2
  exit 1
fi
for _ in $(seq 1 "${EMULATOR_BOOT_WAIT_SECONDS}"); do
  if [[ "$("${ADB}" -s "${ANDROID_SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
[[ "$("${ADB}" -s "${ANDROID_SERIAL}" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
  echo "Android emulator did not finish booting" >&2
  exit 1
}

if [[ ! -f "${APK_PATH}" ]]; then
  "${ROOT_DIR}/android/gradlew" -p "${ROOT_DIR}/android" assembleDebug
fi
"${ADB}" -s "${ANDROID_SERIAL}" install -r "${APK_PATH}" >/dev/null
"${ADB}" -s "${ANDROID_SERIAL}" shell pm grant "${PACKAGE_NAME}" "${CAMERA_PERMISSION}" || true
"${ADB}" -s "${ANDROID_SERIAL}" logcat -c
"${ADB}" -s "${ANDROID_SERIAL}" shell am start -n "${PACKAGE_NAME}/${ACTIVITY_NAME}" \
  --es dev.mobilewebcam.sender.receiverHost 10.0.2.2 \
  --ei dev.mobilewebcam.sender.receiverControlPort "${RECEIVER_CONTROL_PORT}" \
  --es dev.mobilewebcam.sender.receiverName "Emulator receiver" >/dev/null

session_id=""
for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
  session_id="$(grep -oE 'session_id[=:][0-9a-f-]{36}' "${RECEIVER_LOG}" 2>/dev/null | tail -1 | sed 's/.*[=:]//' || true)"
  [[ -n "${session_id}" ]] && break
  sleep "${POLL_INTERVAL_SECONDS}"
done
[[ -n "${session_id}" ]] || {
  echo "Android did not create a receiver session" >&2
  "${ADB}" -s "${ANDROID_SERIAL}" logcat -d >"${ANDROID_LOG}" 2>/dev/null || true
  rg -n 'MobileWebcam|AndroidRuntime|FATAL EXCEPTION' "${ANDROID_LOG}" >&2 || true
  cat "${RECEIVER_LOG}" >&2
  exit 1
}

for _ in $(seq 1 "${RECEIVER_WAIT_SECONDS}"); do
  session_json="$(curl -fsS "http://127.0.0.1:${RECEIVER_CONTROL_PORT}/v2/sessions/${session_id}")"
  if [[ "$(jq -r '.state' <<<"${session_json}")" == receiving ]] && \
    (( $(jq -r '.metrics.decodedFrames // 0' <<<"${session_json}") > 0 )); then
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done
[[ "$(jq -r '.state' <<<"${session_json}")" == receiving ]] || {
  echo "Android emulator stream did not reach receiving state" >&2
  exit 1
}

"${ADB}" -s "${ANDROID_SERIAL}" logcat -d >"${ANDROID_LOG}"
if grep -Eiq 'passphrase|streamid' "${ANDROID_LOG}"; then
  echo "Android logs contain an unredacted SRT credential" >&2
  exit 1
fi

if timeout 10s gst-launch-1.0 -q \
    v4l2src device="${RECEIVER_DEVICE}" num-buffers=30 ! \
    videoconvert ! fakesink sync=false >"${V4L2_CAPTURE}" 2>&1; then
  :
elif [[ "${REQUIRE_V4L2_CAPTURE}" == 1 ]]; then
  echo "The virtual camera could not be captured for the OBS gate" >&2
  exit 1
else
  echo "Virtual-camera capture skipped because no consumer is open; use REQUIRE_V4L2_CAPTURE=1 for OBS evidence."
fi

echo "APK sha256: $(sha256sum "${APK_PATH}")"
echo "Android emulator SRT integration passed: session ${session_id}, decoded frames $(jq -r '.metrics.decodedFrames' <<<"${session_json}")."
