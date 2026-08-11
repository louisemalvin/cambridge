#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
fixture_package="${repo_root}/scripts/sender/ios/cambridge-swift-fixture"
swift_container_image="swift:6.0.3-jammy"
log_dir="${repo_root}/build/ios-fixture-check"
mkdir -p "${log_dir}"

report_failure() {
    local status=$?
    if (( status != 0 )); then
        echo "Swift fixture check failed; retained logs: ${log_dir}" >&2
    fi
    exit "${status}"
}
trap report_failure EXIT

run_swift() {
    if command -v swift >/dev/null 2>&1; then
        (
            cd "${fixture_package}"
            swift "$@"
        )
        return
    fi
    if ! command -v docker >/dev/null 2>&1; then
        echo "Swift 6 or Docker is required for the Swift fixture check" >&2
        return 1
    fi
    docker run --rm \
        --mount "type=bind,source=${repo_root},target=/workspace" \
        --workdir /workspace/scripts/sender/ios/cambridge-swift-fixture \
        "${swift_container_image}" \
        swift "$@"
}

run_swift build --disable-sandbox 2>&1 | tee "${log_dir}/swift-build.log"

if [[ "${CAMBRIDGE_RUN_NATIVE_FIXTURE:-0}" == "1" ]]; then
    CAMBRIDGE_SENDER_MODE=swift \
        "${repo_root}/scripts/receiver/linux/test-cambridge-fixture.sh"
fi
