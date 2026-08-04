# ADR 0005: Support H.264 and H.265 through one architecture

## Context

H.264 offers broad compatibility while H.265 can reduce bitrate. Phones and
receivers differ in codec and profile support, so a single hardcoded branch
would produce fragile behavior.

## Decision

Use a project-owned codec enum and capability negotiation. H.264 maps to
`h264parse`, H.265 maps to `h265parse`, and both use the same MPEG-TS/SRT
session lifecycle and output backend.

## Consequences

Auto mode can prefer H.265 and fall back to H.264. Forced modes fail clearly
instead of silently changing the user's choice. Adding a codec should require
new branches at the codec boundaries, not changes throughout UI and lifecycle
code.
