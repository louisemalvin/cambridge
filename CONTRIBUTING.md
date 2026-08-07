# Contributing

Contributions are welcome for the supported Phase 1 path:

- Android sender
- Linux x86_64/amd64 OBS source
- Versioned control and RTP/H.264 protocol

Use [the repository boundary](docs/repository-boundary.md) to distinguish
public product documentation from workspace-only task notes and tooling.

Before opening a pull request:

1. Read the repository architecture and release contract.
2. Keep Android, protocol, native OBS, and documentation changes in their
   declared module boundaries.
3. Run `./scripts/development/check-all.sh` when the required toolchains are
   available.
4. Describe any platform, device, OBS, FFmpeg, VAAPI, or DRM limitations in
   the pull request.

Do not commit private deployment files, signing keys, generated screenshots,
build directories, or credentials. Use the ignored
`protocol/direct-stream-deployment.local.json` file for local network values.
