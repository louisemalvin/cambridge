#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
version=$(tr -d '[:space:]' <"${repo_root}/VERSION")
artifact_dir=${CAMBRIDGE_RELEASE_ARTIFACT_DIR:-"${repo_root}/build/release"}
build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin-macos-release"}
staging_dir=${CAMBRIDGE_STAGING_DIR:-"${build_dir}/staging"}
package_name="cambridge-obs-plugin-${version}-macos-universal.pkg"
package_path="${artifact_dir}/${package_name}"
component_path="${artifact_dir}/cambridge-obs-plugin-${version}-component.pkg"
package_root="${artifact_dir}/cambridge-obs-plugin-${version}-macos-root"
plugin_path="${staging_dir}/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
metallib_path="${staging_dir}/obs-plugins/cambridge-obs-plugin/bin/64bit/nv12_to_bgra.metallib"
application_identity=${CAMBRIDGE_DEVELOPER_ID_APPLICATION:-}
installer_identity=${CAMBRIDGE_DEVELOPER_ID_INSTALLER:-}
notary_profile=${CAMBRIDGE_NOTARY_PROFILE:-}
skip_build=${CAMBRIDGE_SKIP_BUILD:-OFF}

fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

[[ -n "${application_identity}" ]] || \
    fail "CAMBRIDGE_DEVELOPER_ID_APPLICATION is required for release signing"
[[ -n "${installer_identity}" ]] || \
    fail "CAMBRIDGE_DEVELOPER_ID_INSTALLER is required for release signing"
[[ -n "${notary_profile}" ]] || \
    fail "CAMBRIDGE_NOTARY_PROFILE is required for notarization"
command -v pkgbuild >/dev/null 2>&1 || fail "pkgbuild is required"
command -v productbuild >/dev/null 2>&1 || fail "productbuild is required"
command -v codesign >/dev/null 2>&1 || fail "codesign is required"
command -v lipo >/dev/null 2>&1 || fail "lipo is required"
command -v otool >/dev/null 2>&1 || fail "otool is required"
command -v xcrun >/dev/null 2>&1 || fail "Xcode xcrun is required"
command -v pkgutil >/dev/null 2>&1 || fail "pkgutil is required"
command -v spctl >/dev/null 2>&1 || fail "spctl is required"
command -v shasum >/dev/null 2>&1 || fail "shasum is required"
case "${skip_build}" in
    ON|OFF) ;;
    *) fail "CAMBRIDGE_SKIP_BUILD must be ON or OFF" ;;
esac

mkdir -p "${artifact_dir}"
rm -rf "${package_root}"
rm -f "${component_path}" "${package_path}" "${package_path}.sha256"

if [[ "${skip_build}" == "OFF" ]]; then
    CAMBRIDGE_BUILD_DIR="${build_dir}" \
    CAMBRIDGE_STAGING_DIR="${staging_dir}" \
    CAMBRIDGE_BUILD_TYPE=Release \
        "${repo_root}/scripts/receiver/macos/build-cambridge-obs-plugin.sh"
fi

[[ -f "${plugin_path}" ]] || fail "universal plugin was not staged: ${plugin_path}"
[[ -f "${metallib_path}" ]] || fail "Metal library was not staged: ${metallib_path}"
lipo -verify_arch arm64 x86_64 "${plugin_path}" || fail "plugin is not universal"
if otool -L "${plugin_path}" | rg -q '/opt/homebrew|/usr/local/opt|/usr/local/Cellar|/opt/homebrew/Cellar'; then
    fail "plugin contains an unintended Homebrew load command"
fi

package_plugin_dir="${package_root}/Library/Application Support/obs-studio/plugins/cambridge-obs-plugin/bin/64bit"
mkdir -p "${package_plugin_dir}"
cp "${plugin_path}" "${package_plugin_dir}/cambridge-obs-plugin.so"
cp "${metallib_path}" "${package_plugin_dir}/nv12_to_bgra.metallib"
codesign --force --options runtime --timestamp --sign "${application_identity}" \
    "${package_plugin_dir}/cambridge-obs-plugin.so"
codesign --verify --strict --verbose=2 "${package_plugin_dir}/cambridge-obs-plugin.so"

pkgbuild \
    --root "${package_root}" \
    --identifier "com.cambridge.obs-plugin" \
    --version "${version}" \
    --install-location / \
    --sign "${installer_identity}" \
    "${component_path}"
productbuild \
    --package "${component_path}" \
    --sign "${installer_identity}" \
    "${package_path}"
xcrun notarytool submit "${package_path}" \
    --keychain-profile "${notary_profile}" \
    --wait \
    --output-format json >"${artifact_dir}/notary-${version}.json"
xcrun stapler staple "${package_path}"
spctl --assess --type install --verbose=4 "${package_path}"
pkgutil --check-signature "${package_path}"

printf 'package=%s\n' "${package_path}"
printf 'version=%s\n' "${version}"
printf 'architectures='
lipo -archs "${plugin_path}"
printf 'sha256='
shasum -a 256 "${package_path}" | awk '{print $1}' | tee "${package_path}.sha256"
