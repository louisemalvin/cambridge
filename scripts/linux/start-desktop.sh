#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

exec cargo run --manifest-path "${REPO_ROOT}/desktop/Cargo.toml" \
  --release -p receiver-desktop -- "$@"
