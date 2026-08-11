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
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    test 2>&1 | tee "${log_dir}/xcodebuild-test.log"
