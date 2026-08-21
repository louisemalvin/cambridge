#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
buildspec_file="${repo_root}/receiver/obs/cambridge-obs-source/buildspec.json"
dependency_dir=${CAMBRIDGE_OBS_DEPENDENCY_DIR:-"${repo_root}/build/cambridge-obs-dependencies"}
source_archive_dir="${dependency_dir}/downloads"
source_root="${dependency_dir}/source"
obs_build_dir="${dependency_dir}/obs-build"
obs_install_dir=${CAMBRIDGE_OBS_PREFIX:-"${dependency_dir}/obs"}
# Keep the temporary development prefix layout stable across Linux runners.
obs_library_dir=lib

command -v cmake >/dev/null 2>&1 || { printf 'error: cmake is required\n' >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { printf 'error: curl is required\n' >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { printf 'error: python3 is required\n' >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { printf 'error: sha256sum is required\n' >&2; exit 1; }
command -v tar >/dev/null 2>&1 || { printf 'error: tar is required\n' >&2; exit 1; }

mapfile -t obs_source_spec < <(python3 - "${buildspec_file}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as buildspec_file:
    buildspec = json.load(buildspec_file)

source = buildspec["linuxCompatibility"]["obsStudioSource"]
print(source["version"])
print(source["url"])
print(source["sha256"])
print(buildspec["linuxCompatibility"]["obsLibrarySoname"])
PY
)
obs_version=${obs_source_spec[0]}
obs_source_url=${obs_source_spec[1]}
obs_source_sha256=${obs_source_spec[2]}
obs_library_soname=${obs_source_spec[3]}
source_archive="${source_archive_dir}/obs-studio-${obs_version}-sources.tar.gz"
obs_source_dir="${source_root}/obs-studio-${obs_version}-sources"

mkdir -p "${source_archive_dir}" "${source_root}"
if [[ ! -f "${source_archive}" ]] ||
   ! printf '%s  %s\n' "${obs_source_sha256}" "${source_archive}" | sha256sum --check --status; then
    temporary_archive="${source_archive}.partial"
    rm -f "${temporary_archive}"
    curl --fail --location --retry 3 --silent --show-error \
        "${obs_source_url}" -o "${temporary_archive}"
    printf '%s  %s\n' "${obs_source_sha256}" "${temporary_archive}" | sha256sum --check --status
    mv "${temporary_archive}" "${source_archive}"
fi

if [[ ! -d "${obs_source_dir}" ]]; then
    tar -xzf "${source_archive}" -C "${source_root}"
fi
[[ -f "${obs_source_dir}/CMakeLists.txt" ]] || {
    printf 'error: OBS source directory is missing: %s\n' "${obs_source_dir}" >&2
    exit 1
}

if command -v ninja >/dev/null 2>&1; then
    cmake_generator=Ninja
else
    cmake_generator="Unix Makefiles"
fi

cmake --fresh -S "${obs_source_dir}" -B "${obs_build_dir}" \
    -G "${cmake_generator}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${obs_install_dir}" \
    -DCMAKE_INSTALL_LIBDIR="${obs_library_dir}" \
    -DOBS_VERSION_OVERRIDE="${obs_version}" \
    -DENABLE_FRONTEND=OFF \
    -DENABLE_SCRIPTING=OFF \
    -DENABLE_HEVC=OFF \
    -DENABLE_PLUGINS=OFF \
    -DENABLE_PULSEAUDIO=OFF \
    -DENABLE_WAYLAND=OFF
cmake --build "${obs_build_dir}" --target libobs obs-frontend-api --parallel
cmake --install "${obs_build_dir}" --component Development

obs_pkg_config_path="${obs_install_dir}/${obs_library_dir}/pkgconfig"
resolved_obs_version=$(PKG_CONFIG_PATH="${obs_pkg_config_path}${PKG_CONFIG_PATH:+:${PKG_CONFIG_PATH}}" \
    pkg-config --modversion libobs)
[[ "${resolved_obs_version}" == "${obs_version}" ]] || {
    printf 'error: prepared OBS version is %s, expected %s\n' \
        "${resolved_obs_version}" "${obs_version}" >&2
    exit 1
}
[[ -f "${obs_install_dir}/include/obs/obs.h" ]] || {
    printf 'error: prepared OBS headers are missing\n' >&2
    exit 1
}
[[ -f "${obs_install_dir}/${obs_library_dir}/${obs_library_soname}" ]] || {
    printf 'error: prepared OBS library is missing\n' >&2
    exit 1
}

printf 'prepared OBS dependencies: version=%s prefix=%s\n' \
    "${resolved_obs_version}" "${obs_install_dir}"
