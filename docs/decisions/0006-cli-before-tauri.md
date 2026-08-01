# ADR 0006: Build a reusable receiver service before Tauri

## Context

The media receiver, control API, and Linux virtual-camera output need physical
validation before a desktop UI adds another lifecycle surface.

## Decision

Ship a thin Rust CLI as the Phase 1 composition root. Keep the receiver core,
HTTP control server, and GStreamer implementation independent of CLI parsing.

## Consequences

The CLI is useful for headless Linux testing and exposes the same service a
future Tauri application can call. Tauri, Windows virtual cameras, and macOS
camera extensions are deferred until the reusable path is proven.
