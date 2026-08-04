# ADR 0004: Separate control and media connections

## Context

Codec selection requires the sender to learn receiver capabilities, while the
video path needs a low-overhead packet transport. Combining commands and media
would complicate recovery and versioning.

## Decision

Use versioned HTTP/JSON over TCP `5001` for health, capabilities, session
creation, state, diagnostics, and stop. Use encrypted MPEG-TS over a
receiver-owned SRT listener for video only.

## Consequences

Negotiation and errors are inspectable without touching video bytes. Wi-Fi and
USB tethering share the same implementation. The sender discovers the receiver
with Bonjour/NSD and keeps explicit origin entry as the fallback.
