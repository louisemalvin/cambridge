# ADR 0001: Use RootEncoder behind an adapter

## Context

Android hardware encoding, camera surface input, MPEG-TS packaging, and SRT
transport are platform-sensitive responsibilities. Reimplementing a codec or
transport would duplicate mature media code and increase failure risk.

## Decision

Use RootEncoder `2.8.0` behind the project-owned `StreamEngine` interface. Only
the `streaming/rootencoder/` package may import RootEncoder types. The selected
codec, profile, bitrate, and lifecycle remain application-domain values.

## Consequences

The adapter follows RootEncoder's SRT API while
keeping the rest of the app replaceable. RootEncoder upgrades require a
focused adapter compatibility review. Its Apache-2.0 notice is recorded for
distribution review.
