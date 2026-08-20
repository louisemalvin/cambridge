#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

if [[ $# -ne 1 ]]; then
    printf 'usage: %s PLUGIN\n' "$0" >&2
    exit 2
fi

plugin_path=$1
if [[ ! -f "${plugin_path}" ]]; then
    printf 'error: plugin does not exist: %s\n' "${plugin_path}" >&2
    exit 1
fi

required_commands=(ldd python3 readelf)
for required_command in "${required_commands[@]}"; do
    command -v "${required_command}" >/dev/null 2>&1 || {
        printf 'error: required command is unavailable: %s\n' "${required_command}" >&2
        exit 1
    }
done

repo_root=$(cd -- "${script_dir}/../../.." && pwd)
variant_args=()
if [[ -n "${CAMBRIDGE_LINUX_VARIANT_ID:-}" ]]; then
    variant_args+=(--variant-id "${CAMBRIDGE_LINUX_VARIANT_ID}")
fi
python3 "${repo_root}/scripts/release/cambridge_linux_bundle.py" \
    --validate-plugin "${plugin_path}" "${variant_args[@]}"
printf 'validated Linux plugin dependencies: %s\n' "${plugin_path}"
