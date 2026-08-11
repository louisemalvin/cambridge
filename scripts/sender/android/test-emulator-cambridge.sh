#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
android_root="${repo_root}/sender/android"
plugin_build_dir="${repo_root}/build/cambridge-obs-plugin"
artifact_dir=$(mktemp -d "${repo_root}/build/cambridge-emulator.XXXXXX")

avd_name="${CAMBRIDGE_AVD_NAME:-}"
emulator_port="${CAMBRIDGE_EMULATOR_PORT:-5556}"
emulator_serial="${CAMBRIDGE_EMULATOR_SERIAL:-emulator-${emulator_port}}"
receiver_host="10.0.2.2"
camera_duration_seconds=10
test_card_font_size=32
test_card_margin=32
test_card_flash_width=96
test_card_flash_height=96
test_card_patch_size=96
test_card_patch_gap=16
poll_interval_seconds=1
obs_wait_seconds=30
adb_wait_seconds=90
boot_wait_seconds=120
stream_wait_seconds="${CAMBRIDGE_HOLD_SECONDS:-20}"
lifecycle_cycles="${CAMBRIDGE_LIFECYCLE_CYCLES:-1}"
app_event_wait_seconds=30
obs_shutdown_wait_seconds=5
ui_scroll_attempts=4
ui_scroll_start_x=540
ui_scroll_start_y=1600
ui_scroll_end_x=540
ui_scroll_end_y=500
ui_scroll_duration_millis=300

sdk_root="${ANDROID_SDK_ROOT:-${HOME}/Android/Sdk}"
adb="${sdk_root}/platform-tools/adb"
emulator="${sdk_root}/emulator/emulator"
apk="${android_root}/app/build/outputs/apk/debug/app-debug.apk"
scene_template="${repo_root}/scripts/receiver/common/cambridge-test-scene.json"
contract_json="${repo_root}/protocol/cambridge-stream-contract.json"
camera_video="${artifact_dir}/camera.mp4"
obs_config="${artifact_dir}/obs-config"
obs_log="${artifact_dir}/obs.log"
emulator_log="${artifact_dir}/emulator.log"
app_log="${artifact_dir}/android.log"
ui_dump_remote="/sdcard/cambridge-ui.xml"

obs_pid=""
emulator_pid=""

fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

cleanup() {
    if [[ -x "${adb}" ]]; then
        "${adb}" -s "${emulator_serial}" emu kill >/dev/null 2>&1 || true
    fi
    if [[ -n "${emulator_pid}" ]]; then
        kill -TERM "${emulator_pid}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${obs_pid}" ]]; then
        kill -TERM "${obs_pid}" >/dev/null 2>&1 || true
    fi
    sleep 2
    if [[ -n "${emulator_pid}" ]]; then
        kill -KILL "${emulator_pid}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${obs_pid}" ]]; then
        kill -KILL "${obs_pid}" >/dev/null 2>&1 || true
    fi
}

trap cleanup EXIT

[[ -x "${adb}" ]] || fail "Android adb not found at ${adb}"
[[ -x "${emulator}" ]] || fail "Android emulator not found at ${emulator}"
[[ "${emulator_port}" =~ ^[0-9]+$ ]] || fail "emulator port must be numeric"
if [[ -z "${avd_name}" ]]; then
    mapfile -t available_avds < <("${emulator}" -list-avds)
    if ((${#available_avds[@]} != 1)); then
        fail "set CAMBRIDGE_AVD_NAME when zero or multiple Android AVDs are available"
    fi
    avd_name="${available_avds[0]}"
fi
[[ -f "${scene_template}" ]] || fail "OBS scene template is missing: ${scene_template}"
[[ -f "${contract_json}" ]] || fail "CamBridge stream contract is missing: ${contract_json}"
command -v ffmpeg >/dev/null 2>&1 || fail "ffmpeg is required for the deterministic emulator camera input"
command -v obs >/dev/null 2>&1 || fail "OBS is required for the native source smoke"
command -v jq >/dev/null 2>&1 || fail "jq is required to read the CamBridge stream contract"

protocol_version=$(jq -er '.protocolVersion' "${contract_json}")
receiver_control_port=$(jq -er '.defaults.controlPort' "${contract_json}")
media_port_offset=$(jq -er '.defaults.mediaPortOffset' "${contract_json}")
receiver_media_port=$((receiver_control_port + media_port_offset))
profile_id="${CAMBRIDGE_PROFILE_ID:-fixture-720p30}"
profile_width="${CAMBRIDGE_WIDTH:-1280}"
profile_height="${CAMBRIDGE_HEIGHT:-720}"
profile_fps="${CAMBRIDGE_FPS:-30}"
camera_bitrate_bps="${CAMBRIDGE_BITRATE_BPS:-4000000}"
rotation_degrees="${CAMBRIDGE_ROTATION_DEGREES:-0}"
keyframe_interval_seconds=$(jq -er '.media.keyframeIntervalSeconds' "${contract_json}")
camera_duration_seconds="${CAMBRIDGE_CAMERA_ASSET_SECONDS:-${camera_duration_seconds}}"
[[ "${camera_duration_seconds}" =~ ^[1-9][0-9]*$ ]] || fail "camera asset duration must be a positive integer"
[[ "${stream_wait_seconds}" =~ ^[1-9][0-9]*$ ]] || fail "stream hold duration must be a positive integer"
[[ "${lifecycle_cycles}" =~ ^[1-9][0-9]*$ ]] || fail "lifecycle cycles must be a positive integer"
[[ "${rotation_degrees}" =~ ^(0|90|180|270)$ ]] || fail "rotation must be 0, 90, 180, or 270 degrees"
"${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin.sh" >"${artifact_dir}/plugin-build.log"
JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}" "${android_root}/gradlew" -p "${android_root}" assembleDebug --console=plain >"${artifact_dir}/android-build.log"

[[ -f "${apk}" ]] || fail "debug APK was not produced: ${apk}"
plugin_so="${plugin_build_dir}/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
[[ -f "${plugin_so}" ]] || fail "staged OBS plugin was not produced: ${plugin_so}"

test_card_filter="drawtext=fontcolor=white:fontsize=${test_card_font_size}:box=1:boxcolor=black@0.70:boxborderw=8:text='CamBridge ${profile_id} ${profile_width}x${profile_height} frame %{n} pts %{pts\\:hms}':x=${test_card_margin}:y=${test_card_margin},drawbox=x=iw-${test_card_flash_width}-${test_card_margin}:y=${test_card_margin}:w=${test_card_flash_width}:h=${test_card_flash_height}:color=white@1.0:t=fill:enable='lt(mod(n\\,${profile_fps})\\,2)'"
test_card_patch_y=$((test_card_margin + test_card_font_size + test_card_patch_gap))
test_card_filter="${test_card_filter},drawbox=x=${test_card_margin}:y=${test_card_patch_y}:w=${test_card_patch_size}:h=${test_card_patch_size}:color=red@1.0:t=fill"
test_card_filter="${test_card_filter},drawbox=x=$((test_card_margin + test_card_patch_size + test_card_patch_gap)):y=${test_card_patch_y}:w=${test_card_patch_size}:h=${test_card_patch_size}:color=green@1.0:t=fill"
test_card_filter="${test_card_filter},drawbox=x=$((test_card_margin + 2 * (test_card_patch_size + test_card_patch_gap))):y=${test_card_patch_y}:w=${test_card_patch_size}:h=${test_card_patch_size}:color=blue@1.0:t=fill"
test_card_filter="${test_card_filter},drawtext=fontcolor=black:fontsize=${test_card_font_size}:text='RED':x=$((test_card_margin + 12)):y=$((test_card_patch_y + 30))"
test_card_filter="${test_card_filter},drawtext=fontcolor=black:fontsize=${test_card_font_size}:text='GREEN':x=$((test_card_margin + test_card_patch_size + test_card_patch_gap + 2)):y=$((test_card_patch_y + 30))"
test_card_filter="${test_card_filter},drawtext=fontcolor=white:fontsize=${test_card_font_size}:text='BLUE':x=$((test_card_margin + 2 * (test_card_patch_size + test_card_patch_gap) + 2)):y=$((test_card_patch_y + 30))"
ffmpeg -hide_banner -loglevel error \
    -f lavfi -i "testsrc2=size=${profile_width}x${profile_height}:rate=${profile_fps}" \
    -t "${camera_duration_seconds}" \
    -vf "${test_card_filter}" \
    -c:v libx264 -pix_fmt yuv420p -preset ultrafast -tune zerolatency \
    -b:v "${camera_bitrate_bps}" -g "$((profile_fps * keyframe_interval_seconds))" \
    -movflags +faststart -f mp4 "${camera_video}"

mkdir -p "${obs_config}/obs-studio/basic/scenes" \
    "${obs_config}/obs-studio/plugins/cambridge-obs-plugin/bin/64bit"
cp "${scene_template}" "${obs_config}/obs-studio/basic/scenes/Untitled.json"
cp "${plugin_so}" \
    "${obs_config}/obs-studio/plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"

obs_args=(
    --multi
    --verbose
    --disable-missing-files-check
    --profile Untitled
    --scene Untitled
)

start_obs() {
    XDG_CONFIG_HOME="${obs_config}" obs "${obs_args[@]}" >>"${obs_log}" 2>&1 &
    obs_pid=$!
    for ((attempt = 0; attempt < obs_wait_seconds; attempt += poll_interval_seconds)); do
        rg -q 'loaded module=cambridge-obs-plugin' "${obs_log}" && \
            rg -q 'listening:control=' "${obs_log}" && return
        sleep "${poll_interval_seconds}"
    done
    fail "OBS did not load the CamBridge source"
}

stop_obs() {
    if [[ -z "${obs_pid}" ]]; then
        return
    fi
    kill -TERM "${obs_pid}" >/dev/null 2>&1 || true
    for ((attempt = 0; attempt < obs_shutdown_wait_seconds; attempt += poll_interval_seconds)); do
        kill -0 "${obs_pid}" >/dev/null 2>&1 || break
        sleep "${poll_interval_seconds}"
    done
    if kill -0 "${obs_pid}" >/dev/null 2>&1; then
        kill -KILL "${obs_pid}" >/dev/null 2>&1 || true
    fi
    wait "${obs_pid}" >/dev/null 2>&1 || true
    obs_pid=""
}

: >"${obs_log}"
start_obs

"${emulator}" -avd "${avd_name}" -no-window -no-audio -gpu swiftshader_indirect \
    -camera-back "videofile:${camera_video}" -port "${emulator_port}" >"${emulator_log}" 2>&1 &
emulator_pid=$!
for ((attempt = 0; attempt < adb_wait_seconds; attempt += poll_interval_seconds)); do
    "${adb}" -s "${emulator_serial}" get-state >/dev/null 2>&1 && break
    sleep "${poll_interval_seconds}"
done
"${adb}" -s "${emulator_serial}" wait-for-device >/dev/null
for ((attempt = 0; attempt < boot_wait_seconds; attempt += poll_interval_seconds)); do
    boot_completed=$("${adb}" -s "${emulator_serial}" shell getprop sys.boot_completed | tr -d '\r')
    [[ "${boot_completed}" == "1" ]] && break
    sleep "${poll_interval_seconds}"
done
[[ "${boot_completed:-}" == "1" ]] || fail "AVD ${avd_name} did not finish booting"

"${adb}" -s "${emulator_serial}" install -r "${apk}" >"${artifact_dir}/install.log"
"${adb}" -s "${emulator_serial}" shell pm grant dev.cambridge.sender android.permission.CAMERA
"${adb}" -s "${emulator_serial}" shell am force-stop dev.cambridge.sender
"${adb}" -s "${emulator_serial}" logcat -c
"${adb}" -s "${emulator_serial}" shell am start \
    -n dev.cambridge.sender/.app.MainActivity \
    --es dev.cambridge.sender.receiverHost "${receiver_host}" \
    --ei dev.cambridge.sender.receiverControlPort "${receiver_control_port}" \
    --es dev.cambridge.sender.receiverName Local-OBS \
    --es dev.cambridge.sender.profileId "${profile_id}" \
    --ei dev.cambridge.sender.rotationDegrees "${rotation_degrees}" >"${artifact_dir}/activity.log"

sleep 10
app_pid=$("${adb}" -s "${emulator_serial}" shell pidof dev.cambridge.sender | tr -d '\r' | awk '{print $1}')
[[ -n "${app_pid}" ]] || fail "sender application did not remain running"

refresh_app_log() {
    "${adb}" -s "${emulator_serial}" logcat -d --pid "${app_pid}" -v threadtime >"${app_log}"
}

wait_for_event_count() {
    local event_name="$1"
    local expected_count="$2"
    for ((attempt = 0; attempt < app_event_wait_seconds; attempt += poll_interval_seconds)); do
        refresh_app_log
        local event_count
        event_count=$(rg -c "\\\"event\\\":\\\"${event_name}\\\"" "${app_log}" || true)
        if [[ "${event_count:-0}" -ge "${expected_count}" ]]; then
            return 0
        fi
        if rg -q '\"event\":\"stream_start_failed\"' "${app_log}" &&
            rg -q '\"failureType\":\"NoCompatibleCodec\"' "${app_log}"; then
            printf 'environment_limit=AVD MediaCodec rejected profile=%s\n' "${profile_id}" >&2
            printf 'artifacts=%s\n' "${artifact_dir}" >&2
            exit 2
        fi
        sleep "${poll_interval_seconds}"
    done
    fail "Android did not report event ${event_name} count ${expected_count}"
}

click_stream_action() {
    local content_description="$1"
    local ui_dump="${artifact_dir}/ui.xml"
    local node=""
    local bounds=""
    local left=""
    local top=""
    local right=""
    local bottom=""
    local scroll_attempt
    for ((scroll_attempt = 0; scroll_attempt < ui_scroll_attempts; scroll_attempt += 1)); do
        "${adb}" -s "${emulator_serial}" shell uiautomator dump "${ui_dump_remote}" >/dev/null 2>&1
        "${adb}" -s "${emulator_serial}" exec-out cat "${ui_dump_remote}" >"${ui_dump}"
        node=$(rg -o "<node[^>]*content-desc=\"${content_description}\"[^>]*>" "${ui_dump}" | head -n 1 || true)
        if [[ -n "${node}" ]]; then
            bounds=$(sed -n 's/.*bounds="\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]".*/\1 \2 \3 \4/p' <<<"${node}")
            read -r left top right bottom <<<"${bounds}"
            [[ -n "${left:-}" && -n "${top:-}" && -n "${right:-}" && -n "${bottom:-}" ]] \
                || fail "Android action has no usable bounds: ${content_description}"
            "${adb}" -s "${emulator_serial}" shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
            return
        fi
        "${adb}" -s "${emulator_serial}" shell input swipe \
            "${ui_scroll_start_x}" "${ui_scroll_start_y}" \
            "${ui_scroll_end_x}" "${ui_scroll_end_y}" \
            "${ui_scroll_duration_millis}"
        sleep "${poll_interval_seconds}"
    done
    fail "Android action is not present: ${content_description}"
}

wait_for_stream_setup() {
    local ui_dump="${artifact_dir}/ui-after-stop.xml"
    for ((attempt = 0; attempt < app_event_wait_seconds; attempt += poll_interval_seconds)); do
        "${adb}" -s "${emulator_serial}" shell uiautomator dump "${ui_dump_remote}" >/dev/null 2>&1
        "${adb}" -s "${emulator_serial}" exec-out cat "${ui_dump_remote}" >"${ui_dump}"
        if rg -q 'text="Resolution"' "${ui_dump}"; then
            return
        fi
        sleep "${poll_interval_seconds}"
    done
    fail "Android did not return to the stream setup controls"
}

stream_started_count=0
stream_released_count=0
for ((cycle = 1; cycle <= lifecycle_cycles; cycle += 1)); do
    click_stream_action "Start stream"
    stream_started_count=$((stream_started_count + 1))
    wait_for_event_count "stream_started" "${stream_started_count}"
    sleep "${stream_wait_seconds}"
    click_stream_action "Stop stream"
    click_stream_action "Confirm stop stream"
    stream_released_count=$((stream_released_count + 1))
    wait_for_event_count "stream_resources_released" "${stream_released_count}"
    wait_for_stream_setup
done
refresh_app_log

rg -q '"event":"stream_started"' "${app_log}" || fail "Android did not report stream_started"
rg -q '"event":"stream_resources_released"' "${app_log}" || fail "Android did not release stream resources"
rg -q '"mediaPort":'"${receiver_media_port}" "${app_log}" || fail "Android did not use the contract media port"
rg -q 'session_accepted:' "${obs_log}" || fail "OBS did not accept the CamBridge session"
rg -q 'decoder_ready:h264/(VAAPI|software)' "${obs_log}" || fail "native H.264 decoder did not become ready"
rg -q 'first_frame_published:mode=' "${obs_log}" || fail "native source did not publish a frame"
rg -q 'render_mode=(native|software)' "${obs_log}" || fail "OBS texture presentation mode was not reported"
if rg -q 'NetworkOnMainThreadException|failureType":"Unexpected|failureType":"CameraUnavailable|failureType":"CameraPermissionDenied' "${app_log}"; then
    fail "Android reported a stream runtime failure; see ${app_log}"
fi
printf 'avd=%s\n' "${avd_name}"
printf 'serial=%s\n' "${emulator_serial}"
printf 'profile=%s (%sx%s@%s)\n' "${profile_id}" "${profile_width}" "${profile_height}" "${profile_fps}"
printf 'rotation=%s\n' "${rotation_degrees}"
printf 'protocol=%s\n' "${protocol_version}"
printf 'control=%s:%s\n' "${receiver_host}" "${receiver_control_port}"
printf 'media_port=%s\n' "${receiver_media_port}"
printf 'hold_seconds=%s\n' "${stream_wait_seconds}"
printf 'lifecycle_cycles=%s\n' "${lifecycle_cycles}"
printf 'apk_sha256='; sha256sum "${apk}" | awk '{print $1}'
printf 'plugin_sha256='; sha256sum "${plugin_so}" | awk '{print $1}'
printf 'artifacts=%s\n' "${artifact_dir}"
