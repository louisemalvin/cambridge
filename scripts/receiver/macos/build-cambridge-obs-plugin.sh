#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin-macos"}
staging_dir=${CAMBRIDGE_STAGING_DIR:-"${build_dir}/staging"}
build_type=${CAMBRIDGE_BUILD_TYPE:-RelWithDebInfo}
enable_test_faults=${CAMBRIDGE_ENABLE_TEST_FAULTS:-OFF}
require_universal=${CAMBRIDGE_REQUIRE_UNIVERSAL:-ON}
buildspec_file="${repo_root}/receiver/obs/cambridge-obs-source/buildspec.json"
git_commit=$(git -C "${repo_root}" rev-parse HEAD)
if [[ -n "$(git -C "${repo_root}" status --porcelain --untracked-files=all)" ]]; then
    git_commit="${git_commit}-dirty"
fi

command -v cmake >/dev/null 2>&1 || { printf 'error: cmake is required\n' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { printf 'error: jq is required\n' >&2; exit 1; }
command -v xcrun >/dev/null 2>&1 || { printf 'error: Xcode xcrun is required\n' >&2; exit 1; }
command -v lipo >/dev/null 2>&1 || { printf 'error: lipo is required\n' >&2; exit 1; }
command -v otool >/dev/null 2>&1 || { printf 'error: otool is required\n' >&2; exit 1; }
command -v plutil >/dev/null 2>&1 || { printf 'error: plutil is required\n' >&2; exit 1; }
command -v codesign >/dev/null 2>&1 || { printf 'error: codesign is required\n' >&2; exit 1; }
case "${require_universal}" in
    ON|OFF) ;;
    *) printf 'error: CAMBRIDGE_REQUIRE_UNIVERSAL must be ON or OFF\n' >&2; exit 1 ;;
esac
deployment_target=${CAMBRIDGE_MACOS_DEPLOYMENT_TARGET:-$(jq -er '.baseline.macosDeploymentTarget' "${buildspec_file}")}
architectures=${CAMBRIDGE_MACOS_ARCHITECTURES:-$(jq -er '.baseline.architectures | join(";")' "${buildspec_file}")}
discovery_service_type=$(jq -er '.discovery.serviceType' "${repo_root}/protocol/cambridge-stream-contract.json")

cmake --fresh -S "${repo_root}/receiver/obs/cambridge-obs-source" -B "${build_dir}" \
    -G "Unix Makefiles" \
    -DCMAKE_BUILD_TYPE="${build_type}" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="${deployment_target}" \
    -DCMAKE_OSX_ARCHITECTURES="${architectures}" \
    -DCAMBRIDGE_BUILD_PLUGIN=ON \
    -DCAMBRIDGE_BUILD_TESTS=ON \
    -DCAMBRIDGE_VALIDATE_MACOS_DEPENDENCIES=ON \
    -DCAMBRIDGE_ENABLE_TEST_FAULTS="${enable_test_faults}" \
    -DCAMBRIDGE_GIT_COMMIT="${git_commit}" \
    -DCMAKE_INSTALL_PREFIX="${staging_dir}"
cmake --build "${build_dir}" --parallel
ctest --test-dir "${build_dir}" --output-on-failure
cmake --install "${build_dir}"

plugin_bundle="${staging_dir}/obs-plugins/cambridge-obs-plugin.plugin"
plugin_path="${plugin_bundle}/Contents/MacOS/cambridge-obs-plugin"
metallib_path="${plugin_bundle}/Contents/Resources/nv12_to_bgra.metallib"
info_plist="${plugin_bundle}/Contents/Info.plist"
[[ -d "${plugin_bundle}" ]] || { printf 'error: plugin bundle was not staged: %s\n' "${plugin_bundle}" >&2; exit 1; }
[[ -f "${plugin_path}" ]] || { printf 'error: plugin binary was not staged: %s\n' "${plugin_path}" >&2; exit 1; }
[[ -f "${metallib_path}" ]] || { printf 'error: Metal library was not staged: %s\n' "${metallib_path}" >&2; exit 1; }
[[ -f "${info_plist}" ]] || { printf 'error: bundle Info.plist was not staged: %s\n' "${info_plist}" >&2; exit 1; }
plutil -lint "${info_plist}" >/dev/null
[[ "$(plutil -extract CFBundleExecutable raw "${info_plist}")" == "cambridge-obs-plugin" ]] || {
    printf 'error: bundle executable metadata is incorrect\n' >&2
    exit 1
}
[[ -n "$(plutil -extract NSLocalNetworkUsageDescription raw "${info_plist}")" ]] || {
    printf 'error: local-network permission description is missing\n' >&2
    exit 1
}
[[ "$(plutil -extract NSBonjourServices.0 raw "${info_plist}")" == "${discovery_service_type}" ]] || {
    printf 'error: Bonjour service metadata does not match the protocol contract\n' >&2
    exit 1
}
IFS=';' read -r -a architecture_list <<<"${architectures}"
lipo -verify_arch "${architecture_list[@]}" "${plugin_path}"
plugin_architectures=$(lipo -archs "${plugin_path}")
if [[ "${require_universal}" == "ON" ]]; then
    [[ "${plugin_architectures}" == *arm64* && "${plugin_architectures}" == *x86_64* ]] || {
        printf 'error: plugin is not universal: %s\n' "${plugin_architectures}" >&2
        exit 1
    }
fi
if otool -L "${plugin_path}" | rg -q '/opt/homebrew|/usr/local/opt|/usr/local/Cellar|/opt/homebrew/Cellar'; then
    printf 'error: plugin contains an unintended Homebrew load command\n' >&2
    exit 1
fi
codesign --force --sign - --timestamp=none "${plugin_bundle}"
codesign --verify --strict --verbose=2 "${plugin_bundle}"

printf 'bundle=%s\n' "${plugin_bundle}"
printf 'module=%s\n' "${plugin_path}"
printf 'architectures=%s\n' "${plugin_architectures}"
printf 'metallib=%s\n' "${metallib_path}"
printf 'commit=%s\n' "${git_commit}"
printf 'sha256='
shasum -a 256 "${plugin_path}" | awk '{print $1}'
printf 'staging=%s\n' "${staging_dir}"
