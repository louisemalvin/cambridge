#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
project_path="${repo_root}/sender/ios/CamBridge.xcodeproj"
scheme_name="CamBridge"
log_dir="${repo_root}/build/ios-xcode-check"
mkdir -p "${log_dir}"

report_failure() {
    local status=$?
    if (( status != 0 )); then
        echo "Xcode check failed; retained logs: ${log_dir}" >&2
    fi
    exit "${status}"
}
trap report_failure EXIT

if ! command -v xcodebuild >/dev/null 2>&1 || ! command -v xcrun >/dev/null 2>&1; then
    echo "check-xcode.sh requires macOS Xcode and is unavailable on this host" >&2
    exit 2
fi

developer_directory=$(xcode-select -p)
if [[ ! -d "${developer_directory}" ]]; then
    echo "Selected Xcode developer directory does not exist: ${developer_directory}" >&2
    exit 2
fi
echo "Using Xcode developer directory: ${developer_directory}"
xcodebuild -version

simulator_architecture=$(uname -m)
case "${simulator_architecture}" in
    arm64|x86_64) ;;
    *)
        echo "Unsupported simulator host architecture: ${simulator_architecture}" >&2
        exit 2
        ;;
esac

destination_id=$(xcrun simctl list devices available -j | python3 -c '
import json
import sys

devices = json.load(sys.stdin).get("devices", {})
candidates = []
for runtime, runtime_devices in devices.items():
    if "iOS" not in runtime or "Simulator" not in runtime:
        continue
    for device in runtime_devices:
        if device.get("isAvailable") and device.get("name", "").startswith("iPhone"):
            candidates.append((device["name"], device["udid"]))
if not candidates:
    raise SystemExit("No available iPhone simulator found")
candidates.sort()
print(candidates[0][1])
')

xcodebuild \
    -project "${project_path}" \
    -scheme "${scheme_name}" \
    -destination "platform=iOS Simulator,arch=${simulator_architecture},id=${destination_id}" \
    -derivedDataPath "${repo_root}/build/ios-derived-data" \
    ARCHS="${simulator_architecture}" \
    ONLY_ACTIVE_ARCH=YES \
    SWIFT_ENABLE_TESTABILITY=YES \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    test 2>&1 | tee "${log_dir}/xcodebuild-test.log"

app_bundle="${repo_root}/build/ios-derived-data/Build/Products/Debug-iphonesimulator/CamBridge.app"
privacy_manifest="${app_bundle}/PrivacyInfo.xcprivacy"
if [[ ! -f "${privacy_manifest}" ]]; then
    echo "Built app is missing target privacy manifest: ${privacy_manifest}" >&2
    exit 2
fi
python3 - "${privacy_manifest}" <<'PY'
import plistlib
import sys

manifest_path = sys.argv[1]
with open(manifest_path, "rb") as manifest_file:
    manifest = plistlib.load(manifest_file)

required_api_types = manifest.get("NSPrivacyAccessedAPITypes", [])
user_defaults_entries = [
    entry for entry in required_api_types
    if entry.get("NSPrivacyAccessedAPIType") == "NSPrivacyAccessedAPICategoryUserDefaults"
]
if not any("CA92.1" in entry.get("NSPrivacyAccessedAPITypeReasons", []) for entry in user_defaults_entries):
    raise SystemExit("PrivacyInfo.xcprivacy does not declare UserDefaults reason CA92.1")
print(f"Verified target privacy manifest: {manifest_path}")
PY
