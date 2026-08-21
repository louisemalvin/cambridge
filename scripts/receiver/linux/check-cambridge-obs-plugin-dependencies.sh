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

for required_command in ldd readelf; do
    command -v "${required_command}" >/dev/null 2>&1 || {
        printf 'error: required command is unavailable: %s\n' "${required_command}" >&2
        exit 1
    }
done

dynamic_section=$(readelf -d "${plugin_path}")
if grep -Eq '\(RPATH\)|\(RUNPATH\)' <<<"${dynamic_section}"; then
    printf 'error: plugin contains RPATH or RUNPATH: %s\n' "${plugin_path}" >&2
    exit 1
fi

runtime_output=$(ldd -r "${plugin_path}" 2>&1) || {
    printf '%s\n' "${runtime_output}" >&2
    exit 1
}
if grep -Eiq 'not found|undefined symbol' <<<"${runtime_output}"; then
    printf 'error: plugin has unresolved runtime dependencies: %s\n%s\n' \
        "${plugin_path}" "${runtime_output}" >&2
    exit 1
fi

printf 'validated Linux plugin dependencies: %s\n' "${plugin_path}"
