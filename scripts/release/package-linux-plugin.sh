#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
version=$(tr -d '[:space:]' <"${repo_root}/VERSION")
artifact_dir=${CAMBRIDGE_RELEASE_ARTIFACT_DIR:-"${repo_root}/build/release"}
build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin-release"}
staging_dir=${CAMBRIDGE_STAGING_DIR:-"${build_dir}/staging"}
variant_input_dir=${CAMBRIDGE_LINUX_VARIANT_INPUT_DIR:-"${repo_root}/build/linux-variants"}
skip_build=${CAMBRIDGE_SKIP_BUILD:-OFF}
skip_runtime_validation=${CAMBRIDGE_SKIP_RUNTIME_VALIDATION:-OFF}
require_all_variants=${CAMBRIDGE_REQUIRE_ALL_LINUX_VARIANTS:-OFF}
platform_id="linux-x86_64"
package_name="cambridge-obs-plugin-${version}-${platform_id}"
package_root="${artifact_dir}/${package_name}"
helper="${repo_root}/scripts/release/cambridge_linux_bundle.py"

fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

for required_command in python3 tar sha256sum file; do
    command -v "${required_command}" >/dev/null 2>&1 || fail "${required_command} is required"
done
case "${skip_build}" in
    ON|OFF) ;;
    *) fail "CAMBRIDGE_SKIP_BUILD must be ON or OFF" ;;
esac
case "${skip_runtime_validation}" in
    ON|OFF) ;;
    *) fail "CAMBRIDGE_SKIP_RUNTIME_VALIDATION must be ON or OFF" ;;
esac
case "${require_all_variants}" in
    ON|OFF) ;;
    *) fail "CAMBRIDGE_REQUIRE_ALL_LINUX_VARIANTS must be ON or OFF" ;;
esac

mkdir -p "${artifact_dir}" "${variant_input_dir}"

if [[ "${skip_build}" == "OFF" ]]; then
    if [[ -n "${CAMBRIDGE_LINUX_VARIANT_ID:-}" ]]; then
        CAMBRIDGE_REQUIRE_AVAHI=ON \
        CAMBRIDGE_BUILD_TYPE=Release \
        CAMBRIDGE_LINUX_VARIANT_OUTPUT_DIR="${variant_input_dir}" \
        CAMBRIDGE_BUILD_DIR="${build_dir}-${CAMBRIDGE_LINUX_VARIANT_ID}" \
        CAMBRIDGE_STAGING_DIR="${staging_dir}-${CAMBRIDGE_LINUX_VARIANT_ID}" \
            "${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin-variant.sh"
    else
        CAMBRIDGE_REQUIRE_AVAHI=ON \
        CAMBRIDGE_BUILD_DIR="${build_dir}" \
        CAMBRIDGE_STAGING_DIR="${staging_dir}" \
        CAMBRIDGE_BUILD_TYPE=Release \
            "${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin.sh"
        host_plugin_path="${staging_dir}/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
        detected_variant_id=$(python3 "${helper}" --print-variant-id "${host_plugin_path}")
        detected_variant_dir="${variant_input_dir}/${detected_variant_id}"
        mkdir -p "${detected_variant_dir}"
        cp "${host_plugin_path}" "${detected_variant_dir}/cambridge-obs-plugin.so"
        sha256sum "${host_plugin_path}" | awk '{print $1}' >"${detected_variant_dir}/cambridge-plugin.sha256"
    fi
fi

mapfile -t declared_variant_ids < <(python3 "${helper}" --list-ids)
present_variant_ids=()
for variant_id in "${declared_variant_ids[@]}"; do
    variant_dir="${variant_input_dir}/${variant_id}"
    plugin_path="${variant_dir}/cambridge-obs-plugin.so"
    if [[ -f "${plugin_path}" ]]; then
        present_variant_ids+=("${variant_id}")
    fi
done
(( ${#present_variant_ids[@]} > 0 )) || fail "no declared Linux plugin variant is available in ${variant_input_dir}"

if [[ "${require_all_variants}" == "ON" ]] &&
   (( ${#present_variant_ids[@]} != ${#declared_variant_ids[@]} )); then
    missing_variant_ids=()
    for variant_id in "${declared_variant_ids[@]}"; do
        if [[ ! -f "${variant_input_dir}/${variant_id}/cambridge-obs-plugin.so" ]]; then
            missing_variant_ids+=("${variant_id}")
        fi
    done
    fail "required Linux plugin variants are missing: ${missing_variant_ids[*]}"
fi

rm -rf "${package_root}"
rm -f "${artifact_dir}/${package_name}.tar.gz" "${artifact_dir}/${package_name}.tar.gz.sha256"
mkdir -p "${package_root}/variants"

for variant_id in "${present_variant_ids[@]}"; do
    source_variant_dir="${variant_input_dir}/${variant_id}"
    source_plugin_path="${source_variant_dir}/cambridge-obs-plugin.so"
    if [[ "${skip_runtime_validation}" == "ON" ]]; then
        validation_record="${source_variant_dir}/cambridge-validation.json"
        [[ -f "${validation_record}" ]] ||
            fail "prevalidated variant is missing its validation record: ${variant_id}"
        expected_provenance=$(python3 "${helper}" \
            --print-variant-provenance \
            --variant-id "${variant_id}")
        python3 - "${validation_record}" "${variant_id}" \
            "${source_plugin_path}" "${expected_provenance}" <<'PY'
import hashlib
import json
import pathlib
import sys

record_path = pathlib.Path(sys.argv[1])
variant_id = sys.argv[2]
plugin_path = pathlib.Path(sys.argv[3])
expected_provenance = sys.argv[4]
record = json.loads(record_path.read_text(encoding="utf-8"))
if record.get("variantId") != variant_id:
    raise SystemExit(f"validation record variant mismatch: {record_path}")
if record.get("buildProvenance") != expected_provenance:
    raise SystemExit(f"validation record provenance mismatch: {record_path}")
if record.get("runtimeValidation") != "ldd -r":
    raise SystemExit(f"validation record is missing the ldd -r check: {record_path}")
expected_hash = record.get("pluginSha256")
actual_hash = hashlib.sha256(plugin_path.read_bytes()).hexdigest()
if expected_hash != actual_hash:
    raise SystemExit(f"prevalidated plugin hash mismatch: {plugin_path}")
PY
        python3 "${helper}" \
            --validate-plugin "${source_plugin_path}" \
            --variant-id "${variant_id}" \
            --skip-runtime-validation
    else
        python3 "${helper}" \
            --validate-plugin "${source_plugin_path}" \
            --variant-id "${variant_id}"
    fi
    file -b "${source_plugin_path}" | grep -Eq 'ELF 64-bit.*x86-64' ||
        fail "Linux plugin variant is not an x86_64 ELF module: ${variant_id}"
    mkdir -p "${package_root}/variants/${variant_id}"
    cp "${source_plugin_path}" "${package_root}/variants/${variant_id}/cambridge-obs-plugin.so"
done

if find "${package_root}" -type f \( \
    -name 'libobs.so*' -o \
    -name 'libavcodec.so*' -o \
    -name 'libavutil.so*' -o \
    -name 'libswscale.so*' \
\) -print -quit | grep -q .; then
    fail "package contains bundled OBS or FFmpeg libraries"
fi

metadata_arguments=(--write-metadata "${package_root}/cambridge-linux-variants.json")
for variant_id in "${present_variant_ids[@]}"; do
    metadata_arguments+=(--include-id "${variant_id}")
done
python3 "${helper}" "${metadata_arguments[@]}"

cp "${repo_root}/scripts/release/install-linux-plugin.sh" "${package_root}/install-linux-plugin.sh"
cp "${repo_root}/scripts/release/install-linux-plugin.py" "${package_root}/install-linux-plugin.py"
cp "${helper}" "${package_root}/cambridge_linux_bundle.py"
chmod +x "${package_root}/install-linux-plugin.sh" "${package_root}/install-linux-plugin.py"
cp "${repo_root}/README.md" "${package_root}/README.md"
cp "${repo_root}/LICENSE" "${package_root}/LICENSE"
cp "${repo_root}/NOTICE" "${package_root}/NOTICE"
cp "${repo_root}/THIRD_PARTY_NOTICES.md" "${package_root}/THIRD_PARTY_NOTICES.md"
cp "${repo_root}/SECURITY.md" "${package_root}/SECURITY.md"
cp "${repo_root}/CONTRIBUTING.md" "${package_root}/CONTRIBUTING.md"
cp "${repo_root}/receiver/obs/cambridge-obs-source/LICENSE" "${package_root}/PLUGIN-LICENSE"
mkdir -p "${package_root}/docs"
cp "${repo_root}/docs/"*.md "${package_root}/docs/"
mkdir -p "${package_root}/protocol/examples"
cp "${repo_root}/protocol/cambridge-stream-contract.json" "${package_root}/protocol/"
cp "${repo_root}/protocol/cambridge-stream.schema.json" "${package_root}/protocol/"
cp "${repo_root}/protocol/cambridge-deployment.json" "${package_root}/protocol/"
cp "${repo_root}/protocol/README.md" "${package_root}/protocol/README.md"
cp "${repo_root}/protocol/examples/"*.json "${package_root}/protocol/examples/"

{
    printf 'artifact=cambridge-obs-plugin\n'
    printf 'version=%s\n' "${version}"
    printf 'platform=%s\n' "${platform_id}"
    printf 'linuxVariantIds='
    (IFS=,; printf '%s' "${present_variant_ids[*]}")
    printf '\n'
} >"${package_root}/release-metadata.txt"

archive_path="${artifact_dir}/${package_name}.tar.gz"
tar -C "${artifact_dir}" -czf "${archive_path}" "${package_name}"
(
    cd "${artifact_dir}"
    sha256sum "$(basename "${archive_path}")" >"$(basename "${archive_path}").sha256"
)

printf 'archive=%s\n' "${archive_path}"
printf 'checksum=%s\n' "${archive_path}.sha256"
printf 'variants=%s\n' "${present_variant_ids[*]}"
