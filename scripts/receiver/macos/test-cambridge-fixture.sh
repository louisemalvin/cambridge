#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin-macos"}
artifact_dir=$(mktemp -d "${repo_root}/build/cambridge-macos-fixture.XXXXXX")
contract_json="${repo_root}/protocol/cambridge-stream-contract.json"
scene_template="${repo_root}/scripts/receiver/common/cambridge-test-scene.json"
profile_template="${repo_root}/scripts/receiver/common/cambridge-test-profile.ini"
fixture_script="${repo_root}/scripts/receiver/common/cambridge-fixture.py"

profile_id=${CAMBRIDGE_PROFILE_ID:-"cambridge-fixture-${PPID}"}
collection_id=${CAMBRIDGE_COLLECTION_ID:-"CamBridgeFixture-${PPID}"}
video_width=${CAMBRIDGE_WIDTH:-2560}
video_height=${CAMBRIDGE_HEIGHT:-1440}
video_fps=${CAMBRIDGE_FPS:-30}
video_bitrate_bps=${CAMBRIDGE_BITRATE_BPS:-18000000}
rotation_degrees=${CAMBRIDGE_ROTATION_DEGREES:-0}
duration_seconds=${CAMBRIDGE_DURATION_SECONDS:-60}
decoder_mode=${CAMBRIDGE_DECODER_MODE:-auto}
native_fault=${CAMBRIDGE_NATIVE_FAULT:-}
capture_output=${CAMBRIDGE_CAPTURE_OUTPUT:-1}
discovery_expectation=${CAMBRIDGE_EXPECT_DISCOVERY:-optional}
poll_interval_seconds=1
obs_wait_seconds=30
obs_shutdown_wait_seconds=5
recording_sample_fps=1

obs_bin=${CAMBRIDGE_OBS_BIN:-/Applications/OBS.app/Contents/MacOS/obs}
obs_config=${CAMBRIDGE_OBS_CONFIG_DIR:-$(python3 -c 'from pathlib import Path; print(Path.home() / "Library/Application Support/obs-studio")')}
obs_log="${artifact_dir}/obs.log"
metrics_log="${artifact_dir}/process-metrics.tsv"
fixture_summary="${artifact_dir}/fixture-summary.json"
scene_config="${artifact_dir}/scene.json"
recording_dir="${artifact_dir}/recording"
recording_hashes="${artifact_dir}/recording.framemd5"
diagnostics_path="${artifact_dir}/diagnostics.json"
recording_file=""

obs_plugin_dir="${obs_config}/plugins/cambridge-obs-plugin/bin/64bit"
plugin_so="${build_dir}/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
metallib="${build_dir}/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/nv12_to_bgra.metallib"
plugin_backup="${artifact_dir}/cambridge-obs-plugin.so.backup"
metallib_backup="${artifact_dir}/nv12_to_bgra.metallib.backup"
plugin_was_present=0
metallib_was_present=0
obs_files_touched=0
scene_created=0
profile_created=0
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

restore_obs_files() {
    stop_obs
    if [[ "${obs_files_touched}" == "1" ]]; then
        if [[ "${plugin_was_present}" == "1" ]]; then
            mv -f "${plugin_backup}" "${obs_plugin_dir}/cambridge-obs-plugin.so"
        else
            rm -f "${obs_plugin_dir}/cambridge-obs-plugin.so"
        fi
        if [[ "${metallib_was_present}" == "1" ]]; then
            mv -f "${metallib_backup}" "${obs_plugin_dir}/nv12_to_bgra.metallib"
        else
            rm -f "${obs_plugin_dir}/nv12_to_bgra.metallib"
        fi
    fi
    if [[ "${scene_created}" == "1" ]]; then
        rm -f "${obs_config}/basic/scenes/${collection_id}.json"
    fi
    if [[ "${profile_created}" == "1" ]]; then
        rm -rf "${obs_config}/basic/profiles/${profile_id}"
    fi
}

cleanup() {
    if [[ -n "${monitor_pid}" ]]; then
        kill -TERM "${monitor_pid}" >/dev/null 2>&1 || true
        wait "${monitor_pid}" >/dev/null 2>&1 || true
    fi
    restore_obs_files
}

trap cleanup EXIT

[[ -f "${contract_json}" ]] || fail "CamBridge stream contract is missing: ${contract_json}"
[[ -f "${scene_template}" ]] || fail "OBS scene template is missing: ${scene_template}"
[[ -f "${profile_template}" ]] || fail "OBS profile template is missing: ${profile_template}"
[[ -f "${fixture_script}" ]] || fail "fixture script is missing: ${fixture_script}"
[[ -x "${obs_bin}" ]] || fail "OBS executable is missing or not executable: ${obs_bin}"
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v ffmpeg >/dev/null 2>&1 || fail "ffmpeg is required"
command -v ffprobe >/dev/null 2>&1 || fail "ffprobe is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"
case "${decoder_mode}" in
    auto|cpu|native_required) ;;
    *) fail "decoder mode must be auto, cpu, or native_required" ;;
esac
case "${native_fault}" in
    ""|export|conversion|import|pool) ;;
    *) fail "native fault must be empty, export, conversion, import, or pool" ;;
esac
case "${capture_output}" in
    0|1) ;;
    *) fail "capture output must be 0 or 1" ;;
esac
case "${discovery_expectation}" in
    required|optional|unavailable) ;;
    *) fail "discovery expectation must be required, optional, or unavailable" ;;
esac
[[ "${duration_seconds}" =~ ^[1-9][0-9]*$ ]] || fail "duration must be a positive integer"
[[ "${rotation_degrees}" =~ ^(0|90|180|270)$ ]] || fail "rotation must be 0, 90, 180, or 270 degrees"
if [[ -n "${native_fault}" && "${decoder_mode}" != "native_required" ]]; then
    fail "native fault tests require decoder_mode=native_required"
fi
[[ ! -e "${obs_config}/basic/scenes/${collection_id}.json" ]] || \
    fail "fixture scene already exists: ${obs_config}/basic/scenes/${collection_id}.json"
[[ ! -e "${obs_config}/basic/profiles/${profile_id}" ]] || \
    fail "fixture profile already exists: ${obs_config}/basic/profiles/${profile_id}"

contract_control_port=$(jq -er '.defaults.controlPort' "${contract_json}")
media_port_offset=$(jq -er '.defaults.mediaPortOffset' "${contract_json}")
control_port=${CAMBRIDGE_CONTROL_PORT:-${contract_control_port}}
media_port=${CAMBRIDGE_MEDIA_PORT:-$((control_port + media_port_offset))}

"${repo_root}/scripts/receiver/macos/build-cambridge-obs-plugin.sh" \
    >"${artifact_dir}/plugin-build.log"
[[ -f "${plugin_so}" ]] || fail "staged OBS plugin is missing: ${plugin_so}"
[[ -f "${metallib}" ]] || fail "staged Metal library is missing: ${metallib}"

mkdir -p "${obs_plugin_dir}" "${obs_config}/basic/scenes" "${obs_config}/basic/profiles/${profile_id}"
obs_files_touched=1
profile_created=1
if [[ -e "${obs_plugin_dir}/cambridge-obs-plugin.so" ]]; then
    mv "${obs_plugin_dir}/cambridge-obs-plugin.so" "${plugin_backup}"
    plugin_was_present=1
fi
if [[ -e "${obs_plugin_dir}/nv12_to_bgra.metallib" ]]; then
    mv "${obs_plugin_dir}/nv12_to_bgra.metallib" "${metallib_backup}"
    metallib_was_present=1
fi
cp "${plugin_so}" "${obs_plugin_dir}/cambridge-obs-plugin.so"
cp "${metallib}" "${obs_plugin_dir}/nv12_to_bgra.metallib"

jq --arg decoder_mode "${decoder_mode}" \
    --arg collection_id "${collection_id}" \
    --argjson control_port "${control_port}" \
    --argjson media_port "${media_port}" \
    --arg diagnostics_path "${diagnostics_path}" \
    '(.name = $collection_id | (.sources[] | select(.id == "cambridge_android_source").settings) |=
        (.decoder_mode = $decoder_mode | .control_port = $control_port | .media_port = $media_port |
         .diagnostics_path = $diagnostics_path)' \
    "${scene_template}" >"${scene_config}"
cp "${scene_config}" "${obs_config}/basic/scenes/${collection_id}.json"
scene_created=1
sed \
    -e "s|__CAMBRIDGE_RECORDING_DIR__|${recording_dir}|g" \
    -e "s|__CAMBRIDGE_OUTPUT_WIDTH__|${video_width}|g" \
    -e "s|__CAMBRIDGE_OUTPUT_HEIGHT__|${video_height}|g" \
    -e "s|__CAMBRIDGE_OUTPUT_FPS__|${video_fps}|g" \
    "${profile_template}" >"${obs_config}/basic/profiles/${profile_id}/basic.ini"

obs_args=(
    --multi
    --verbose
    --disable-missing-files-check
    --profile "${profile_id}"
    --collection "${collection_id}"
    --scene Scene
)
if [[ "${capture_output}" == "1" && -z "${native_fault}" ]]; then
    mkdir -p "${recording_dir}"
    obs_args+=(--startrecording)
fi
if [[ -n "${native_fault}" ]]; then
    env CAMBRIDGE_NATIVE_FAULT="${native_fault}" \
        CAMBRIDGE_TEST_WRITE_DIAGNOSTICS_ON_SESSION_END=1 \
        "${obs_bin}" "${obs_args[@]}" >"${obs_log}" 2>&1 &
else
    env CAMBRIDGE_TEST_WRITE_DIAGNOSTICS_ON_SESSION_END=1 \
        "${obs_bin}" "${obs_args[@]}" >"${obs_log}" 2>&1 &
fi
obs_pid=$!
for ((attempt = 0; attempt < obs_wait_seconds; attempt += poll_interval_seconds)); do
    rg -q 'listening:control=' "${obs_log}" && break
    sleep "${poll_interval_seconds}"
done
rg -q 'loaded module=cambridge-obs-plugin' "${obs_log}" || fail "OBS did not load the CamBridge OBS plugin"
rg -q 'listening:control=' "${obs_log}" || fail "OBS source did not begin listening"
case "${discovery_expectation}" in
    required)
        rg -q 'discovery:service_type=' "${obs_log}" || fail "Bonjour registration was not reported"
        ;;
    unavailable)
        rg -q 'discovery_unavailable:' "${obs_log}" || fail "degraded discovery was not reported"
        ;;
    optional) ;;
esac

printf 'timestamp_seconds\tresident_kib\tthreads\tfile_descriptors\tcpu_percent\n' >"${metrics_log}"
(
    while kill -0 "${obs_pid}" >/dev/null 2>&1; do
        timestamp=$(date +%s)
        resident_kib=$(ps -o rss= -p "${obs_pid}" | awk '{$1=$1; print}')
        threads=$(ps -o nlwp= -p "${obs_pid}" | awk '{$1=$1; print}')
        file_descriptors=$(lsof -p "${obs_pid}" 2>/dev/null | tail -n +2 | wc -l | awk '{$1=$1; print}')
        cpu_percent=$(ps -o %cpu= -p "${obs_pid}" | awk '{$1=$1; print}')
        printf '%s\t%s\t%s\t%s\t%s\n' "${timestamp}" "${resident_kib:-0}" \
            "${threads:-0}" "${file_descriptors:-0}" "${cpu_percent:-0}" >>"${metrics_log}"
        sleep "${poll_interval_seconds}"
    done
) &
monitor_pid=$!

python3 "${fixture_script}" \
    --contract "${contract_json}" \
    --profile-id "${profile_id}" \
    --width "${video_width}" \
    --height "${video_height}" \
    --fps "${video_fps}" \
    --bitrate-bps "${video_bitrate_bps}" \
    --host 127.0.0.1 \
    --control-port "${control_port}" \
    --media-port "${media_port}" \
    --duration "${duration_seconds}" \
    --rotation-degrees "${rotation_degrees}" \
    --output "${fixture_summary}" \
    --ffmpeg ffmpeg

if [[ -n "${native_fault}" ]]; then
    stop_obs
    rg -q 'media_path_failure:code=(native_export|native_conversion|native_import)' "${obs_log}" \
        || fail "fault ${native_fault} did not fail the active session"
    if [[ "${native_fault}" == "pool" ]]; then
        rg -q 'media_path_failure:code=native_conversion:detail=native_pool_exhaustion:' "${obs_log}" \
            || fail "pool exhaustion was not classified as native conversion failure"
    fi
    [[ -f "${diagnostics_path}" ]] || fail "faulted session did not write diagnostics"
    case "${native_fault}" in
        export)
            jq -e '.lastMediaPathFailureCode == "native_export"' "${diagnostics_path}" >/dev/null
            ;;
        conversion)
            jq -e '.lastMediaPathFailureCode == "native_conversion"' "${diagnostics_path}" >/dev/null
            ;;
        import)
            jq -e '.lastMediaPathFailureCode == "native_import" and .nativeImportFailures >= 1' \
                "${diagnostics_path}" >/dev/null
            ;;
        pool)
            jq -e '.lastMediaPathFailureCode == "native_conversion" and .nativePoolExhaustions >= 1' \
                "${diagnostics_path}" >/dev/null
            ;;
    esac
    printf 'fault=%s\n' "${native_fault}"
    printf 'artifacts=%s\n' "${artifact_dir}"
    exit 0
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
    [[ "${recording_width}" -eq "${video_width}" && "${recording_height}" -eq "${video_height}" ]] \
        || fail "OBS recording used ${recording_width}x${recording_height}, expected ${video_width}x${video_height}"
    ffmpeg -hide_banner -loglevel error -i "${recording_file}" -map 0:v:0 \
        -vf "fps=${recording_sample_fps}" -an -f framemd5 "${recording_hashes}"
    unique_hashes=$(awk -F, '$1 !~ /^#/ && NF >= 2 {gsub(/[[:space:]]/, "", $NF); print $NF}' \
        "${recording_hashes}" | sort -u | wc -l)
    [[ "${unique_hashes}" -gt 1 ]] || fail "OBS recording did not show changing frame hashes"
fi

display_width="${video_width}"
display_height="${video_height}"
if [[ "${rotation_degrees}" == "90" || "${rotation_degrees}" == "270" ]]; then
    display_width="${video_height}"
    display_height="${video_width}"
fi
rg -q "session_accepted:.*:display=${display_width}x${display_height}:rotation=${rotation_degrees}@${video_fps}" "${obs_log}" \
    || fail "macOS source did not accept the requested fixture profile"
rg -q 'control_disconnected_session_invalidated' "${obs_log}" \
    || fail "macOS source did not invalidate the sender session after stop"
rg -q 'decoder_ready:h264/(VideoToolbox|software)' "${obs_log}" \
    || fail "macOS decoder did not become ready"
rg -q "first_frame_published:mode=.*profile=${video_width}x${video_height}" "${obs_log}" \
    || fail "macOS source did not publish the requested profile"
if [[ "${decoder_mode}" == "cpu" ]]; then
    rg -q 'decoder_ready:h264/software' "${obs_log}" || fail "CPU decoder mode was not honored"
    rg -q 'render_mode=software' "${obs_log}" || fail "software rendering was not reported"
    rg -q 'session_accepted:.*:requested=cpu:path=software' "${obs_log}" \
        || fail "CPU session path was not locked to software"
elif [[ "${decoder_mode}" == "native_required" ]]; then
    rg -q 'decoder_ready:h264/VideoToolbox' "${obs_log}" || fail "VideoToolbox was not active"
    rg -q 'render_mode=native' "${obs_log}" || fail "native rendering was not reported"
    rg -q 'session_accepted:.*:requested=native_required:path=native' "${obs_log}" \
        || fail "native_required path was not locked to native"
else
    if rg -q 'decoder_ready:h264/VideoToolbox' "${obs_log}"; then
        rg -q 'render_mode=native' "${obs_log}" || fail "native rendering was not reported"
        rg -q 'session_accepted:.*:requested=auto:path=native' "${obs_log}" \
            || fail "automatic native path was not locked"
    else
        rg -q 'decoder_ready:h264/software' "${obs_log}" || fail "automatic mode selected no decoder"
        rg -q 'native_unsupported_selecting_software:' "${obs_log}" \
            || fail "automatic software selection was not reported"
        rg -q 'render_mode=software' "${obs_log}" || fail "automatic software rendering was not reported"
        rg -q 'session_accepted:.*:requested=auto:path=software' "${obs_log}" \
            || fail "automatic software path was not locked"
    fi
fi
[[ -f "${diagnostics_path}" ]] || fail "OBS did not write the requested diagnostics snapshot"
if [[ "${decoder_mode}" == "native_required" ||
      ( "${decoder_mode}" == "auto" && "$(jq -r '.sessionMediaPath' "${diagnostics_path}")" == "native" ) ]]; then
    jq -e '.decoder == "h264/VideoToolbox" and .sessionMediaPath == "native" and
        .cpuFrameCopies == 0 and .hardwareCpuTransfers == 0 and .gpuCopies >= 1' \
        "${diagnostics_path}" >/dev/null || fail "native diagnostics do not prove the Metal path"
else
    jq -e '.sessionMediaPath == "software" and .cpuFrameCopies >= 1 and
        .hardwareCpuTransfers == 0' "${diagnostics_path}" >/dev/null \
        || fail "software diagnostics do not prove the CPU path"
fi
if rg -q 'decoder_error|decode_failed|rtp_invalid|settings_network_restart_failed' "${obs_log}"; then
    fail "macOS fixture reported a media failure; see ${obs_log}"
fi

printf 'profile=%s (%sx%s@%s)\n' "${profile_id}" "${video_width}" "${video_height}" "${video_fps}"
printf 'display=%sx%s rotation=%s\n' "${display_width}" "${display_height}" "${rotation_degrees}"
printf 'decoder_mode=%s\n' "${decoder_mode}"
printf 'duration_seconds=%s\n' "${duration_seconds}"
printf 'control=%s\n' "${control_port}"
printf 'media=%s\n' "${media_port}"
printf 'module_sha256='
shasum -a 256 "${plugin_so}" | awk '{print $1}'
printf 'artifacts=%s\n' "${artifact_dir}"
