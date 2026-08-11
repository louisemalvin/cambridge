#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
architecture=${CAMBRIDGE_MACOS_ARCHITECTURE:-}
readonly download_retry_limit=3
readonly download_retry_delay_seconds=2
readonly ffmpeg_binary_name=ffmpeg
readonly ffmpeg_library_names=(libavcodec libavformat libavutil libswresample libswscale)
if [[ -z "${architecture}" ]]; then
    printf 'error: CAMBRIDGE_MACOS_ARCHITECTURE must be arm64 or x86_64\n' >&2
    exit 1
fi
case "${architecture}" in
    arm64|x86_64) ;;
    *) printf 'error: unsupported macOS architecture: %s\n' "${architecture}" >&2; exit 1 ;;
esac

command -v brew >/dev/null 2>&1 || { printf 'error: Homebrew is required for build tools\n' >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { printf 'error: curl is required\n' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { printf 'error: jq is required\n' >&2; exit 1; }
command -v lipo >/dev/null 2>&1 || { printf 'error: lipo is required\n' >&2; exit 1; }
command -v shasum >/dev/null 2>&1 || { printf 'error: shasum is required\n' >&2; exit 1; }
brew install pkg-config jq yasm nasm jansson simde uthash

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

download_and_verify_dependency() {
    local dependency_name=$1
    local archive_path=$2
    local dependency_url
    local dependency_hash

    dependency_url=$(jq -er --arg name "${dependency_name}" \
        '.dependencies[] | select(.name == $name) | .url' "${buildspec}")
    dependency_hash=$(jq -er --arg name "${dependency_name}" \
        '.dependencies[] | select(.name == $name) | .sha256' "${buildspec}")
    curl --fail --location --retry "${download_retry_limit}" \
        --retry-delay "${download_retry_delay_seconds}" --retry-all-errors \
        "${dependency_url}" --output "${archive_path}"
    printf '%s  %s\n' "${dependency_hash}" "${archive_path}" | shasum -a 256 -c -
}

cmake_version=$(jq -er '.baseline.cmake' "${buildspec}")
cmake_archive="${temp_root}/cmake-${cmake_version}-macos-universal.tar.gz"
cmake_root="${temp_root}/cmake-${cmake_version}-macos-universal/CMake.app/Contents"
download_and_verify_dependency cmake "${cmake_archive}"
tar -xf "${cmake_archive}" -C "${temp_root}"
export PATH="${cmake_root}/bin:${PATH}"
cmake --version

ffmpeg_version=$(jq -er '.baseline.ffmpeg' "${buildspec}")
ffmpeg_archive="${temp_root}/ffmpeg-${ffmpeg_version}.tar.xz"
ffmpeg_source="${temp_root}/ffmpeg-${ffmpeg_version}"
ffmpeg_prefix="${temp_root}/ffmpeg-prefix-${architecture}"
download_and_verify_dependency ffmpeg "${ffmpeg_archive}"
tar -xf "${ffmpeg_archive}" -C "${temp_root}"
(
    cd "${ffmpeg_source}"
    ./configure \
        --prefix="${ffmpeg_prefix}" \
        --arch="${architecture}" \
        --target-os=darwin \
        --cc=clang \
        --disable-ffplay \
        --disable-ffprobe \
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
        --enable-encoder=h264_videotoolbox \
        --enable-hwaccel=h264_videotoolbox \
        --install-name-dir='@rpath'
    make -j"${build_jobs}"
    make install
)
ffmpeg_binary="${ffmpeg_prefix}/bin/${ffmpeg_binary_name}"
[[ -x "${ffmpeg_binary}" ]] || {
    printf 'error: pinned FFmpeg executable is missing: %s\n' "${ffmpeg_binary}" >&2
    exit 1
}
ffmpeg_runtime_library_path="${ffmpeg_prefix}/lib${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}"
ffmpeg_version_output=$(DYLD_LIBRARY_PATH="${ffmpeg_runtime_library_path}" \
    "${ffmpeg_binary}" -version)
grep -Fq "ffmpeg version ${ffmpeg_version}" <<<"${ffmpeg_version_output}" || {
    printf 'error: installed FFmpeg executable does not match %s\n' \
        "${ffmpeg_version}" >&2
    exit 1
}
ffmpeg_version_header="${ffmpeg_prefix}/include/libavutil/ffversion.h"
grep -Fq "#define FFMPEG_VERSION \"${ffmpeg_version}\"" "${ffmpeg_version_header}" || {
    printf 'error: installed FFmpeg version does not match %s\n' "${ffmpeg_version}" >&2
    exit 1
}
for ffmpeg_library_name in "${ffmpeg_library_names[@]}"; do
    ffmpeg_library="${ffmpeg_prefix}/lib/${ffmpeg_library_name}.dylib"
    [[ -f "${ffmpeg_library}" ]] || {
        printf 'error: installed FFmpeg library is missing: %s\n' "${ffmpeg_library}" >&2
        exit 1
    }
    lipo "${ffmpeg_library}" -verify_arch "${architecture}" >/dev/null
done

obs_version=$(jq -er '.baseline.obsStudio' "${buildspec}")
obs_archive="${temp_root}/obs-${obs_version}.tar.gz"
obs_source="${temp_root}/obs-studio-${obs_version}-sources"
obs_build="${temp_root}/obs-build-${architecture}"
obs_prefix="${temp_root}/obs-prefix-${architecture}"
download_and_verify_dependency obs-studio "${obs_archive}"
tar -xf "${obs_archive}" -C "${temp_root}"
obs_libobs_entrypoint="${repo_root}/receiver/obs/cambridge-obs-source/cmake/macos/obs-libobs-only.CMakeLists.txt"
[[ -f "${obs_libobs_entrypoint}" ]] || {
    printf 'error: committed OBS libobs-only entry point is missing: %s\n' \
        "${obs_libobs_entrypoint}" >&2
    exit 1
}
cp "${obs_libobs_entrypoint}" "${obs_source}/CMakeLists.txt"

obs_pkg_config_path="${ffmpeg_prefix}/lib/pkgconfig:${simde_prefix}/lib/pkgconfig:${uthash_prefix}/lib/pkgconfig:${jansson_prefix}/lib/pkgconfig"
obs_cmake_prefix_path="${ffmpeg_prefix};${simde_prefix};${uthash_prefix};${jansson_prefix}"
PKG_CONFIG_PATH="${obs_pkg_config_path}" cmake -S "${obs_source}" -B "${obs_build}" -G Xcode \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$(jq -er '.baseline.macosDeploymentTarget' "${buildspec}")" \
    -DCMAKE_OSX_ARCHITECTURES="${architecture}" \
    -DCMAKE_PREFIX_PATH="${obs_cmake_prefix_path}" \
    -DCMAKE_INSTALL_PREFIX="${obs_prefix}" \
    -DOBS_VERSION_OVERRIDE="${obs_version}" \
    -DENABLE_FRONTEND=OFF \
    -DENABLE_UI=OFF \
    -DENABLE_SCRIPTING=OFF \
    -DENABLE_PLUGINS=OFF \
    -DENABLE_HEVC=OFF
PKG_CONFIG_PATH="${obs_pkg_config_path}" cmake --build "${obs_build}" \
    --config Release --target libobs --parallel "${build_jobs}"
PKG_CONFIG_PATH="${obs_pkg_config_path}" cmake --install "${obs_build}" \
    --config Release --component Development

obs_framework="${obs_prefix}/Frameworks/libobs.framework"
obs_framework_binary="${obs_framework}/Versions/A/libobs"
[[ -f "${obs_framework_binary}" ]] || {
    printf 'error: installed libobs framework binary is missing: %s\n' \
        "${obs_framework_binary}" >&2
    exit 1
}
lipo "${obs_framework_binary}" -verify_arch "${architecture}" >/dev/null
printf 'resolved OBS version: %s\n' "${obs_version}"
printf 'resolved FFmpeg version: %s\n' "${ffmpeg_version}"
printf 'libobs architecture: %s\n' "${architecture}"

obs_pc_dir="${temp_root}/cambridge-pkgconfig-${architecture}"
mkdir -p "${obs_pc_dir}"
cat >"${obs_pc_dir}/libobs.pc" <<EOF
prefix=${obs_prefix}
exec_prefix=\${prefix}
frameworkdir=\${prefix}/Frameworks/libobs.framework
libdir=\${frameworkdir}/Versions/A
includedir=\${frameworkdir}/Headers
Name: libobs
Description: OBS Studio libobs pinned CamBridge build baseline
Version: ${obs_version}
Cflags: -F\${prefix}/Frameworks -I\${includedir}
Libs: -L\${libdir} -lobs
EOF

resolved_obs_version=$(PKG_CONFIG_PATH="${obs_pc_dir}:${obs_pkg_config_path}" \
    pkg-config --modversion libobs)
[[ "${resolved_obs_version}" == "${obs_version}" ]] || {
    printf 'error: pkg-config resolved OBS %s; expected %s\n' \
        "${resolved_obs_version}" "${obs_version}" >&2
    exit 1
}
for ffmpeg_library_name in "${ffmpeg_library_names[@]}"; do
    resolved_ffmpeg_component_version=$(PKG_CONFIG_PATH="${obs_pc_dir}:${obs_pkg_config_path}" \
        pkg-config --modversion "${ffmpeg_library_name}")
    printf 'resolved %s pkg-config version: %s\n' \
        "${ffmpeg_library_name}" "${resolved_ffmpeg_component_version}"
done

export_lines=(
    "CAMBRIDGE_FFMPEG_PREFIX=${ffmpeg_prefix}"
    "CAMBRIDGE_FFMPEG_EXECUTABLE=${ffmpeg_binary}"
    "CAMBRIDGE_OBS_PREFIX=${obs_prefix}"
    "CMAKE_PREFIX_PATH=${obs_prefix};${ffmpeg_prefix};${simde_prefix};${uthash_prefix};${jansson_prefix}"
    "PKG_CONFIG_PATH=${obs_pc_dir}:${ffmpeg_prefix}/lib/pkgconfig:${simde_prefix}/lib/pkgconfig:${uthash_prefix}/lib/pkgconfig:${jansson_prefix}/lib/pkgconfig"
    "DYLD_LIBRARY_PATH=${ffmpeg_runtime_library_path}"
)
if [[ -n "${GITHUB_ENV:-}" ]]; then
    for export_line in "${export_lines[@]}"; do
        printf '%s\n' "${export_line}" >>"${GITHUB_ENV}"
    done
fi
if [[ -n "${GITHUB_PATH:-}" ]]; then
    printf '%s\n' "${cmake_root}/bin" "${ffmpeg_prefix}/bin" >>"${GITHUB_PATH}"
fi
if [[ -z "${GITHUB_ENV:-}" || -z "${GITHUB_PATH:-}" ]]; then
    printf 'PATH=%s/bin:%s/bin:${PATH}\n' "${cmake_root}" "${ffmpeg_prefix}"
    printf '%s\n' "${export_lines[@]}"
fi
