#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
platform=$(uname -s)

python3 "${repo_root}/scripts/development/cambridge_component_versions.py" --check
python3 "${repo_root}/scripts/development/test-component-versions.py"
python3 "${repo_root}/scripts/development/generate-ios-version.py" --check
python3 "${repo_root}/scripts/development/check-cambridge-stream-contract.py"
python3 "${repo_root}/scripts/release/test-cambridge-linux-bundle.py"
bash -n \
    "${repo_root}/scripts/sender/android/prepare-gstreamer-android.sh" \
    "${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin.sh" \
    "${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin-variant.sh" \
    "${repo_root}/scripts/receiver/linux/check-cambridge-obs-plugin-dependencies.sh" \
    "${repo_root}/scripts/receiver/linux/prepare-cambridge-obs-dependencies.sh" \
    "${repo_root}/scripts/receiver/linux/test-cambridge-fixture.sh" \
    "${repo_root}/scripts/receiver/macos/build-cambridge-obs-plugin.sh" \
    "${repo_root}/scripts/receiver/macos/test-cambridge-fixture.sh" \
    "${repo_root}/scripts/receiver/macos/prepare-cambridge-build-dependencies.sh" \
    "${repo_root}/scripts/release/package-linux-plugin.sh" \
    "${repo_root}/scripts/release/install-linux-plugin.sh" \
    "${repo_root}/scripts/release/package-macos-plugin.sh"
if [[ -z "${GSTREAMER_ROOT_ANDROID:-}" ]]; then
    gstreamer_setup_output=$(
        "${repo_root}/scripts/sender/android/prepare-gstreamer-android.sh"
    )
    GSTREAMER_ROOT_ANDROID=$(printf '%s\n' "${gstreamer_setup_output}" \
        | sed -n 's/^GSTREAMER_ROOT_ANDROID=//p' | tail -n 1)
fi
export GSTREAMER_ROOT_ANDROID
[[ -n "${GSTREAMER_ROOT_ANDROID}" ]] || {
    printf 'error: GStreamer Android SDK path was not resolved\n' >&2
    exit 1
}
JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}" \
    "${repo_root}/sender/android/gradlew" -p "${repo_root}/sender/android" \
    testDebugUnitTest lint assembleDebug compileDebugAndroidTestKotlin --console=plain
case "${platform}" in
    Linux)
        "${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin.sh"
        ldd -r "${repo_root}/build/cambridge-obs-plugin/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
        ;;
    Darwin)
        "${repo_root}/scripts/receiver/macos/build-cambridge-obs-plugin.sh"
        ;;
    *)
        printf 'error: unsupported development host: %s\n' "${platform}" >&2
        exit 1
        ;;
esac
