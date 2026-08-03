#!/usr/bin/env bash
set -euo pipefail

VIDEO_NUMBER="${1:-10}"
DEVICE="/dev/video${VIDEO_NUMBER}"
MIN_SUPPORTED_VERSION="0.15.0"

is_loopback_device() {
  local entry="/sys/class/video4linux/video${VIDEO_NUMBER}"
  local driver
  local sysfs_target
  driver="$(readlink -f "${entry}/device/driver" 2>/dev/null || true)"
  sysfs_target="$(readlink -f "${entry}" 2>/dev/null || true)"
  [[ "${driver}" == */v4l2loopback ]] || \
    { [[ "${sysfs_target}" == /sys/devices/virtual/video4linux/video* ]] && [[ -e /sys/module/v4l2loopback ]]; }
}

if [[ "$(id -u)" -eq 0 ]]; then
  echo "Run this validation as your normal user so permission guidance remains visible."
fi

if ! command -v modinfo >/dev/null 2>&1; then
  echo "modinfo is unavailable. Install the distribution package that provides kmod." >&2
  exit 1
fi

if ! modinfo v4l2loopback >/dev/null 2>&1; then
  cat >&2 <<EOF
v4l2loopback is not installed.

Install the distribution package, then retry. Do not unload unrelated video
modules. On Secure Boot systems, a signed kernel module or Secure Boot policy
change may be required by your distribution.
EOF
  exit 1
fi

MODULE_VERSION="$(modinfo -F version v4l2loopback 2>/dev/null || true)"
if [[ -z "${MODULE_VERSION}" ]]; then
  echo "Could not determine the installed v4l2loopback version." >&2
  exit 1
fi
if [[ "${MODULE_VERSION}" != 0.* ]] || [[ "${MODULE_VERSION}" < "${MIN_SUPPORTED_VERSION}" ]]; then
  echo "v4l2loopback ${MODULE_VERSION} is unsupported; install ${MIN_SUPPORTED_VERSION} or newer for client-usage events." >&2
  exit 1
fi

if [[ -e /sys/module/v4l2loopback ]]; then
  if [[ -e "$DEVICE" ]]; then
    if is_loopback_device; then
      echo "v4l2loopback is loaded and ${DEVICE} is attached."
      exit 0
    fi
    DRIVER="$(readlink -f "/sys/class/video4linux/video${VIDEO_NUMBER}/device/driver" 2>/dev/null || true)"
    echo "${DEVICE} exists but is owned by another driver: ${DRIVER:-unknown}" >&2
    exit 1
  fi
  cat >&2 <<EOF
v4l2loopback is loaded, but ${DEVICE} does not exist.

Safe corrective command to inspect the current module configuration:
  grep -H . /sys/module/v4l2loopback/parameters/*

If no loopback device was created, run this explicit command after reviewing
your current devices:
  sudo modprobe v4l2loopback devices=1 video_nr=${VIDEO_NUMBER} card_label="Mobile Webcam" exclusive_caps=1

This script never unloads a module that may be in use.
EOF
  exit 1
fi

cat >&2 <<EOF
v4l2loopback is installed but not loaded.

Run this manual command:
  sudo modprobe v4l2loopback devices=1 video_nr=${VIDEO_NUMBER} card_label="Mobile Webcam" exclusive_caps=1

If modprobe reports a signing or Secure Boot error, install a distribution
signed module or enroll the required signing key. The receiver will not load
kernel modules or change Secure Boot settings automatically.
EOF
exit 1
