#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
build_dir=${CAMBRIDGE_BUILD_DIR:-"${repo_root}/build/cambridge-obs-plugin"}
staging_dir=${CAMBRIDGE_STAGING_DIR:-"${build_dir}/staging"}
build_type=${CAMBRIDGE_BUILD_TYPE:-RelWithDebInfo}
require_avahi=${CAMBRIDGE_REQUIRE_AVAHI:-OFF}
git_commit=$(git -C "${repo_root}" rev-parse HEAD)
if [[ -n "$(git -C "${repo_root}" status --porcelain --untracked-files=all)" ]]; then
    git_commit="${git_commit}-dirty"
fi

cmake --fresh -S "${repo_root}/receiver/obs/cambridge-obs-source" -B "${build_dir}" \
    -DCMAKE_BUILD_TYPE="${build_type}" \
    -DCAMBRIDGE_GIT_COMMIT="${git_commit}" \
    -DCAMBRIDGE_REQUIRE_AVAHI="${require_avahi}" \
    -DCMAKE_INSTALL_PREFIX="${staging_dir}"
cmake --build "${build_dir}" --parallel
ctest --test-dir "${build_dir}" --output-on-failure
cmake --install "${build_dir}"

artifact="${build_dir}/cambridge-obs-plugin.so"
printf 'module=%s\n' "${artifact}"
printf 'commit=%s\n' "${git_commit}"
printf 'sha256='; sha256sum "${artifact}" | awk '{print $1}'
printf 'staging=%s\n' "${staging_dir}"
