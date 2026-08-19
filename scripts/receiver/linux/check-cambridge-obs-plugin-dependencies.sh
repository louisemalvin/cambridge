#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    printf 'usage: %s PLUGIN\n' "$0" >&2
    exit 2
fi

plugin_path=$1
if [[ ! -f "${plugin_path}" ]]; then
    printf 'error: plugin does not exist: %s\n' "${plugin_path}" >&2
    exit 1
fi

required_commands=(ldd pkg-config readelf)
for required_command in "${required_commands[@]}"; do
    command -v "${required_command}" >/dev/null 2>&1 || {
        printf 'error: required command is unavailable: %s\n' "${required_command}" >&2
        exit 1
    }
done

read_soname() {
    local library_path=$1
    readelf -d "${library_path}" |
        awk -F'[][]' '/Library soname:/ && !found {print $2; found=1}'
}

required_modules=(libobs libavcodec libavutil libswscale)
declare -A expected_sonames=()
for module in "${required_modules[@]}"; do
    library_dir=$(pkg-config --variable=libdir "${module}")
    library_path="${library_dir}/${module}.so"
    if [[ ! -e "${library_path}" ]]; then
        printf 'error: pkg-config library is missing: %s\n' "${library_path}" >&2
        exit 1
    fi
    expected_soname=$(read_soname "${library_path}")
    if [[ -z "${expected_soname}" ]]; then
        printf 'error: could not read SONAME for %s\n' "${library_path}" >&2
        exit 1
    fi
    expected_sonames["${module}"]=${expected_soname}
done

dynamic_section=$(readelf -d "${plugin_path}")
if grep -Eq ' \((RPATH|RUNPATH)\)' <<<"${dynamic_section}"; then
    printf 'error: Linux plugin contains RPATH or RUNPATH: %s\n' "${plugin_path}" >&2
    exit 1
fi

needed_libraries=$(sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' <<<"${dynamic_section}")
for module in "${required_modules[@]}"; do
    expected_soname=${expected_sonames["${module}"]}
    if ! grep -Fqx "${expected_soname}" <<<"${needed_libraries}"; then
        printf 'error: plugin is not linked to the selected %s ABI (%s)\n' \
            "${module}" "${expected_soname}" >&2
        exit 1
    fi
done

ldd_output=$(ldd -r "${plugin_path}" 2>&1) || {
    printf '%s\n' "${ldd_output}" >&2
    printf 'error: ldd failed for plugin: %s\n' "${plugin_path}" >&2
    exit 1
}
if grep -Eq 'not found|undefined symbol' <<<"${ldd_output}"; then
    printf '%s\n' "${ldd_output}" >&2
    printf 'error: plugin has unresolved runtime dependencies: %s\n' "${plugin_path}" >&2
    exit 1
fi

printf 'validated Linux plugin dependencies: %s\n' "${plugin_path}"
printf '  OBS: %s\n' "${expected_sonames[libobs]}"
printf '  FFmpeg: %s, %s, %s\n' \
    "${expected_sonames[libavcodec]}" \
    "${expected_sonames[libavutil]}" \
    "${expected_sonames[libswscale]}"
