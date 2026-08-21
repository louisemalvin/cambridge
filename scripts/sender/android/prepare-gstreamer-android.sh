#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
buildspec_file="${repo_root}/receiver/obs/cambridge-obs-source/buildspec.json"
download_dir=${CAMBRIDGE_DOWNLOAD_DIR:-"${repo_root}/build/downloads"}
sdk_root=${CAMBRIDGE_GSTREAMER_ANDROID_DIR:-}

command -v curl >/dev/null 2>&1 || {
    printf 'error: curl is required to install the GStreamer Android SDK\n' >&2
    exit 1
}
command -v python3 >/dev/null 2>&1 || {
    printf 'error: python3 is required to read the GStreamer SDK manifest\n' >&2
    exit 1
}
command -v sha256sum >/dev/null 2>&1 || {
    printf 'error: sha256sum is required to verify the GStreamer Android SDK\n' >&2
    exit 1
}
command -v tar >/dev/null 2>&1 || {
    printf 'error: tar is required to extract the GStreamer Android SDK\n' >&2
    exit 1
}

mapfile -t sdk_spec < <(python3 - "${buildspec_file}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as buildspec_file:
    buildspec = json.load(buildspec_file)

dependency = next(
    item for item in buildspec["dependencies"] if item["name"] == "gstreamer-android"
)
print(dependency["version"])
print(dependency["url"])
print(dependency["sha256"])
PY
)
sdk_version=${sdk_spec[0]}
sdk_url=${sdk_spec[1]}
sdk_sha256=${sdk_spec[2]}
archive_name=${sdk_url##*/}
archive_path="${download_dir}/${archive_name}"

mkdir -p "${download_dir}"
if [[ ! -f "${archive_path}" ]] ||
   ! printf '%s  %s\n' "${sdk_sha256}" "${archive_path}" | sha256sum --check --status; then
    temporary_archive="${archive_path}.partial"
    rm -f "${temporary_archive}"
    curl --fail --location --retry 3 --retry-all-errors --silent --show-error \
        "${sdk_url}" --output "${temporary_archive}"
    printf '%s  %s\n' "${sdk_sha256}" "${temporary_archive}" | sha256sum --check --status
    mv "${temporary_archive}" "${archive_path}"
fi

if [[ -n "${sdk_root}" && -e "${sdk_root}" ]]; then
    if [[ ! -f "${sdk_root}/arm64/share/gst-android/ndk-build/gstreamer-1.0.mk" ||
          ! -f "${sdk_root}/x86_64/share/gst-android/ndk-build/gstreamer-1.0.mk" ]]; then
        printf 'error: existing GStreamer Android SDK is incomplete: %s\n' "${sdk_root}" >&2
        exit 1
    fi
else
    sdk_root="${repo_root}/build/gstreamer-android-${sdk_version}"
    if [[ ! -f "${sdk_root}/arm64/share/gst-android/ndk-build/gstreamer-1.0.mk" ||
          ! -f "${sdk_root}/x86_64/share/gst-android/ndk-build/gstreamer-1.0.mk" ]]; then
        extract_dir=$(mktemp -d "${repo_root}/build/gstreamer-android-extract.XXXXXX")
        cleanup() {
            rm -rf "${extract_dir}"
        }
        trap cleanup EXIT
        tar -xJf "${archive_path}" -C "${extract_dir}"
        mapfile -t sdk_roots < <(find "${extract_dir}" -type f \
            -path '*/arm64/share/gst-android/ndk-build/gstreamer-1.0.mk' \
            -printf '%h\n' | sed 's#/arm64/share/gst-android/ndk-build##')
        if [[ "${#sdk_roots[@]}" -ne 1 ]]; then
            printf 'error: could not identify one GStreamer Android SDK root\n' >&2
            exit 1
        fi
        source_root=${sdk_roots[0]}
        if [[ -e "${sdk_root}" ]]; then
            printf 'error: incomplete GStreamer SDK path already exists: %s\n' "${sdk_root}" >&2
            exit 1
        fi
        mv "${source_root}" "${sdk_root}"
    fi
fi

for sdk_architecture in arm64 armv7 x86_64; do
    test -f "${sdk_root}/${sdk_architecture}/share/gst-android/ndk-build/gstreamer-1.0.mk" || {
        printf 'error: GStreamer Android SDK is missing ABI %s: %s\n' \
            "${sdk_architecture}" "${sdk_root}" >&2
        exit 1
    }
done

if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'GSTREAMER_ROOT_ANDROID=%s\n' "${sdk_root}" >>"${GITHUB_ENV}"
else
    printf 'GSTREAMER_ROOT_ANDROID=%s\n' "${sdk_root}"
fi
printf 'GStreamer Android SDK %s is ready at %s\n' "${sdk_version}" "${sdk_root}"
