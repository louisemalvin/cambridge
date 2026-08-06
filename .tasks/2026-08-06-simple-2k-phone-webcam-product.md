# Simple 2K Phone Webcam Product Contract

## Goal

Ship one boring, stable phone-to-Linux-OBS webcam product. A user connects the
phone to one configured desktop and gets live video. The product is not a
prototype, compatibility experiment, or multi-route networking client.

## Context

- The production media path is Camera2 -> MediaCodec H.264 -> RTP/H.264 over
  UDP -> the native OBS source.
- The native source already owns the receive, decode, GPU presentation, CPU
  fallback, dynamic geometry, and newest-frame mailbox boundaries.
- The current Android control layer has more phases than this product needs:
  route candidates, health probing, synthetic capabilities, and synthetic
  session creation. Those are not part of the target architecture.
- The current task artifact is superseded by this contract. This file is the
  only authoritative task artifact for the product work.

## Decisions

- There is exactly one configured desktop endpoint per phone. It may be a
  hostname or address supplied during setup, but the app stores and uses no
  alternate endpoint.
- The operating system owns network routing. The app does not select LAN,
  VPN, Tailscale, or fallback branches.
- `Connect` is the one explicit start action. It connects to the configured
  desktop and starts video as soon as the session handshake succeeds.
- `Stop` is the only explicit stop action. It releases the camera, encoder,
  sockets, foreground ownership, and session.
- The user-visible connection states are `Connecting`, `Streaming`,
  `Desktop unavailable`, and `Stopped`. There is no reconnect state or
  background recovery loop.
- The product has one active phone session per OBS source. A new valid hello
  invalidates any previous session before it becomes active.
- The direct control protocol is version 3 and uses the machine-readable
  values in `protocol/direct-stream-contract.json` as its source of truth.
- The direct session uses one TCP control connection and one UDP media
  connection. These are two sockets implementing one logical product session.
- The only production codec and media transport are H.264 and RFC 6184
  H.264/RTP over UDP. No audio, H.265, AV1, MPEG-TS, SRT, WebRTC, relay, or
  virtual camera device is added.
- The normal product profile is fixed at 2K30: coded `2560x1440`, 30 FPS.
  There is no normal-flow quality selector or silent downgrade. Any future
  quality is a separate contract change.
- Before connection, the phone builds one immutable session contract containing
  the selected profile, orientation, exact rotation, frame rate, bitrate, and
  camera metadata. The app locks to the selected portrait or landscape axis
  while allowing its 180-degree reverse. The hello carries coded geometry and
  the resolved rotation; the receiver derives presentation dimensions.
  Rotation during a session does not renegotiate.
- RTP is best-effort. Late or incomplete media is dropped, the newest valid
  frame wins, and periodic encoder keyframes provide passive recovery. There
  are no media recovery requests or status feedback messages.
- The native source applies the same orientation transform to luma and chroma,
  preserves color, prefers VAAPI/DRM PRIME direct presentation, and retains a
  bounded CPU NV12 fallback for environments without usable hardware import.
- If the control session disappears, the phone releases stale media resources,
  reports failure, and waits for the user to press Connect again.

## Product Connection Contract

1. The desktop OBS source is loaded and listens on the contract control and
   media ports.
2. The phone opens one TCP connection to the configured desktop control port.
3. The phone sends one length-prefixed JSON `hello` containing protocol version,
   session ID, generation, H.264, coded geometry, rotation, FPS, and bitrate.
4. OBS validates the hello and replies with one `accepted` message containing
   the same session identity and the contract-derived media port.
5. Only after `accepted` does the phone start Camera2, MediaCodec, and RTP/UDP.
6. OBS receives RTP, reorders and assembles H.264 access units, decodes them,
   and presents the newest valid frame.
7. The phone may send `stop` before closing a normal session. OBS never sends
   media recovery requests or periodic status messages.
8. A control disconnect, explicit stop, or invalid generation clears the OBS
   session and presentation mailbox.

There is no separate health request, capabilities request, or `createSession`
step in the direct product flow. A successful hello/accepted exchange is the
receiver negotiation.

## Acceptance Criteria

### User behavior

- One configured desktop can be connected without choosing among routes.
- `Connect` reaches `Streaming` when OBS accepts the session and does not
  activate the camera while the desktop is unavailable.
- `Stop` is idempotent and prevents future reconnects or camera activation.
- A lost session reaches `Desktop unavailable` or a plain connection error and
  remains stopped until the user presses Connect again.
- No stale frame from an earlier generation is displayed after a new explicit
  session.

### Wire and lifecycle behavior

- The first control frame is a valid protocol v3 `hello`; no preflight control
  frames are required.
- Media starts only after a matching `accepted` response.
- The receiver accepts one active session and validates session ID, generation,
  codec, frame rate, geometry, and bounds.
- Stop, disconnect, and failed start release every camera, codec, socket,
  coroutine/job, mailbox, and foreground-service resource.
- A new session ID and generation are created only by a new explicit Connect.

### Video behavior

- The normal product stream is `2560x1440@30` H.264.
- Landscape is upright and undistorted at `2560x1440`.
- Portrait is upright and undistorted at derived display geometry `1440x2560`,
  aspect-fitted by the OBS scene without stretch or unintended crop.
- Color conversion is correct for both supported DMA-BUF layouts and CPU NV12.
- The primary hardware path reports VAAPI decode, DRM PRIME, direct DMA-BUF
  presentation, zero CPU uploads, zero hardware CPU transfers, and zero import
  failures during the acceptance run.
- CPU NV12 presentation remains a bounded operational fallback and is tested
  separately; it is not a product quality downgrade below 2K.

### User interface

- Normal flow contains only the configured computer, connection status,
  Connect, Stop, camera controls, orientation result, and plain-language
  errors.
- Normal flow does not expose ports, IP addresses, RTP, UDP, H.264, codecs,
  bitrates, VPNs, or route selection.
- Errors distinguish desktop unavailable and phone 2K
  incompatibility without asking the user to diagnose transport details.

### Host behavior

- The OBS source binds the contract control and media ports on the intended
  host interfaces.
- Firewall setup, when applied by an authorized operator, permits only the
  intended trusted network/interface and never opens the ports globally.
- No live UFW rules or live OBS scene are changed by agent-run verification.

## Implementation Plan

1. [x] Make `protocol/direct-stream-contract.json` and its parity checks authoritative
   for the single control/media session and fixed 2K30 profile.
2. [x] Replace the Android route/probe/capability/session-preparation chain with one
   endpoint, one connection lifecycle, and one hello/accepted handshake.
3. [x] Keep session generation, the immutable pre-connect session contract,
   capability checks, and resource cleanup behind the single Connect action.
4. [x] Update the native control server to enforce the one-session contract and
   retain the existing RTP receiver, decoder, renderer, and mailbox boundaries.
5. [x] Simplify the normal Android UI and make automated interaction target semantic
   controls rather than screen coordinates.
6. [x] Update the OBS template, setup guidance, diagnostics, tests, and deployment
   checks to describe one endpoint and one logical session.
7. [x] Inspect changed production files for unexplained numeric literals and
   duplicated contract values before completion.

## Task Contract

- In scope: the Android single-endpoint connection lifecycle, direct v3 control
  handshake, 2K30 orientation geometry, RTP/H.264 media path, native OBS
  session handling, explicit cleanup, simple UI, isolated setup, tests, and
  documentation.
- Out of scope: alternate endpoints, route priority, VPN/Tailscale logic,
  automatic multi-computer discovery, audio, new codecs/transports, cloud
  relay, internet exposure, live rotation renegotiation, and mandatory 4K.
- Product boundary: one phone, one configured desktop, one active OBS source
  session, one fixed normal quality, one locked session contract, and one
  user-controlled Connect/Stop lifecycle.
- Verification boundary: do not alter live UFW or the live OBS scene during
  agent-run work. Physical validation is user-authorized when explicitly
  requested.

## Verification Plan

- Run contract JSON/schema/parity validation and inspect all generated or
  mirrored values.
- Run Android unit tests for one-endpoint connect, handshake failure, no
  automatic retry, generation replacement, Stop cancellation, orientation, and
  cleanup.
- Run the native build, tests, staged-module diagnostics, and isolated fixture
  capture for 2K landscape and portrait in hardware and CPU modes.
- Run the required AVD `codex-phone-webcam-api35` smoke for real
  Camera2/MediaCodec connection lifecycle. A test-only lower-resolution AVD
  asset, if required by emulator capability, validates lifecycle only and never
  represents a product downgrade or satisfies 2K acceptance.
- Run one authorized physical acceptance sequence for real 2K landscape and
  portrait. A connection loss requires an explicit user Connect.
- Verify nonblack changing frames, source geometry, aspect fit, color patches,
  generation replacement, and the absence of stale frames.
- Scan normal UI and configuration for alternate-route branches, technical
  transport labels, old preflight calls, stale 720p product defaults, fixed
  scene scaling, duplicated ports, and unexplained production literals.

## Status

- Product contract implemented and verified in incremental commits.
- Android now has one configured endpoint, one hello/accepted handshake, fixed
  normal 2K30, an immutable pre-connect session contract, explicit Connect/Stop,
  no automatic reconnect, and no legacy route, discovery, health, standby, or
  codec-selection flow.
- Native verification passed for 2K30 landscape VAAPI/DRM PRIME direct DMA-BUF
  and 2K30 portrait CPU NV12 presentation.
- The named AVD smoke passed with isolated OBS, a fresh generation after OBS
  restart, native frame publication, and final resource release.
- An earlier debug APK was installed on the user-authorized Vivo V2413 and the
  user reported that it connected. No live UFW rules or live OBS scene were
  changed by this contract update.

## Handoff Notes

- Authoritative artifact: `.tasks/2026-08-06-simple-2k-phone-webcam-product.md`.
- Superseded artifact: removed
  `.tasks/2026-08-06-lan-first-resilient-2k-portrait-and-landscape-phone-webcam.md`.
- Verification: contract parity, native CTest, Android unit tests/lint/APK, and
  the focused native build passed after the v3 best-effort contract update.
  The earlier full verification also passed: `./scripts/development/check-all.sh`,
  JSON validation, native CTest, `ldd -r`, Android unit tests/lint/APK, and
  isolated 2K native fixtures passed. The AVD smoke passed on
  `codex-phone-webcam-api35` with serial `emulator-5556` and isolated OBS.
- Android CLI layout and screenshot inspection passed on the same named AVD;
  the semantic `Start camera` action was present and visually confirmed.
- `task-init` and `task-ready` are not installed, so this artifact was created
  directly with the task-artifact template sections.
