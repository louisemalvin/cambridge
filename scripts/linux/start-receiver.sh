#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
RECEIVER_BINARY="${REPO_ROOT}/desktop/target/release/mobile-webcam-receiver"

if [[ ! -x "${RECEIVER_BINARY}" ]]; then
  cat >&2 <<EOF
The receiver is not built yet.

Run this one-time setup command first:
  ${REPO_ROOT}/scripts/linux/install-receiver.sh
EOF
  exit 1
fi

exec "${RECEIVER_BINARY}" "$@"
