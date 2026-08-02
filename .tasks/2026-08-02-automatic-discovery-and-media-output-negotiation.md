# Automatic discovery and media output negotiation

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Make physical-phone streams negotiate reliably through the desktop preview and
virtual-camera outputs. Replace manual receiver IP entry with desktop-side phone
discovery and attachment.

## Context

- Physical-phone logs show H.265 reaches `decodebin` and produces a decoded
  `video/x-raw` pad before `not-negotiated` propagates through `demux-queue` and
  `udpsrc`.
- Both output branches require the negotiated width, height, format, and exact
  framerate, but contain no `videorate` element.
- A controlled 25 FPS H.264 stream against a 30 FPS receiver session reproduces
  the exact decode-then-`not-negotiated` failure without Android or network loss.
- The equivalent pipeline with `videorate` ran cleanly for the bounded test.
- The v4l2loopback device accepts 1920x1080 YUYV at 30 FPS.
- MPEG-TS continuity warnings remain a separate integrity issue. They are not
  the demonstrated cause of the downstream caps failure.
- The Android app currently initiates control through a manually entered
  receiver endpoint. It does not advertise itself or expose reverse control.
- Physical discovery initially failed because Kotlin serialization omitted the
  required `protocolVersion` from sender responses whose DTO fields had default
  values. The desktop correctly rejected those malformed responses.
- Physical pairing then exposed a firewall contract defect: unconstrained
  ephemeral UDP allocation selected port `45006`, while active UFW policy only
  allowed the old fixed port `5000`, so the receiver timed out without packets.
- Physical H.265 now reaches decoded output, but the frame is predominantly gray
  and does not match the phone preview. MPEG-TS video continuity counters arrive
  heavily out of order.
- Android routes `192.168.1.0/24` through its active Tailscale VPN (`tun0`, MTU
  1280). RootEncoder sends 1316-byte MPEG-TS UDP payloads, and the desktop sees
  them hairpin from the Tailscale subnet router at `192.168.1.156` instead of the
  phone Wi-Fi address `192.168.1.188`.
- Android exposes the matching Wi-Fi network but rejects
  `bindProcessToNetwork` because Tailscale declares a non-bypassable VPN. The
  first direct-binding build therefore rejects stream start with
  `The selected LAN network is no longer available`.
- RootEncoder 2.8.0 and current upstream hard-code seven MPEG-TS packets per UDP
  datagram (1316 bytes) and expose no MTU or payload-size setting.
- The receiver also applies a two-buffer downstream-leaky queue before decode,
  which can discard dependent compressed frames and must not be used as a
  low-latency mechanism.

## Decisions

- Treat output caps negotiation as the primary media defect.
- Normalize the virtual-camera branch to its explicit output framerate with
  `videorate`.
- Do not force preview dimensions and framerate to match the virtual-camera
  contract; preview should follow decoded frames and require only its display
  pixel format.
- Keep continuity/source-integrity diagnosis separate and evidence-based.
- Remove manual IP entry from the normal workflow.
- Target desktop-side phone discovery: Android exposes a fixed, versioned sender
  control service, and the desktop discovers and attaches to it through an
  explicit reverse-control contract.
- Require first-time approval on Android, then automatically reconnect an
  approved phone-desktop pair while both applications are available.
- Remove manual receiver IP and port entry from the product flow. Do not keep a
  legacy endpoint-entry path or compatibility shim.
- Bind each prepared media session to a fresh session-specific UDP port so
  unrelated senders cannot mix on a shared fixed port.
- Allocate session ports from the application-owned range `50000-50099` and
  configure host firewall policy for only that range.
- Respect Android's selected network route, including non-bypassable VPNs. Do
  not force or prefer a route that bypasses the user's VPN choice.
- Own a narrow RootEncoder UDP transport fork at the Android adapter boundary.
  Send six MPEG-TS packets per datagram (1128-byte payload), below the 1232-byte
  UDP payload available at the IPv6 minimum MTU, on both direct and VPN paths.
- Preserve compressed-stream integrity through demux, parse, and decode. Apply
  bounded leaky queues only after decoding at independent raw-frame branches.

## Acceptance Criteria

- H.264 and H.265 physical-phone streams reach the first decoded output frame.
- Streams whose decoded caps report a different or unknown framerate do not
  fail downstream negotiation.
- Preview and v4l2loopback output both remain bounded and low latency.
- The desktop shows available phones without requiring an IP address.
- With one eligible phone, the desktop selects it automatically.
- With multiple phones, the desktop presents an explicit selection.
- Media packets from an unselected sender cannot enter the active session.
- Android camera activation follows the agreed confirmation/pairing policy.

## Implementation Plan

1. Add decoded-pad caps and actual decoder-child diagnostics.
2. Add `videorate` to the virtual-camera branch and relax preview caps.
3. Add regression tests for exact, mismatched, and unknown input framerates.
4. Capture one physical sender at the UDP boundary and classify continuity
   discontinuities as sender, network, or mixed-source behavior.
5. Define sender discovery and reverse-control protocol boundaries.
6. Add the Android sender control service and side-effect-free discovery request.
7. Add Rust discovery, phone selection, and session-bound media filtering.
8. Replace manual endpoint entry with discovered-device state and discovery
   diagnostics.
9. Run physical-phone, restart, multi-phone, OBS, and browser verification.
10. Make the pre-decoder queue lossless.
11. Replace RootEncoder's non-configurable 1316-byte UDP payload with an
    application-owned 1128-byte compatible transport module.

## Task Contract

- Scope: downstream media negotiation, physical-stream integrity diagnosis,
  desktop-side phone discovery, reverse control, and selected-source binding.
- Out of scope: replacing MPEG-TS/UDP, cloud relays, accounts, or internet-wide
  discovery.
- Files or areas likely to change: `receiver-gstreamer`, `receiver-core`,
  `receiver-control-http` or a new discovery/control boundary,
  `receiver-desktop`, Android platform/control/session/UI packages, protocol
  schema, tests, and architecture/setup docs.
- Interfaces or behavior contracts: discovery remains separate from the
  versioned session protocol; only the selected phone may stream into an active
  session; camera activation must remain visible on Android.
- Risks and edge cases: Android background restrictions, duplicate device
  names, multiple network interfaces, oversized subnets, phone sleep, sender
  restarts, and multiple phones.
- Open questions: None.

## Verification Plan

- Rust format, clippy, tests, and workspace build.
- Android unit tests, lint, and debug APK build.
- Controlled 25 FPS input against a 30 FPS output reaches both sinks.
- Controlled unknown-framerate input reaches both sinks.
- Physical H.264 and H.265 runs report decoder and first-frame events.
- Packet capture confirms one selected UDP source and valid session filtering.
- One-phone discovery auto-selection and multi-phone explicit selection.
- Phone disappearance, desktop restart, sender restart, and stale-service tests.
- OBS and browser consume `Mobile Webcam` after discovery-based attachment.

## Status

Implemented and physically verified over the active non-bypassable Tailscale
route. The scoped RootEncoder UDP transport sends 1128-byte MPEG-TS payloads,
the receiver preserves compressed frames until decode, and both H.265 and H.264
produce clean desktop-preview and `/dev/video10` frames without continuity
warnings. Android tests and lint pass. The phone is restored to Auto - prefer
H.265, and the desktop app is running an H.265 stream.

## Handoff Notes

- Next exact step: user confirms the visible desktop preview. Run remaining
  multi-phone, OBS/browser, and restart checks when those acceptance paths are
  in scope.
- Files changed: GStreamer receiver, desktop discovery/UI/runtime, Android
  sender control/pairing/UI and scoped RootEncoder UDP transport, shared
  protocols, tests, setup docs, and installer.
- Commands run: Rust format/workspace tests/clippy/release build; Android full
  tests/lint/APK build/install; Gradle dependency verification; physical H.265
  and H.264 receiver captures; Android route and RootEncoder source diagnostics.
- Errors encountered: required sender response fields with Kotlin defaults were
  omitted from encoded JSON. The fields are now explicit constructor values and
  have a serialization regression test. UFW drops unapproved dynamic UDP ports;
  media allocation and installation now use the bounded `50000-50099` range.
  Android's Tailscale VPN captures the receiver subnet, fragments RootEncoder's
  1316-byte UDP payloads across an MTU-1280 tunnel, and corrupts MPEG-TS
  continuity. Android rejects direct binding because the VPN is non-bypassable.
  RootEncoder exposes no supported payload-size configuration, so its UDP module
  is replaced by a scoped API-compatible fork with the safe payload contract.
  The receiver's compressed queue was also incorrectly leaky.
- Verification evidence: all Rust tests and clippy pass; all Android tests and
  lint pass; direct discovery returns a schema-valid advertisement; the running
  desktop discovers and pairs with the phone; Android starts 1080p30 H.265; UFW
  policy proves why the ephemeral session received no packets; installed APK
  and desktop binaries match current builds; bounded-port and mismatched-frame-
  rate regression tests pass; UFW now permits the active bounded media port;
  `/dev/video10` receives 1080p30 but its captured frame is gray while the phone
  preview has clear scene structure; Android route and network diagnostics prove
  the media flow uses Tailscale instead of direct Wi-Fi; Android logcat proves
  `bindProcessToNetwork` returns false for the valid underlying Wi-Fi network;
  RootEncoder 2.8.0 and current upstream source both hard-code the same 1316-byte
  payload; regression coverage proves the fork emits 1128-byte payloads and no
  leaky queue exists before decode; H.265 and H.264 each reached first frame;
  captured virtual-camera frames clearly match the phone's ceiling scene; no
  MPEG-TS continuity warning occurred in either final run.
