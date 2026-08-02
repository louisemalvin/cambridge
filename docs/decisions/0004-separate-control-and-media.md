# ADR 0004: Separate control and media connections

## Context

Codec selection requires the sender to learn receiver capabilities, while the
video path needs a low-overhead packet transport. Combining commands and media
would complicate recovery and versioning.

## Decision

Use versioned HTTP/JSON over TCP `5001` for health, capabilities, preparation,
state, and stop. Use MPEG-TS over a session-specific UDP port for video only.

## Consequences

Negotiation and errors are inspectable without touching video bytes. Wi-Fi and
USB tethering share the same implementation. Desktop subnet discovery and
reverse control supply the receiver address without user entry.
