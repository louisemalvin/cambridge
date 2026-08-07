# Repository boundary

The public project is **CamBridge**, by [@louisemalvin](https://github.com/louisemalvin).
This file defines what belongs in
the public repository and what remains workspace-only development material.

## Public repository

These files describe, build, test, or support the released Phase 1 product:

| Area | Public contents |
| --- | --- |
| Product entry point | `README.md`, `CHANGELOG.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md` |
| Community and safety | `CONTRIBUTING.md`, `SECURITY.md` |
| Product documentation | `docs/architecture.md`, `docs/contract.md`, `docs/platforms/`, `docs/operations/`, `docs/release.md`, and `docs/release-contract.md` |
| Protocol | `protocol/README.md`, the active direct-webcam JSON contract/schema, and active examples |
| Build and verification | `android/`, `desktop/hosts/obs/direct-webcam-source/`, and the active `scripts/` |
| Release metadata | `VERSION` and the release workflows under `.github/workflows/` |

Public documentation must describe the current Android sender and Linux
x86_64/amd64 OBS source. It must not promise ARM, iOS, other OBS operating
systems, virtual-camera output, SRT, or the removed Rust receiver.

## Workspace-only development material

These are useful to the local development process but are not product or user
documentation and must not be added to the public release commit:

- `AGENTS.md`: local coding-agent and maintainer instructions;
- `.tasks/`: private task history, measurements, experiments, and future ideas;
- `android/skills/`: locally installed reference skills and copied material;
- root screenshots such as `desktop-screen*.png` and `screenshot.png`;
- `build/`, `__pycache__/`, logs, and other generated output;
- `protocol/direct-stream-deployment.local.json`: local network configuration.

The ignored files remain available in the development workspace. Ignoring them
does not delete them.

## Historical or out-of-scope material

Old Rust receiver, iOS, SRT, virtual-camera, and protocol-v2 plans are not
part of the Phase 1 public documentation. They should remain excluded from the
release commit unless a future release contract explicitly restores them.
The remaining `desktop/receiver-core/README.md` is an internal extraction
roadmap, not a supported public API or implementation.

## Release review rule

Before tagging a release, review the staged file list against this boundary.
The public release must contain no private addresses, workstation paths,
credentials, generated screenshots, or internal task notes.
