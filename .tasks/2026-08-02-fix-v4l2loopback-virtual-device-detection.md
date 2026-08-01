# Fix v4l2loopback virtual-device detection

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Allow the receiver installer and runtime to recognize real v4l2loopback devices exposed through Linux's virtual video4linux sysfs path, even when no `device/driver` symlink exists.
One or two sentences describing the user-visible outcome or concrete engineering result.

## Context

The user's host has `/dev/video10` named `Mobile Webcam` and `/sys/module/v4l2loopback` loaded. Its sysfs entry resolves to `/sys/devices/virtual/video4linux/video10` and has no `device/driver`, so the current detector reports no device and stops before installing the binary.
The current GStreamer package also exposes `tsdemux.latency` as a signed integer property, so the existing Rust pipeline assignment needed a type conversion for the updated host plugins.
Only the constraints and facts needed to implement safely.

## Decisions

- Treat a virtual `video4linux` sysfs entry as v4l2loopback when the v4l2loopback module is loaded.
- Retain the existing driver-symlink check for physical or differently exposed devices.
- Apply the same detection rule to Rust, installer, inspection, and virtual-camera test helpers.
- Do not unload or reload a kernel module automatically.
- Convert the configured demux latency to the GStreamer property's signed integer type.

## Acceptance Criteria

- The current `/dev/video10` host is detected as v4l2loopback.
- The installer proceeds past device detection and installs `mobile-webcam-receiver` when GStreamer checks pass.
- Runtime explicit and automatic device validation use the corrected rule.
- Shell helpers report and test the virtual device without requiring a hardcoded path.
- GStreamer pipeline construction passes with the installed plugin version.
- Rust tests, shell syntax checks, and the Android/Rust repository checks pass.

## Implementation Plan

- Extend Linux device metadata with virtual-sysfs detection.
- Update Rust validation and discovery tests.
- Update installer and helper scripts to recognize virtual devices.
- Adapt the `tsdemux` latency assignment to the current GStreamer property type.
- Run formatting, tests, lint, builds, and host diagnostics.
- Commit the focused fix and update this artifact.

## Task Contract

- Scope: v4l2loopback device recognition after successful package/module installation and the related current-GStreamer property compatibility fix.
- Out of scope: kernel module reloading, package management changes, media behavior changes, and non-Linux backends.
- Files or areas likely to change: `receiver-platform-linux`, `scripts/linux/install-receiver.sh`, `inspect-video-devices.sh`, `setup-v4l2loopback.sh`, and `test-virtual-camera.sh`.
- Interfaces or behavior contracts: a virtual `/sys/devices/virtual/video4linux/videoN` entry counts as loopback only while `/sys/module/v4l2loopback` exists; explicit device paths remain validated.
- Risks and edge cases: another virtual V4L2 driver may be loaded at the same time; existing driver-symlink detection remains preferred.
- Open questions: None.

## Verification Plan

- Inspect the current sysfs and V4L2 metadata read-only.
- Run Rust format, clippy, tests, and release build.
- Run `bash -n` for all Linux scripts.
- Run the receiver's capability/pipeline diagnostics and verify the current virtual device is discoverable.
- Do not run privileged reload commands automatically.

## Status

Complete.

## Handoff Notes

- Next exact step: rerun the installer so it can install the global receiver command, then validate the Android phone path.
- Files changed: Linux `VideoDevice` detection, receiver GStreamer pipeline latency typing, installer/device-inspection/setup/test scripts, and this task artifact.
- Commands run: `cargo fmt --manifest-path desktop/Cargo.toml --all`; `bash -n scripts/linux/*.sh`; `cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets -- -D warnings`; `cargo test --manifest-path desktop/Cargo.toml --workspace`; `cargo build --manifest-path desktop/Cargo.toml --release -p receiver-cli`; `scripts/linux/inspect-video-devices.sh`; `scripts/linux/setup-v4l2loopback.sh 10`; receiver startup with alternate ports; `timeout 2s scripts/linux/test-virtual-camera.sh`.
- Errors encountered: initial tests exposed `tsdemux.latency` expecting `gint`, and the original detector rejected virtual sysfs devices; both were fixed. The user's first installer run stopped before installing `/usr/local/bin/mobile-webcam-receiver`.
- Verification evidence: `/dev/video10` is now reported as `v4l2loopback`; the receiver starts and prints its banner; the virtual-camera test pipeline reaches PLAYING; all Rust tests and clippy pass. The privileged installer was not rerun automatically.
