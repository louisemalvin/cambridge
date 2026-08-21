#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
version=$(python3 "${repo_root}/scripts/development/cambridge_version.py")
artifact_dir=${CAMBRIDGE_RELEASE_ARTIFACT_DIR:-"${repo_root}/build/release"}
build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin-macos-release"}
staging_dir=${CAMBRIDGE_STAGING_DIR:-"${build_dir}/staging"}
package_name="cambridge-obs-plugin-${version}-macos-universal.pkg"
package_path="${artifact_dir}/${package_name}"
component_path="${artifact_dir}/cambridge-obs-plugin-${version}-component.pkg"
package_root="${artifact_dir}/cambridge-obs-plugin-${version}-macos-root"
plugin_bundle="${staging_dir}/obs-plugins/cambridge-obs-plugin.plugin"
plugin_path="${plugin_bundle}/Contents/MacOS/cambridge-obs-plugin"
metallib_path="${plugin_bundle}/Contents/Resources/nv12_to_bgra.metallib"
info_plist="${plugin_bundle}/Contents/Info.plist"
application_identity=${CAMBRIDGE_DEVELOPER_ID_APPLICATION:-}
installer_identity=${CAMBRIDGE_DEVELOPER_ID_INSTALLER:-}
notary_profile=${CAMBRIDGE_NOTARY_PROFILE:-}
notary_keychain=${CAMBRIDGE_NOTARY_KEYCHAIN:-}
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
command -v plutil >/dev/null 2>&1 || fail "plutil is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
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

[[ -d "${plugin_bundle}" ]] || fail "universal plugin bundle was not staged: ${plugin_bundle}"
[[ -f "${plugin_path}" ]] || fail "universal plugin binary was not staged: ${plugin_path}"
[[ -f "${metallib_path}" ]] || fail "Metal library was not staged: ${metallib_path}"
[[ -f "${info_plist}" ]] || fail "plugin Info.plist was not staged: ${info_plist}"
plutil -lint "${info_plist}" >/dev/null
lipo -verify_arch arm64 x86_64 "${plugin_path}" || fail "plugin is not universal"
if otool -L "${plugin_path}" | rg -q '/opt/homebrew|/usr/local/opt|/usr/local/Cellar|/opt/homebrew/Cellar'; then
    fail "plugin contains an unintended Homebrew load command"
fi

package_plugin_dir="${package_root}/Library/Application Support/obs-studio/plugins"
mkdir -p "${package_plugin_dir}"
cp -R "${plugin_bundle}" "${package_plugin_dir}/cambridge-obs-plugin.plugin"
package_plugin_bundle="${package_plugin_dir}/cambridge-obs-plugin.plugin"
codesign --force --options runtime --timestamp --sign "${application_identity}" \
    "${package_plugin_bundle}"
codesign --verify --strict --verbose=2 "${package_plugin_bundle}"

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
notary_arguments=(--keychain-profile "${notary_profile}")
if [[ -n "${notary_keychain}" ]]; then
    notary_arguments+=(--keychain "${notary_keychain}")
fi
xcrun notarytool submit "${package_path}" "${notary_arguments[@]}" \
    --wait --output-format json >"${artifact_dir}/notary-${version}.json"
jq -e '.status == "Accepted"' "${artifact_dir}/notary-${version}.json" >/dev/null \
    || fail "Apple notarization did not return Accepted"
xcrun stapler staple "${package_path}"
xcrun stapler validate "${package_path}"
spctl --assess --type install --verbose=4 "${package_path}"
pkgutil --check-signature "${package_path}"

printf 'package=%s\n' "${package_path}"
printf 'version=%s\n' "${version}"
printf 'architectures='
lipo -archs "${plugin_path}"
printf 'sha256='
shasum -a 256 "${package_path}" | awk '{print $1}' | tee "${package_path}.sha256"
