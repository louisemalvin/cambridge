#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
architecture=${CAMBRIDGE_MACOS_ARCHITECTURE:-}
readonly download_retry_limit=3
readonly download_retry_delay_seconds=2
if [[ -z "${architecture}" ]]; then
    printf 'error: CAMBRIDGE_MACOS_ARCHITECTURE must be arm64 or x86_64\n' >&2
    exit 1
fi
case "${architecture}" in
    arm64|x86_64) ;;
    *) printf 'error: unsupported macOS architecture: %s\n' "${architecture}" >&2; exit 1 ;;
esac

command -v brew >/dev/null 2>&1 || { printf 'error: Homebrew is required for build tools\n' >&2; exit 1; }
brew install cmake pkg-config jq yasm nasm jansson simde uthash

buildspec="${repo_root}/receiver/obs/cambridge-obs-source/buildspec.json"
temp_root=${CAMBRIDGE_DEPENDENCY_BUILD_ROOT:-${RUNNER_TEMP:-}}
if [[ -z "${temp_root}" ]]; then
    temp_root=$(mktemp -d "${repo_root}/build/cambridge-macos-dependencies.XXXXXX")
else
    mkdir -p "${temp_root}"
fi
build_jobs=$(sysctl -n hw.ncpu)
simde_prefix=$(brew --prefix simde)
uthash_prefix=$(brew --prefix uthash)
jansson_prefix=$(brew --prefix jansson)

ffmpeg_version=$(jq -er '.baseline.ffmpeg' "${buildspec}")
ffmpeg_url=$(jq -er '.dependencies[] | select(.name == "ffmpeg") | .url' "${buildspec}")
ffmpeg_hash=$(jq -er '.dependencies[] | select(.name == "ffmpeg") | .sha256' "${buildspec}")
ffmpeg_archive="${temp_root}/ffmpeg-${ffmpeg_version}.tar.xz"
ffmpeg_source="${temp_root}/ffmpeg-${ffmpeg_version}"
ffmpeg_prefix="${temp_root}/ffmpeg-prefix-${architecture}"
curl --fail --location --retry "${download_retry_limit}" \
    --retry-delay "${download_retry_delay_seconds}" --retry-all-errors \
    "${ffmpeg_url}" --output "${ffmpeg_archive}"
echo "${ffmpeg_hash}  ${ffmpeg_archive}" | shasum -a 256 -c -
tar -xf "${ffmpeg_archive}" -C "${temp_root}"
(
    cd "${ffmpeg_source}"
    ./configure \
        --prefix="${ffmpeg_prefix}" \
        --arch="${architecture}" \
        --target-os=darwin \
        --cc=clang \
        --disable-programs \
        --disable-doc \
        --disable-debug \
        --disable-static \
        --enable-shared \
        --enable-pic \
        --enable-avcodec \
        --enable-avutil \
        --enable-swscale \
        --enable-decoder=h264 \
        --enable-parser=h264 \
        --enable-videotoolbox \
        --enable-hwaccel=h264_videotoolbox \
        --install-name-dir='@rpath'
    make -j"${build_jobs}"
    make install
)

obs_version=$(jq -er '.baseline.obsStudio' "${buildspec}")
obs_url=$(jq -er '.dependencies[] | select(.name == "obs-studio") | .url' "${buildspec}")
obs_hash=$(jq -er '.dependencies[] | select(.name == "obs-studio") | .sha256' "${buildspec}")
obs_archive="${temp_root}/obs-${obs_version}.tar.gz"
obs_source="${temp_root}/obs-studio-${obs_version}-sources"
obs_build="${temp_root}/obs-build-${architecture}"
obs_prefix="${temp_root}/obs-prefix-${architecture}"
curl --fail --location --retry "${download_retry_limit}" \
    --retry-delay "${download_retry_delay_seconds}" --retry-all-errors \
    "${obs_url}" --output "${obs_archive}"
echo "${obs_hash}  ${obs_archive}" | shasum -a 256 -c -
tar -xf "${obs_archive}" -C "${temp_root}"
cmake -S "${obs_source}" -B "${obs_build}" -G Xcode \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$(jq -er '.baseline.macosDeploymentTarget' "${buildspec}")" \
    -DCMAKE_OSX_ARCHITECTURES="${architecture}" \
    -DCMAKE_PREFIX_PATH="${simde_prefix};${uthash_prefix};${jansson_prefix}" \
    -DCMAKE_INSTALL_PREFIX="${obs_prefix}" \
    -DENABLE_UI=OFF \
    -DENABLE_SCRIPTING=OFF \
    -DENABLE_PLUGINS=OFF \
    -DENABLE_HEVC=OFF
cmake --build "${obs_build}" --config Release --target libobs --parallel
cmake --install "${obs_build}" --config Release

obs_pc_dir="${temp_root}/cambridge-pkgconfig-${architecture}"
mkdir -p "${obs_pc_dir}"
cat >"${obs_pc_dir}/libobs.pc" <<EOF
prefix=${obs_prefix}
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=${obs_source}/libobs
Name: libobs
Description: OBS Studio libobs pinned CamBridge build baseline
Version: ${obs_version}
Cflags: -I\${includedir} -I${obs_build}/config
Libs: -L\${libdir} -lobs
EOF

export_lines=(
    "CAMBRIDGE_FFMPEG_PREFIX=${ffmpeg_prefix}"
    "CAMBRIDGE_OBS_PREFIX=${obs_prefix}"
    "CMAKE_PREFIX_PATH=${obs_prefix};${ffmpeg_prefix};${simde_prefix};${uthash_prefix};${jansson_prefix}"
    "PKG_CONFIG_PATH=${obs_pc_dir}:${ffmpeg_prefix}/lib/pkgconfig:${simde_prefix}/lib/pkgconfig:${uthash_prefix}/lib/pkgconfig:${jansson_prefix}/lib/pkgconfig"
)
if [[ -n "${GITHUB_ENV:-}" ]]; then
    for export_line in "${export_lines[@]}"; do
        printf '%s\n' "${export_line}" >>"${GITHUB_ENV}"
    done
else
    printf '%s\n' "${export_lines[@]}"
fi
