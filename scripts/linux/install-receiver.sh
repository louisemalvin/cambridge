#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
MODULE_NAME="v4l2loopback"
VIDEO_NUMBER="10"
MODULE_LOAD_FILE="/etc/modules-load.d/mobile-webcam.conf"
MODULE_OPTIONS_FILE="/etc/modprobe.d/mobile-webcam.conf"
MODULE_OPTIONS="options ${MODULE_NAME} devices=1 video_nr=${VIDEO_NUMBER} card_label=\"Mobile Webcam\" exclusive_caps=1"
INSTALLED_BINARY="/usr/local/bin/mobile-webcam-receiver"
INSTALLED_DESKTOP_BINARY="/usr/local/bin/mobile-webcam-desktop"
DESKTOP_ENTRY_TARGET="/usr/local/share/applications/mobile-webcam.desktop"
MEDIA_PORT_RANGE="50000:50099"
MIN_SUPPORTED_V4L2LOOPBACK_VERSION="0.15.0"

declare -a PRIVILEGE=()

fail() {
  echo "Error: $*" >&2
  exit 1
}

if [[ "${EUID}" -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || fail "sudo is required to install system packages and configure v4l2loopback."
  PRIVILEGE=(sudo)
fi

[[ -f "${REPO_ROOT}/desktop/Cargo.toml" ]] || fail "run this script from a checked-out project tree."

install_packages() {
  if ! [[ -r /etc/os-release ]]; then
    fail "cannot identify the Linux distribution from /etc/os-release."
  fi

  # shellcheck disable=SC1091
  source /etc/os-release
  local distro_id="${ID:-unknown}"
  local distro_like="${ID_LIKE:-}"

  case "${distro_id}:${distro_like}" in
    cachyos:*|arch:*|*:arch*)
      command -v pacman >/dev/null 2>&1 || fail "pacman is not available on this Arch-compatible system."
      "${PRIVILEGE[@]}" pacman -S --needed \
        base-devel pkgconf rust \
        gtk4 \
        gstreamer gst-plugins-base gst-plugins-good gst-plugins-bad \
        gst-plugins-ugly gst-libav v4l-utils v4l2loopback-dkms
      ;;
    ubuntu:*|debian:*|*:debian*)
      command -v apt-get >/dev/null 2>&1 || fail "apt-get is not available on this Debian-compatible system."
      "${PRIVILEGE[@]}" apt-get update
      local apt_packages=(
        build-essential pkg-config cargo rustc
        libgtk-4-dev
        libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev
        gstreamer1.0-tools gstreamer1.0-plugins-base
        gstreamer1.0-plugins-good gstreamer1.0-plugins-bad
        gstreamer1.0-plugins-ugly gstreamer1.0-libav
        v4l-utils v4l2loopback-dkms
      )
      local kernel_headers="linux-headers-$(uname -r)"
      if apt-cache show "${kernel_headers}" >/dev/null 2>&1; then
        apt_packages+=("${kernel_headers}")
      fi
      "${PRIVILEGE[@]}" apt-get install -y "${apt_packages[@]}"
      ;;
    *)
      fail "unsupported Linux distribution '${distro_id}'. Supported families are Arch/CachyOS and Ubuntu/Debian."
      ;;
  esac
}

write_managed_file() {
  local target="$1"
  local content="$2"
  local temporary_file

  if [[ -L "${target}" ]]; then
    fail "refusing to replace symlinked system file ${target}; resolve it manually."
  fi
  if [[ -e "${target}" ]]; then
    temporary_file="$(mktemp)"
    printf '%s\n' "${content}" >"${temporary_file}"
    if ! cmp -s "${temporary_file}" "${target}"; then
      rm -f "${temporary_file}"
      fail "${target} already exists with different contents; review it before rerunning this installer."
    fi
    rm -f "${temporary_file}"
    return
  fi

  temporary_file="$(mktemp)"
  printf '%s\n' "${content}" >"${temporary_file}"
  "${PRIVILEGE[@]}" install -o root -g root -m 0644 "${temporary_file}" "${target}"
  rm -f "${temporary_file}"
}

check_module_configuration_conflicts() {
  shopt -s nullglob
  local config_file
  for config_file in /etc/modprobe.d/*.conf; do
    [[ "${config_file}" == "${MODULE_OPTIONS_FILE}" ]] && continue
    if grep -Eq '^[[:space:]]*options[[:space:]]+v4l2loopback([[:space:]]|$)' "${config_file}"; then
      fail "${config_file} already configures v4l2loopback; review it before using this installer."
    fi
  done
}

find_loopback_device() {
  shopt -s nullglob
  local entry
  local driver
  local sysfs_target
  for entry in /sys/class/video4linux/video*; do
    driver="$(readlink -f "${entry}/device/driver" 2>/dev/null || true)"
    sysfs_target="$(readlink -f "${entry}" 2>/dev/null || true)"
    if [[ "${driver}" == */v4l2loopback ]] || \
      { [[ "${sysfs_target}" == /sys/devices/virtual/video4linux/video* ]] && [[ -e "/sys/module/${MODULE_NAME}" ]]; }; then
      printf '/dev/%s\n' "${entry##*/}"
      return 0
    fi
  done
  return 1
}

verify_gstreamer() {
  command -v gst-inspect-1.0 >/dev/null 2>&1 || fail "gst-inspect-1.0 is unavailable after package installation."

  local missing=()
  local element
  for element in udpsrc tsparse tsdemux h264parse h265parse decodebin videorate v4l2sink appsink; do
    if ! gst-inspect-1.0 "${element}" >/dev/null 2>&1; then
      missing+=("${element}")
    fi
  done
  if ((${#missing[@]} > 0)); then
    fail "GStreamer is missing receiver elements: ${missing[*]}"
  fi
}

configure_firewall() {
  command -v ufw >/dev/null 2>&1 || return
  "${PRIVILEGE[@]}" ufw status | grep -q '^Status: active' || return

  local default_interface
  local trusted_subnet
  default_interface="$(ip -4 route show default | awk '{ for (field = 1; field <= NF; field++) if ($field == "dev") { print $(field + 1); exit } }')"
  [[ -n "${default_interface}" ]] || fail "UFW is active but the default network interface could not be identified."
  trusted_subnet="$(ip -4 route show dev "${default_interface}" scope link | awk '$1 ~ /^[0-9]+\./ && $1 ~ /\// { print $1; exit }')"
  [[ -n "${trusted_subnet}" ]] || fail "UFW is active but the trusted local subnet could not be identified."

  "${PRIVILEGE[@]}" ufw allow from "${trusted_subnet}" to any port 5001 proto tcp comment 'Mobile Webcam control'
  "${PRIVILEGE[@]}" ufw allow from "${trusted_subnet}" to any port "${MEDIA_PORT_RANGE}" proto udp comment 'Mobile Webcam media'
}

install_packages
MODULE_VERSION="$(modinfo -F version v4l2loopback 2>/dev/null || true)"
[[ -n "${MODULE_VERSION}" ]] || fail "could not determine the installed v4l2loopback version."
if [[ "${MODULE_VERSION}" != 0.* ]] || [[ "${MODULE_VERSION}" < "${MIN_SUPPORTED_V4L2LOOPBACK_VERSION}" ]]; then
  fail "v4l2loopback ${MODULE_VERSION} is unsupported; install ${MIN_SUPPORTED_V4L2LOOPBACK_VERSION} or newer for client-usage events."
fi
check_module_configuration_conflicts
write_managed_file "${MODULE_LOAD_FILE}" "${MODULE_NAME}"
write_managed_file "${MODULE_OPTIONS_FILE}" "${MODULE_OPTIONS}"

if [[ ! -e "/sys/module/${MODULE_NAME}" ]]; then
  if ! "${PRIVILEGE[@]}" modprobe "${MODULE_NAME}"; then
    cat >&2 <<EOF
The v4l2loopback module could not be loaded.

If the kernel rejected the module, check the DKMS build and Secure Boot policy.
The installer does not unload modules or change Secure Boot settings.
EOF
    exit 1
  fi
fi

if command -v udevadm >/dev/null 2>&1; then
  "${PRIVILEGE[@]}" udevadm settle || true
fi

LOOPBACK_DEVICE="$(find_loopback_device || true)"
[[ -n "${LOOPBACK_DEVICE}" ]] || fail "v4l2loopback loaded but did not create a video device; inspect /sys/module/v4l2loopback/parameters and existing modprobe configuration."
[[ -c "${LOOPBACK_DEVICE}" ]] || fail "v4l2loopback reported ${LOOPBACK_DEVICE}, but the device node is unavailable."

verify_gstreamer
configure_firewall
command -v cargo >/dev/null 2>&1 || fail "cargo is unavailable after package installation."

echo "Building the receiver..."
cargo build --manifest-path "${REPO_ROOT}/desktop/Cargo.toml" --release \
  -p receiver-cli -p receiver-desktop
"${PRIVILEGE[@]}" install -o root -g root -m 0755 \
  "${REPO_ROOT}/desktop/target/release/mobile-webcam-receiver" "${INSTALLED_BINARY}"
"${PRIVILEGE[@]}" install -o root -g root -m 0755 \
  "${REPO_ROOT}/desktop/target/release/mobile-webcam-desktop" "${INSTALLED_DESKTOP_BINARY}"
"${PRIVILEGE[@]}" install -d -o root -g root -m 0755 /usr/local/share/applications
write_managed_file "${DESKTOP_ENTRY_TARGET}" "$(<"${REPO_ROOT}/desktop/mobile-webcam.desktop")"

cat <<EOF

Mobile Webcam receiver setup is complete.

Virtual camera: ${LOOPBACK_DEVICE}
Receiver command: ${INSTALLED_BINARY}
Desktop application: ${INSTALLED_DESKTOP_BINARY}

Start the desktop receiver any time with:
  mobile-webcam-desktop

The headless receiver is also available with:
  mobile-webcam-receiver

The repository wrapper remains available for local development:
  ${REPO_ROOT}/scripts/linux/start-receiver.sh

The receiver automatically selects the first v4l2loopback device and discovers
Android phones through their local control service. It listens on TCP 5001 for
control and allocates a new UDP media port in 50000-50099 for each session.
EOF
