# Simplify Linux receiver setup and automatic device discovery

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Make normal Linux receiver use simple: a one-time installer prepares required OS packages and the virtual camera, then the receiver starts without requiring users to know the device path or pass a long command.

## Context

The receiver binary must remain unprivileged and must not load kernel modules itself. Existing Linux code validates an explicitly supplied `/dev/video*` path, while the CLI currently defaults to `/dev/video10`. The project already has a manual v4l2loopback setup script and Linux setup documentation.

## Decisions

- Keep privileged package/module work in a separate, idempotent installer script.
- Make `--device` optional and automatically select the first v4l2loopback device when omitted.
- Preserve explicit `--device` for advanced users and diagnostics.
- Keep default control/media ports and low-latency settings unchanged.

## Acceptance Criteria

- A Linux user can run one setup command once, then start the receiver with no device or port arguments.
- The receiver discovers a v4l2loopback device and reports a useful error when none is available.
- The installer handles supported Arch/CachyOS and Ubuntu/Debian package flows without unloading modules or hiding failures.
- Existing explicit-device and diagnostic CLI paths remain available.
- Documentation presents the simple path first and identifies manual commands as troubleshooting/developer alternatives.
- Rust tests, formatting, linting, shell syntax checks, and a release build pass where dependencies are available.

## Implementation Plan

- Add platform-level v4l2loopback discovery and tests.
- Refactor the CLI to resolve an explicit device or discover one automatically.
- Improve startup errors and output to show the resolved device and one-time setup command.
- Add an idempotent Linux installer and a simple daily start wrapper.
- Rewrite the Linux quick-start documentation around the installer and zero-argument launch.
- Format, lint, test, build, inspect shell scripts, and commit the focused change.

## Task Contract

- Scope: Linux receiver setup UX, v4l2loopback discovery, CLI defaults, scripts, and docs.
- Out of scope: Tauri, automatic OS package management from the receiver binary, Windows/macOS backends, authentication, or changing media protocol behavior.
- Files or areas likely to change: `receiver-platform-linux`, `receiver-cli`, `scripts/linux`, `README.md`, Linux setup/troubleshooting docs.
- Interfaces or behavior contracts: no `--device` means first detected v4l2loopback device; explicit `--device` remains authoritative; missing setup errors point to the installer.
- Risks and edge cases: missing kernel headers, Secure Boot/module signing, conflicting existing module configuration, multiple loopback devices, no GStreamer plugins, and permission failures.
- Open questions: None.

## Verification Plan

- Run `cargo fmt --check`, workspace clippy, workspace tests, and release build.
- Run shell `bash -n` checks for all Linux scripts.
- Run CLI help and pipeline-print diagnostics without requiring a physical loopback device.
- Unit-test discovery ordering and explicit-vs-automatic device selection.
- Verify documentation commands match the actual scripts and binary paths.

## Status

Complete.

## Handoff Notes

- Next exact step: run the one-time Linux installer on a configured CachyOS/Arch or Ubuntu/Debian host, then validate the physical phone path.
- Files changed: receiver CLI/platform discovery, installer and launcher scripts, virtual-camera test helper, Linux setup/troubleshooting docs, README, and this task artifact.
- Commands run: `cargo fmt --manifest-path desktop/Cargo.toml --all`; `bash -n scripts/linux/*.sh`; `cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings`; `cargo test --manifest-path desktop/Cargo.toml --workspace`; `cargo build --manifest-path desktop/Cargo.toml --release -p receiver-cli`; `scripts/development/check-all.sh`; receiver `--help`, `--print-pipeline`, and `--print-capabilities` diagnostics.
- Errors encountered: an initial test compared `PathBuf` with `&str`, and an initial clippy run found an unused import; both were fixed. ShellCheck was unavailable, so Bash syntax validation was used.
- Verification evidence: Rust and Android checks passed. The normal receiver path returns a clear installer hint when no loopback device exists. Privileged installer execution, v4l2loopback, OBS/browser, synthetic network media, and physical Android validation remain host-dependent and were not run here.
