#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

cargo fmt --manifest-path desktop/Cargo.toml --all -- --check
cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings
cargo test --manifest-path desktop/Cargo.toml --workspace
cargo build --manifest-path desktop/Cargo.toml --workspace

if [[ -x android/gradlew ]]; then
  android/gradlew -p android test lint assembleDebug
elif command -v gradle >/dev/null 2>&1; then
  gradle -p android test lint assembleDebug
else
  echo "Android checks skipped: no Gradle wrapper or gradle executable is available."
fi
