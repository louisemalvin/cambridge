#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
version=$(tr -d '[:space:]' <"${repo_root}/VERSION")
artifact_dir=${CAMBRIDGE_RELEASE_ARTIFACT_DIR:-"${repo_root}/build/release"}
build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin-release"}
staging_dir=${CAMBRIDGE_STAGING_DIR:-"${build_dir}/staging"}
platform_id="linux-x86_64"
package_name="cambridge-obs-plugin-${version}-${platform_id}"
package_root="${artifact_dir}/${package_name}"
plugin_path="${staging_dir}/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"

command -v pkg-config >/dev/null 2>&1 || { printf 'error: pkg-config is required\n' >&2; exit 1; }
command -v tar >/dev/null 2>&1 || { printf 'error: tar is required\n' >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { printf 'error: sha256sum is required\n' >&2; exit 1; }
command -v file >/dev/null 2>&1 || { printf 'error: file is required\n' >&2; exit 1; }

rm -rf "${package_root}"
mkdir -p "${artifact_dir}" "${package_root}/obs-plugins/cambridge-obs-plugin/bin/64bit"

CAMBRIDGE_REQUIRE_AVAHI=ON \
CAMBRIDGE_BUILD_DIR="${build_dir}" \
CAMBRIDGE_STAGING_DIR="${staging_dir}" \
CAMBRIDGE_BUILD_TYPE=Release \
    "${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin.sh"

[[ -f "${plugin_path}" ]] || {
    printf 'error: native plugin was not staged: %s\n' "${plugin_path}" >&2
    exit 1
}
file -b "${plugin_path}" | grep -Eq 'ELF 64-bit.*x86-64' || {
    printf 'error: native plugin is not an x86_64 ELF module\n' >&2
    exit 1
}

cp "${plugin_path}" "${package_root}/obs-plugins/cambridge-obs-plugin/bin/64bit/"
cp "${repo_root}/README.md" "${package_root}/README.md"
cp "${repo_root}/LICENSE" "${package_root}/LICENSE"
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

obs_version=$(pkg-config --modversion libobs)
printf 'artifact=cambridge-obs-plugin\nversion=%s\nplatform=%s\nobs_libobs=%s\n' \
    "${version}" "${platform_id}" "${obs_version}" \
    >"${package_root}/release-metadata.txt"

archive_path="${artifact_dir}/${package_name}.tar.gz"
rm -f "${archive_path}" "${archive_path}.sha256"
tar -C "${artifact_dir}" -czf "${archive_path}" "${package_name}"
(
    cd "${artifact_dir}"
    sha256sum "$(basename "${archive_path}")" >"$(basename "${archive_path}").sha256"
)

printf 'archive=%s\n' "${archive_path}"
printf 'checksum=%s\n' "${archive_path}.sha256"
