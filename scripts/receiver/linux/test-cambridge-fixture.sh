#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
build_dir="${repo_root}/build/cambridge-obs-plugin"
artifact_dir=$(mktemp -d "${repo_root}/build/cambridge-fixture.XXXXXX")
contract_json="${repo_root}/protocol/cambridge-stream-contract.json"
scene_template="${repo_root}/scripts/receiver/common/cambridge-test-scene.json"
profile_template="${repo_root}/scripts/receiver/common/cambridge-test-profile.ini"
fixture_script="${repo_root}/scripts/receiver/common/cambridge-fixture.py"
swift_fixture_package="${repo_root}/scripts/sender/ios/cambridge-swift-fixture"
swift_container_image="swift:6.0.3-jammy"

profile_id="${CAMBRIDGE_PROFILE_ID:-fixture-2k30}"
video_width="${CAMBRIDGE_WIDTH:-2560}"
video_height="${CAMBRIDGE_HEIGHT:-1440}"
video_fps="${CAMBRIDGE_FPS:-30}"
video_bitrate_bps="${CAMBRIDGE_BITRATE_BPS:-18000000}"
rotation_degrees="${CAMBRIDGE_ROTATION_DEGREES:-0}"
duration_seconds="${CAMBRIDGE_DURATION_SECONDS:-60}"
decoder_mode="${CAMBRIDGE_DECODER_MODE:-auto}"
capture_output="${CAMBRIDGE_CAPTURE_OUTPUT:-0}"
sender_mode="${CAMBRIDGE_SENDER_MODE:-python}"
discovery_expectation="${CAMBRIDGE_EXPECT_DISCOVERY:-optional}"
poll_interval_seconds=1
obs_wait_seconds=30
obs_shutdown_wait_seconds=5
obs_recording_settle_seconds=2
recording_sample_fps=1

obs_config="${artifact_dir}/obs-config"
obs_log="${artifact_dir}/obs.log"
metrics_log="${artifact_dir}/process-metrics.tsv"
fixture_summary="${artifact_dir}/fixture-summary.json"
scene_config="${artifact_dir}/scene.json"
recording_dir="${artifact_dir}/recording"
recording_hashes="${artifact_dir}/recording.framemd5"
recording_file=""

obs_pid=""
monitor_pid=""

fail() {
    printf 'error: %s\n' "$1" >&2
    printf 'artifacts=%s\n' "${artifact_dir}" >&2
    exit 1
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

cleanup() {
    if [[ -n "${monitor_pid}" ]]; then
        kill -TERM "${monitor_pid}" >/dev/null 2>&1 || true
        wait "${monitor_pid}" >/dev/null 2>&1 || true
    fi
    stop_obs
}

trap cleanup EXIT

[[ -f "${contract_json}" ]] || fail "CamBridge stream contract is missing: ${contract_json}"
[[ -f "${scene_template}" ]] || fail "OBS scene template is missing: ${scene_template}"
[[ -f "${profile_template}" ]] || fail "OBS profile template is missing: ${profile_template}"
[[ -f "${fixture_script}" ]] || fail "fixture script is missing: ${fixture_script}"
if [[ "${sender_mode}" == "swift" ]]; then
    [[ -f "${swift_fixture_package}/Package.swift" ]] || fail "Swift fixture package is missing"
fi
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v obs >/dev/null 2>&1 || fail "OBS is required"
ffmpeg_path=$(command -v ffmpeg) || fail "ffmpeg is required"
python_path=$(command -v python3) || fail "python3 is required"
case "${decoder_mode}" in
    auto|cpu|native_required) ;;
    *) fail "decoder mode must be auto, cpu, or native_required" ;;
esac
case "${capture_output}" in
    0|1) ;;
    *) fail "capture output must be 0 or 1" ;;
esac
case "${sender_mode}" in
    python|swift) ;;
    *) fail "sender mode must be python or swift" ;;
esac
case "${discovery_expectation}" in
    optional|required|unavailable) ;;
    *) fail "discovery expectation must be optional, required, or unavailable" ;;
esac
[[ "${duration_seconds}" =~ ^[1-9][0-9]*$ ]] || fail "duration must be a positive integer"
[[ "${rotation_degrees}" =~ ^(0|90|180|270)$ ]] || fail "rotation must be 0, 90, 180, or 270 degrees"

contract_control_port=$(jq -er '.defaults.controlPort' "${contract_json}")
media_port_offset=$(jq -er '.defaults.mediaPortOffset' "${contract_json}")
control_port="${CAMBRIDGE_CONTROL_PORT:-${contract_control_port}}"
media_port="${CAMBRIDGE_MEDIA_PORT:-$((control_port + media_port_offset))}"
profile_width="${video_width}"
profile_height="${video_height}"
profile_fps="${video_fps}"

"${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin.sh" >"${artifact_dir}/plugin-build.log"
plugin_so="${build_dir}/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
[[ -f "${plugin_so}" ]] || fail "staged OBS plugin is missing: ${plugin_so}"

jq --arg decoder_mode "${decoder_mode}" \
    --argjson control_port "${control_port}" \
    --argjson media_port "${media_port}" \
    '(.sources[] | select(.id == "cambridge_android_source").settings) |=
        (.decoder_mode = $decoder_mode | .control_port = $control_port | .media_port = $media_port)' \
    "${scene_template}" >"${scene_config}"
mkdir -p "${obs_config}/obs-studio/basic/scenes" \
    "${obs_config}/obs-studio/plugins/cambridge-obs-plugin/bin/64bit"
if [[ "${capture_output}" == "1" ]]; then
    mkdir -p "${recording_dir}" "${obs_config}/obs-studio/basic/profiles/Untitled"
    sed \
        -e "s|__CAMBRIDGE_RECORDING_DIR__|${recording_dir}|g" \
        -e "s|__CAMBRIDGE_OUTPUT_WIDTH__|${profile_width}|g" \
        -e "s|__CAMBRIDGE_OUTPUT_HEIGHT__|${profile_height}|g" \
        -e "s|__CAMBRIDGE_OUTPUT_FPS__|${profile_fps}|g" \
        "${profile_template}" \
        >"${obs_config}/obs-studio/basic/profiles/Untitled/basic.ini"
fi
cp "${scene_config}" "${obs_config}/obs-studio/basic/scenes/Untitled.json"
cp "${plugin_so}" "${obs_config}/obs-studio/plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"

obs_args=(
    --multi
    --verbose
    --disable-missing-files-check
    --profile Untitled
    --scene Untitled
)
if [[ "${capture_output}" == "1" ]]; then
    obs_args+=(--startrecording)
fi
XDG_CONFIG_HOME="${obs_config}" obs "${obs_args[@]}" >"${obs_log}" 2>&1 &
obs_pid=$!
for ((attempt = 0; attempt < obs_wait_seconds; attempt += poll_interval_seconds)); do
    rg -q 'listening:control=' "${obs_log}" && break
    sleep "${poll_interval_seconds}"
done
rg -q 'loaded module=cambridge-obs-plugin' "${obs_log}" || fail "OBS did not load the CamBridge OBS plugin"
rg -q 'listening:control=' "${obs_log}" || fail "OBS source did not begin listening"
case "${discovery_expectation}" in
    required)
        rg -q 'discovery:service_type=' "${obs_log}" \
            || fail "receiver discovery registration was not reported"
        ;;
    unavailable)
        rg -q 'discovery_unavailable:' "${obs_log}" \
            || fail "receiver did not report degraded discovery"
        ;;
    optional)
        ;;
esac

printf 'timestamp_seconds\tresident_kib\tthreads\tfile_descriptors\tcpu_percent\n' >"${metrics_log}"
(
    while kill -0 "${obs_pid}" >/dev/null 2>&1; do
        timestamp=$(date +%s)
        resident_kib=$(awk '/VmRSS:/ {print $2}' "/proc/${obs_pid}/status" 2>/dev/null || printf '0')
        threads=$(awk '/Threads:/ {print $2}' "/proc/${obs_pid}/status" 2>/dev/null || printf '0')
        file_descriptors=$(find "/proc/${obs_pid}/fd" -mindepth 1 -maxdepth 1 -type l 2>/dev/null | wc -l)
        cpu_percent=$(ps -p "${obs_pid}" -o %cpu= | awk '{$1=$1; print}')
        printf '%s\t%s\t%s\t%s\t%s\n' "${timestamp}" "${resident_kib}" "${threads}" "${file_descriptors}" "${cpu_percent:-0}" >>"${metrics_log}"
        sleep "${poll_interval_seconds}"
    done
) &
monitor_pid=$!

fixture_args=(
    "${fixture_script}"
    --contract "${contract_json}"
    --profile-id "${profile_id}"
    --width "${profile_width}"
    --height "${profile_height}"
    --fps "${profile_fps}"
    --bitrate-bps "${video_bitrate_bps}"
    --host 127.0.0.1
    --control-port "${control_port}"
    --media-port "${media_port}"
    --duration "${duration_seconds}"
    --rotation-degrees "${rotation_degrees}"
    --output "${fixture_summary}"
    --ffmpeg "${ffmpeg_path}"
)
if [[ "${capture_output}" == "1" ]]; then
    fixture_args+=(--startup-delay "${obs_recording_settle_seconds}")
fi

if [[ "${sender_mode}" == "python" ]]; then
    "${python_path}" "${fixture_args[@]}"
else
    swift_access_unit="${artifact_dir}/swift-fixture.h264"
    "${ffmpeg_path}" -hide_banner -loglevel error -nostdin \
        -f lavfi -i "testsrc2=size=${profile_width}x${profile_height}:rate=${profile_fps}" \
        -frames:v 1 -an -c:v libx264 -preset ultrafast -tune zerolatency \
        -x264-params repeat-headers=1 -f h264 "${swift_access_unit}"
    [[ -s "${swift_access_unit}" ]] || fail "FFmpeg did not produce a Swift Annex-B fixture"
    swift_access_unit_argument="${swift_access_unit}"
    if ! command -v swift >/dev/null 2>&1; then
        swift_access_unit_argument="/workspace/${swift_access_unit#${repo_root}/}"
    fi
    swift_fixture_args=(
        --host 127.0.0.1
        --control-port "${control_port}"
        --profile-id "${profile_id}"
        --width "${profile_width}"
        --height "${profile_height}"
        --fps "${profile_fps}"
        --bitrate-bps "${video_bitrate_bps}"
        --rotation-degrees "${rotation_degrees}"
        --access-unit "${swift_access_unit_argument}"
        --repeat 3
        --linger-ms 1000
    )
    if command -v swift >/dev/null 2>&1; then
        (
            cd "${swift_fixture_package}"
            swift run --disable-sandbox cambridge-swift-fixture "${swift_fixture_args[@]}"
        )
    else
        command -v docker >/dev/null 2>&1 || fail "Swift or Docker is required for the Swift fixture"
        docker run --rm --network host \
            --mount "type=bind,source=${repo_root},target=/workspace" \
            --workdir /workspace/scripts/sender/ios/cambridge-swift-fixture \
            "${swift_container_image}" \
            swift run --disable-sandbox cambridge-swift-fixture "${swift_fixture_args[@]}"
    fi
fi

if [[ "${capture_output}" == "1" ]]; then
    if [[ -n "${monitor_pid}" ]]; then
        kill -TERM "${monitor_pid}" >/dev/null 2>&1 || true
        wait "${monitor_pid}" >/dev/null 2>&1 || true
        monitor_pid=""
    fi
    stop_obs
    recording_file=$(find "${recording_dir}" -maxdepth 1 -type f -name '*.mp4' -size +0c -print -quit)
    [[ -n "${recording_file}" ]] || fail "OBS did not produce a non-empty recording"
    ffprobe -v error -select_streams v:0 -show_entries stream=width,height \
        -of json "${recording_file}" >"${artifact_dir}/recording-stream.json"
    recording_width=$(jq -er '.streams[0].width' "${artifact_dir}/recording-stream.json")
    recording_height=$(jq -er '.streams[0].height' "${artifact_dir}/recording-stream.json")
    [[ "${recording_width}" -eq "${profile_width}" && "${recording_height}" -eq "${profile_height}" ]] \
        || fail "OBS recording used ${recording_width}x${recording_height}, expected ${profile_width}x${profile_height}"
    ffmpeg -hide_banner -loglevel error -i "${recording_file}" -map 0:v:0 \
        -vf "fps=${recording_sample_fps}" -an -f framemd5 "${recording_hashes}"
    unique_hashes=$(awk -F, '$1 !~ /^#/ && NF >= 2 {gsub(/[[:space:]]/, "", $NF); print $NF}' \
        "${recording_hashes}" | sort -u | wc -l)
    [[ "${unique_hashes}" -gt 1 ]] || fail "OBS recording did not show changing frame hashes"
fi

display_width="${profile_width}"
display_height="${profile_height}"
if [[ "${rotation_degrees}" == "90" || "${rotation_degrees}" == "270" ]]; then
    display_width="${profile_height}"
    display_height="${profile_width}"
fi
rg -q "session_accepted:.*:display=${display_width}x${display_height}:rotation=${rotation_degrees}@${profile_fps}" "${obs_log}" \
    || fail "native source did not accept the requested fixture profile"
rg -q 'control_disconnected_session_invalidated' "${obs_log}" \
    || fail "native source did not invalidate the sender session after stop"
rg -q 'decoder_ready:h264/(VAAPI|software)' "${obs_log}" \
    || fail "native decoder did not become ready"
rg -q "first_frame_published:mode=.*profile=${profile_width}x${profile_height}" "${obs_log}" \
    || fail "native source did not publish the requested profile"
if [[ "${decoder_mode}" == "cpu" ]]; then
    rg -q 'decoder_ready:h264/software' "${obs_log}" || fail "CPU decoder mode was not honored"
    rg -q 'render_mode=cpu_nv12_upload' "${obs_log}" || fail "CPU NV12 rendering was not reported"
    rg -q 'session_accepted:.*:requested=cpu:path=software' "${obs_log}" \
        || fail "CPU session path was not locked to software"
elif [[ "${decoder_mode}" == "native_required" ]]; then
    rg -q 'decoder_ready:h264/VAAPI' "${obs_log}" || fail "VAAPI was not active for the hardware fixture"
    rg -q 'render_mode=dma_buf_direct' "${obs_log}" || fail "direct DMA-BUF rendering was not reported"
    rg -q 'session_accepted:.*:requested=native_required:path=native' "${obs_log}" \
        || fail "native_required session path was not locked to native"
else
    if rg -q 'decoder_ready:h264/VAAPI' "${obs_log}"; then
        rg -q 'render_mode=dma_buf_direct' "${obs_log}" \
            || fail "direct DMA-BUF rendering was not reported"
        rg -q 'session_accepted:.*:requested=auto:path=native' "${obs_log}" \
            || fail "automatic native session path was not locked to native"
    else
        rg -q 'decoder_ready:h264/software' "${obs_log}" \
            || fail "automatic mode did not select a supported decoder"
        rg -q 'native_unsupported_selecting_software:' "${obs_log}" \
            || fail "automatic software selection was not explicitly reported"
        rg -q 'render_mode=cpu_nv12_upload' "${obs_log}" \
            || fail "automatic software rendering was not reported"
        rg -q 'session_accepted:.*:requested=auto:path=software' "${obs_log}" \
            || fail "automatic software session path was not locked to software"
    fi
fi
if rg -q 'decoder_error|decode_failed|rtp_invalid|settings_network_restart_failed' "${obs_log}"; then
    fail "native fixture reported a media failure; see ${obs_log}"
fi
printf 'profile=%s (%sx%s@%s)\n' "${profile_id}" "${profile_width}" "${profile_height}" "${profile_fps}"
printf 'display=%sx%s rotation=%s\n' "${display_width}" "${display_height}" "${rotation_degrees}"
printf 'decoder_mode=%s\n' "${decoder_mode}"
printf 'duration_seconds=%s\n' "${duration_seconds}"
printf 'capture_output=%s\n' "${capture_output}"
printf 'control=%s\n' "${control_port}"
printf 'media=%s\n' "${media_port}"
printf 'module_sha256='; sha256sum "${plugin_so}" | awk '{print $1}'
printf 'artifacts=%s\n' "${artifact_dir}"
