# macOS OBS Receiver Completion Contract

## Goal

Finish the existing macOS OBS receiver as a CI-validated release candidate.
Do not implement or repair the iOS sender. Physical Mac acceptance will be
performed later by the owner’s tester.

## Starting point

- Start from clean `main` at `b8ad4ac`.
- Linux native build and tests pass.
- Both macOS jobs currently fail during OBS dependency configuration, before
  the CamBridge plugin compiles:
  <https://github.com/louisemalvin/cambridge/actions/runs/31460310017>
- The immediate failure is unnecessary configuration of OBS target
  `libobs-metal`. CamBridge needs the `libobs` development target, not the OBS
  application or its renderer targets.

## Scope

Work only in:

- `receiver/obs/cambridge-obs-source/`
- `scripts/receiver/macos/`
- `.github/workflows/native.yml`
- receiver documentation after verification

Do not change `sender/ios`, `sender/android`, `protocol`, `VERSION`, or release
tags. Do not disable or modify iOS CI.

## Architecture contract

Keep the existing shared pipeline:

```text
TCP control -> RTP/H.264 UDP -> shared decoder -> newest-frame mailbox
            -> shared renderer -> OBS
```

Only native frame handling is platform-specific:

```text
Linux: VAAPI -> DRM PRIME -> DMA-BUF -> OBS
macOS: VideoToolbox -> CVPixelBuffer/IOSurface -> Metal conversion -> OBS
```

Media-path behavior is fixed:

- `cpu`: software only; do not attempt native setup.
- `native_required`: native only; any failure ends the session.
- `auto`: use native when setup is `Ready`; select software only when setup is
  explicitly `Unsupported`.
- Never switch paths after session acceptance.
- Never hide a failure with retries, alternate decoders, or silent fallback.

## Work order

### 1. Correct the dependency boundary

Create:

`receiver/obs/cambridge-obs-source/cmake/macos/obs-libobs-only.CMakeLists.txt`

It must initialize the pinned OBS CMake project and add only `libobs`. It must
not add OBS frontend, plugins, tests, `libobs-opengl`, or `libobs-metal`.

Update `scripts/receiver/macos/prepare-cambridge-build-dependencies.sh` to:

1. Download and hash-verify the pinned dependencies from `buildspec.json`.
2. Use the committed libobs-only CMake entry point in the extracted OBS tree.
3. Configure with Xcode for `CAMBRIDGE_MACOS_ARCHITECTURE`.
4. Build only target `libobs`.
5. Install only the `Development` component.
6. Export the resulting CMake and pkg-config paths.
7. Verify the resolved OBS/FFmpeg versions and libobs architecture.

Keep the current pinned versions. Do not downgrade OBS and do not patch the
downloaded source with ad-hoc `sed` replacements.

### 2. Compile the plugin

Push this isolated dependency correction on branch
`receiver/macos-validation`. Wait for the complete Native workflow. If plugin
compilation then fails, collect every compiler error from both macOS jobs and
fix the complete batch in the existing macOS adapters. Do not refactor shared
receiver code unless a demonstrated shared defect has a regression test.

### 3. Verify

The final commit must pass:

```bash
./scripts/development/check-all.sh
./scripts/receiver/linux/build-cambridge-obs-plugin.sh
ldd -r build/cambridge-obs-plugin/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so
git diff --check
```

The final Native GitHub workflow must be green for Linux, macOS arm64, and
macOS x86_64. Each macOS job must validate CTest, bundle layout, Info.plist,
Metal library, architecture, signature, and load commands. Upload each verified
`.plugin` bundle as a workflow artifact.

## Completion boundary

Merge to `main` only after the final Native workflow is green. End with clean
local `main`, `HEAD == origin/main`, and report the commit and workflow URL.

Keep macOS documented as an **acceptance candidate**. Do not tag or publish a
macOS release. Later physical testing must cover OBS loading, native and CPU
fixtures, rotations, fault injection, Bonjour/manual addressing, repeated
Start/Stop, soak behavior, and a real Android-to-OBS stream.
