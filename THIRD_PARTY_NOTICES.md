# Third-party notices

Phase 1 uses the following third-party components. This is an engineering
inventory, not a final commercial distribution review.

| Component | Use | License | Source |
| --- | --- | --- | --- |
| RootEncoder 2.8.0 | Android hardware encode and MPEG-TS/SRT transport adapter | Apache-2.0 | https://github.com/pedroSG94/RootEncoder |
| GStreamer | Desktop demux, parse, decode, convert, and sink pipeline | LGPL-2.1-or-later | https://gstreamer.freedesktop.org/ |
| GStreamer Rust bindings | Rust API for the GStreamer pipeline and appsink | MIT or Apache-2.0, see each crate | https://gitlab.freedesktop.org/gstreamer/gstreamer-rs |
| GTK 4 and gtk4-rs | Linux desktop receiver window and preview texture | GTK LGPL-2.1-or-later; gtk4-rs MIT or Apache-2.0 | https://www.gtk.org/ |
| Ktor | Android HTTP control client | Apache-2.0 | https://ktor.io/ |
| Axum, Tokio, Clap, Serde, tracing | Rust control server, CLI, serialization, and logging | MIT or Apache-2.0, see each crate | https://crates.io/ |
| Rust crates | Other receiver implementation dependencies | See Cargo metadata and each crate license | https://crates.io/ |
| AndroidX and Jetpack Compose | Android UI and lifecycle | Apache-2.0 | https://developer.android.com/ |

Before distributing binaries commercially, generate a complete dependency
notice report for both Gradle and Cargo artifacts and review codec licensing
and system-plugin licensing separately.
