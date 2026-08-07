#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)

python3 "${repo_root}/scripts/development/check-cambridge-stream-contract.py"
JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}" \
    "${repo_root}/sender/android/gradlew" -p "${repo_root}/sender/android" \
    testDebugUnitTest lint assembleDebug --console=plain
"${repo_root}/scripts/receiver/linux/build-cambridge-obs-plugin.sh"
ldd -r "${repo_root}/build/cambridge-obs-plugin/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so"
