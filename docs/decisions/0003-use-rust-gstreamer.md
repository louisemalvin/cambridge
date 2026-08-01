# ADR 0003: Use Rust and official GStreamer bindings

## Context

The receiver needs dynamic MPEG-TS pads, codec-specific parser branches,
decoder selection, bus messages, timeout recovery, and a reusable service API.
Shelling out to `gst-launch-1.0` would make lifecycle and error handling opaque.

## Decision

Use official GStreamer Rust bindings in `receiver-gstreamer`. Programmatically
build and manage the pipeline. Use `decodebin` initially and log the selected
decoder.

## Consequences

The receiver can expose typed state and errors to the HTTP server and future
UIs. GStreamer runtime plugins remain host dependencies and must be documented
per distribution.
