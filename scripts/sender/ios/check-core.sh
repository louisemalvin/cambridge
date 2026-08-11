#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
package_path="${repo_root}/sender/ios/Packages/CamBridgeCore"
swift_container_image="swift:6.0.3-jammy"
log_dir="${repo_root}/build/ios-core-check"
mkdir -p "${log_dir}"

report_failure() {
    local status=$?
    if (( status != 0 )); then
        echo "CamBridgeCore check failed; retained logs: ${log_dir}" >&2
    fi
    exit "${status}"
}
trap report_failure EXIT

run_swift() {
    if command -v swift >/dev/null 2>&1; then
        (
            cd "${package_path}"
            swift "$@"
        )
        return
    fi
    if ! command -v docker >/dev/null 2>&1; then
        echo "Swift 6 or Docker is required for CamBridgeCore checks" >&2
        return 1
    fi
    docker run --rm \
        --mount "type=bind,source=${repo_root},target=/workspace" \
        --workdir /workspace/sender/ios/Packages/CamBridgeCore \
        "${swift_container_image}" \
        swift "$@"
}

python3 "${repo_root}/scripts/development/generate-cambridge-swift-contract.py" --check
python3 "${repo_root}/scripts/development/generate-cambridge-sender-modes.py" --check
python3 "${repo_root}/scripts/development/generate-ios-version.py" --check
run_swift package dump-package >/dev/null
run_swift test --parallel 2>&1 | tee "${log_dir}/swift-test.log"
