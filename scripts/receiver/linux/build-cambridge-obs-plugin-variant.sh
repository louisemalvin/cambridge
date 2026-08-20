#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
variant_id=${CAMBRIDGE_LINUX_VARIANT_ID:-}
variant_output_dir=${CAMBRIDGE_LINUX_VARIANT_OUTPUT_DIR:-"${repo_root}/build/linux-variants"}

if [[ -z "${variant_id}" ]]; then
    printf 'error: CAMBRIDGE_LINUX_VARIANT_ID is required\n' >&2
    exit 2
fi

build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin-${variant_id}"}
staging_dir=${CAMBRIDGE_STAGING_DIR:-"${build_dir}/staging"}
plugin_path="${staging_dir}/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
variant_dir="${variant_output_dir}/${variant_id}"

CAMBRIDGE_LINUX_VARIANT_ID="${variant_id}" \
CAMBRIDGE_BUILD_DIR="${build_dir}" \
CAMBRIDGE_STAGING_DIR="${staging_dir}" \
    "${script_dir}/build-cambridge-obs-plugin.sh"

[[ -f "${plugin_path}" ]] || {
    printf 'error: native plugin was not staged: %s\n' "${plugin_path}" >&2
    exit 1
}

mkdir -p "${variant_dir}"
cp "${plugin_path}" "${variant_dir}/cambridge-obs-plugin.so"
python3 "${repo_root}/scripts/release/cambridge_linux_bundle.py" \
    --validate-plugin "${variant_dir}/cambridge-obs-plugin.so" \
    --variant-id "${variant_id}"
python3 - "${variant_dir}/cambridge-validation.json" "${variant_id}" "${variant_dir}/cambridge-obs-plugin.so" <<'PY'
import hashlib
import json
import pathlib
import sys

record_path = pathlib.Path(sys.argv[1])
variant_id = sys.argv[2]
plugin_path = pathlib.Path(sys.argv[3])
record = {
    "variantId": variant_id,
    "pluginSha256": hashlib.sha256(plugin_path.read_bytes()).hexdigest(),
    "runtimeValidation": "ldd -r",
}
record_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
PY
printf 'variant=%s\n' "${variant_id}"
printf 'plugin=%s\n' "${variant_dir}/cambridge-obs-plugin.so"
