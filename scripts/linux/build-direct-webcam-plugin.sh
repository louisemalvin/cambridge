#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
build_dir=${DIRECT_WEBCAM_BUILD_DIR:-"${repo_root}/build/direct-webcam-source"}
staging_dir=${DIRECT_WEBCAM_STAGING_DIR:-"${build_dir}/staging"}
build_type=${DIRECT_WEBCAM_BUILD_TYPE:-RelWithDebInfo}
git_commit=$(git -C "${repo_root}" rev-parse HEAD)
if [[ -n "$(git -C "${repo_root}" status --porcelain --untracked-files=all)" ]]; then
    git_commit="${git_commit}-dirty"
fi

cmake -S "${repo_root}/desktop/hosts/obs/direct-webcam-source" -B "${build_dir}" \
    -DCMAKE_BUILD_TYPE="${build_type}" \
    -DDIRECT_WEBCAM_GIT_COMMIT="${git_commit}" \
    -DCMAKE_INSTALL_PREFIX="${staging_dir}"
cmake --build "${build_dir}" --parallel
ctest --test-dir "${build_dir}" --output-on-failure
cmake --install "${build_dir}"

artifact="${build_dir}/direct-webcam-source.so"
printf 'module=%s\n' "${artifact}"
printf 'commit=%s\n' "${git_commit}"
printf 'sha256='; sha256sum "${artifact}" | awk '{print $1}'
printf 'staging=%s\n' "${staging_dir}"
