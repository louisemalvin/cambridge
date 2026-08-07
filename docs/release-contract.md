# Open-source release contract

Status: draft; maintainer decisions are required before release work is
considered complete.

This contract defines what the first public release of CamBridge includes,
what it explicitly excludes, and the evidence required to publish it. It is a
release boundary, not a promise that every future platform or transport is
supported.

## Proposed Phase 1 scope

The release contains:

- The Android sender using Camera2, MediaCodec H.264, and RTP/H.264 over UDP.
- The native Linux OBS source using the direct-webcam protocol, FFmpeg,
  VAAPI/DRM PRIME when available, and the bounded CPU NV12 fallback.
- The versioned protocol contract, schemas, examples, build scripts, tests, and
  operational documentation.
- Android discovery of the OBS receiver with manual endpoint configuration as
  the fallback.

The release does not contain unless explicitly re-approved:

- The removed Rust receiver, iOS implementation, virtual-camera output,
  MPEG-TS, SRT, automatic reconnect, or internet-facing deployment.
- Authentication or encryption beyond the trusted-local-network deployment
  model.
- A promise that every Android device exposes the same Camera2, H.264, lens,
  anti-flicker, or hardware-acceleration capabilities.

Future expansion is expected, but is outside this release contract. Planned
follow-up work may add an iOS sender and OBS host support for other operating
systems. Those platforms require their own implementation, compatibility,
packaging, and release gates before they become supported.

## Proposed support baseline

- Android application ID remains `dev.mobilewebcam.sender`, with the current
  minimum SDK and target SDK documented in the Android build.
- Linux support is intended to be generic rather than tied to one named
  distribution, but Release 1 targets Linux x86_64/amd64 only. The supported
  build contract is an x86_64 Linux system with the latest OBS Studio version
  installed and tested for the release, libobs, FFmpeg, libva/libdrm, jansson,
  and C++17 build tooling. The exact OBS version and dependency/ABI
  requirements must be recorded in the release notes.
- ARM, including ARM64, is outside the Release 1 binary support matrix. It
  may be supported later through separate builds and validation.
- VAAPI/DRM PRIME is an optimization; software decode and CPU NV12 upload are
  the supported fallback.
- The transport uses the contract-defined control and media ports on a trusted
  LAN. A developer workstation address must not be embedded in the public
  release configuration.

## Release deliverables

The release is complete only when the selected distribution form has all of
the following:

- A clean, reviewed Git commit with a version tag and changelog.
- Source code, build instructions, supported-platform matrix, troubleshooting,
  security model, and contribution guidance.
- Complete third-party notices and required license texts for Android and
  native dependencies, including the actual FFmpeg build configuration.
- A reproducible Android release build and a downloadable signed APK with
  checksums and a documented signing-key process.
- A downloadable, versioned Linux OBS plugin package with checksums and its
  runtime-library and OBS compatibility requirements.
- CI that runs on changes to the actual Android, protocol, native OBS, script,
  and documentation paths and publishes retained test/build evidence.

## Release gates

1. Scope is settled; all current tracked deletions and untracked files are
   either intentionally included, intentionally ignored, or removed from the
   release branch.
2. The project license, copyright holder, contributor policy, OBS/libobs
   relationship, FFmpeg configuration, H.264 patent considerations, and
   redistribution notices have been reviewed.
3. Private deployment values, workstation paths, generated screenshots,
   caches, and credentials are absent from the public tree and Git history.
4. Android has a release variant, stable versioning, application icon and
   metadata, signing automation that keeps keys outside the repository, and a
   clean install/upgrade test.
5. The native workflow watches `desktop/hosts/obs/direct-webcam-source/**`,
   builds on the documented Linux baseline, runs `ldd -r`, and verifies both
   VAAPI/DRM and CPU fallback paths where available.
6. The emulator checks, Android instrumentation tests, native tests, and a
   physical-device OBS session pass. A fixed one-hour endurance run is not a
   Release 1 gate.
7. The trusted-LAN, unauthenticated transport and camera/network permissions
   are clearly disclosed to users.

## Selected license policy

The maintainer selected the following policy, subject to final legal review:

- Apache-2.0 for the Android sender, protocol, scripts, and documentation.
- GPL-2.0-or-later for the Linux OBS plugin, with the plugin directory carrying
  its own license notice.
- Existing third-party licenses remain applicable and are listed in the final
  dependency notices.

This keeps the phone sender permissive while acknowledging that the native
receiver integrates with GPL-licensed libobs. This recommendation is not legal
advice; binary redistribution and FFmpeg/H.264 details still require review.

## Maintainer decisions required

Please answer these questions in order. The proposed default can be accepted
with `default`.

1. Is the public scope Android sender plus Linux OBS source only, with the
   removed Rust and iOS trees excluded? **Decision: yes.** iOS and other OBS
   operating systems remain future expansion work, not Release 1 support.
2. Is the first release source-only, or should it include a signed Android
   APK/AAB and prebuilt Linux OBS plugin? **Decision: include downloadable
   Android APK and Linux OBS plugin artifacts with the source.**
3. Should the first Android distribution be sideloaded/GitHub Releases, or is
   Google Play required? **Decision: publish the downloadable artifacts as
   releases; Google Play is not part of Release 1 unless separately approved.**
4. What project name, repository URL, copyright holder, maintainer contact,
   and security-reporting address should appear in the public files?
   **Decision: project name `CamBridge`; public creator tag `@louisemalvin`;
   repository `https://github.com/louisemalvin/cambridge`; security
   reports use
   GitHub's private vulnerability-reporting channel or a private channel
   arranged through the repository owner. No personal address is stored in
   the repository.**
5. May we use the recommended split policy above, subject to legal review, and
   what exact person or company name should appear as copyright holder?
   **Decision: use the recommended split policy. Copyright identity remains
   public attribution: `Louise Tanaka`. No address, email, or other personal
   details are required in the repository.**
6. What Linux/OBS baseline should be supported: distribution(s), the latest
   installed OBS version, architecture(s), and whether VAAPI is required or
   optional?
   **Decision: generic Linux is the goal, with Linux x86_64/amd64 as the only
   Release 1 architecture. ARM/ARM64 is not supported in Release 1. Release 1
   targets the latest OBS version installed and tested in the release
   environment; the exact version and dependency/ABI matrix will be recorded
   in the release notes. VAAPI remains optional and CPU fallback remains
   supported.**
7. Should receiver discovery be the normal path with manual host/port fallback,
   and may the public release contain no preconfigured workstation address?
   **Decision: yes.**
8. Is the trusted-LAN/no-authentication threat model acceptable for Phase 1?
   **Decision: yes, with prominent documentation and no security guarantee.**
9. What release version and date should be used? **Decision: release version
   `0.1.0`; the publication date will be the date of the public release.**
10. Must the one-hour endurance run pass before the first public tag?
    **Decision: no. There is no fixed one-hour endurance requirement for
    Release 1.**

## Change control

Any change to transport, supported platforms, binary distribution, licensing,
security model, or release gates requires an update to this contract and a new
release decision record. Implementation work should not silently expand the
Phase 1 boundary. The public-versus-workspace documentation boundary is defined
in [repository-boundary.md](repository-boundary.md).
