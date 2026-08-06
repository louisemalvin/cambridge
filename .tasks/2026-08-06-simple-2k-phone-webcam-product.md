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
- `Stop` is the only explicit stop action. It cancels reconnect work and
  releases the camera, encoder, sockets, foreground ownership, and session.
- The user-visible connection states are `Connecting`, `Streaming`,
  `Reconnecting`, `Desktop unavailable`, and `Stopped`. There is no separate
  standby or capability-negotiation state.
- The product has one active phone session per OBS source. A new valid hello
  invalidates any previous session before it becomes active.
- The direct control protocol remains version 2 and uses the machine-readable
  values in `protocol/direct-stream-contract.json` as its source of truth.
- The direct session uses one TCP control connection and one UDP media
  connection. These are two sockets implementing one logical product session.
- The only production codec and media transport are H.264 and RFC 6184
  H.264/RTP over UDP. No audio, H.265, AV1, MPEG-TS, SRT, WebRTC, relay, or
  virtual camera device is added.
- The normal product profile is fixed at 2K30: coded `2560x1440`, 30 FPS.
  There is no normal-flow quality selector or silent downgrade. Any future
  quality is a separate contract change.
- At connection start, the phone snapshots the camera/display orientation.
  Landscape reports `2560x1440`; portrait reports `1440x2560` while retaining
  the coded frame at `2560x1440`. Rotation during a session does not renegotiate.
  The user stops and connects again after rotating the phone.
- The native source applies the same orientation transform to luma and chroma,
  preserves color, prefers VAAPI/DRM PRIME direct presentation, and retains a
  bounded CPU NV12 fallback for environments without usable hardware import.
- If the session disappears, the phone releases stale media resources and
  retries the same configured endpoint with a bounded backoff while the user
  remains connected. Each successful retry receives a new session generation.

## Product Connection Contract

1. The desktop OBS source is loaded and listens on the contract control and
   media ports.
2. The phone opens one TCP connection to the configured desktop control port.
3. The phone sends one length-prefixed JSON `hello` containing protocol version,
   session ID, generation, H.264, coded/display geometry, rotation, FPS, and
   bitrate.
4. OBS validates the hello and replies with one `accepted` message containing
   the same session identity and the contract-derived media port.
5. Only after `accepted` does the phone start Camera2, MediaCodec, and RTP/UDP.
6. OBS receives RTP, reorders and assembles H.264 access units, decodes them,
   and presents the newest valid frame.
7. OBS may send `request_idr` when loss or stale presentation requires a fresh
   keyframe. The phone may send `stop` before closing a normal session.
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
- A lost session reaches `Reconnecting`, then returns to `Streaming` through
  the same endpoint without another user action.
- No stale frame from an earlier generation is displayed after reconnect.

### Wire and lifecycle behavior

- The first control frame is a valid protocol v2 `hello`; no preflight control
  frames are required.
- Media starts only after a matching `accepted` response.
- The receiver accepts one active session and validates session ID, generation,
  codec, frame rate, geometry, and bounds.
- Stop, disconnect, failed start, and reconnect release every camera, codec,
  socket, coroutine/job, mailbox, and foreground-service resource.
- A reconnect creates a new session ID or generation and requests a fresh IDR.

### Video behavior

- The normal product stream is `2560x1440@30` H.264.
- Landscape is upright and undistorted at `2560x1440`.
- Portrait is upright and undistorted at display geometry `1440x2560`,
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
- Errors distinguish desktop unavailable, reconnecting, and phone 2K
  incompatibility without asking the user to diagnose transport details.

### Host behavior

- The OBS source binds the contract control and media ports on the intended
  host interfaces.
- Firewall setup, when applied by an authorized operator, permits only the
  intended trusted network/interface and never opens the ports globally.
- No live UFW rules or live OBS scene are changed by agent-run verification.

## Implementation Plan

1. Make `protocol/direct-stream-contract.json` and its parity checks authoritative
   for the single control/media session and fixed 2K30 profile.
2. Replace the Android route/probe/capability/session-preparation chain with one
   endpoint, one connection lifecycle, one hello/accepted handshake, and one
   reconnect owner.
3. Keep session generation, orientation snapshot, capability checks, and
   resource cleanup, but keep them behind the single Connect action.
4. Update the native control server to enforce the one-session contract and
   retain the existing RTP receiver, decoder, renderer, and mailbox boundaries.
5. Simplify the normal Android UI and make automated interaction target semantic
   controls rather than screen coordinates.
6. Update the OBS template, setup guidance, diagnostics, tests, and deployment
   checks to describe one endpoint and one logical session.
7. Inspect changed production files for unexplained numeric literals and
   duplicated contract values before completion.

## Task Contract

- In scope: the Android single-endpoint connection lifecycle, direct v2 control
  handshake, 2K30 orientation geometry, RTP/H.264 media path, native OBS
  session handling, reconnect/cleanup, simple UI, isolated setup, tests, and
  documentation.
- Out of scope: alternate endpoints, route priority, VPN/Tailscale logic,
  automatic multi-computer discovery, audio, new codecs/transports, cloud
  relay, internet exposure, live rotation renegotiation, and mandatory 4K.
- Product boundary: one phone, one configured desktop, one active OBS source
  session, one fixed normal quality, and one user-controlled Connect/Stop
  lifecycle.
- Verification boundary: do not contact a physical Android device, alter live
  UFW, or alter the live OBS scene during agent-run work. Physical 2K testing
  is a separate user-authorized acceptance gate.

## Verification Plan

- Run contract JSON/schema/parity validation and inspect all generated or
  mirrored values.
- Run Android unit tests for one-endpoint connect, handshake failure, same-endpoint
  retry, generation replacement, Stop cancellation, orientation, and cleanup.
- Run the native build, tests, staged-module diagnostics, and isolated fixture
  capture for 2K landscape and portrait in hardware and CPU modes.
- Run the required AVD `codex-phone-webcam-api35` smoke for real
  Camera2/MediaCodec connection lifecycle. A test-only lower-resolution AVD
  asset, if required by emulator capability, validates lifecycle only and never
  represents a product downgrade or satisfies 2K acceptance.
- Run one authorized physical acceptance sequence for real 2K landscape,
  portrait, reconnect, and changing OBS output. This is not an agent-run step.
- Verify nonblack changing frames, source geometry, aspect fit, color patches,
  generation replacement, and the absence of stale frames.
- Scan normal UI and configuration for alternate-route branches, technical
  transport labels, old preflight calls, stale 720p product defaults, fixed
  scene scaling, duplicated ports, and unexplained production literals.

## Status

- Contract ready for implementation.
- The existing native RTP/H.264, decoder, renderer, and isolated 2K fixture
  work is reusable.
- The existing Android multi-phase and multi-route connection behavior does not
  yet satisfy this contract and must be simplified before completion.
- No runtime systems were launched or changed while defining this contract.

## Handoff Notes

- Next exact step: update the Android direct control/session path to use one
  configured endpoint and one hello/accepted handshake, then update its unit
  tests before any runtime smoke.
- Authoritative artifact: `.tasks/2026-08-06-simple-2k-phone-webcam-product.md`.
- Superseded artifact: removed
  `.tasks/2026-08-06-lan-first-resilient-2k-portrait-and-landscape-phone-webcam.md`.
- Commands run: repository inspection with `rg`, `sed`, `cat`, `wc`, and
  read-only `git status`; no emulator, OBS, firewall, or physical device run.
- Errors encountered: `task-init` and `task-ready` are not installed, so the
  artifact was created directly with the task-artifact template sections.
