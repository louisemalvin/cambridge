//! Platform-independent receiver service and session policy.

mod config;
mod error;
mod event;
mod negotiation;
mod service;
mod session;
mod state;
mod validation;

pub use config::{LatencyConfig, OutputFormat, ReceiverConfig};
pub use error::ReceiverError;
pub use event::ReceiverEvent;
pub use negotiation::{negotiate_codec, ReceiverCapabilityProvider, StaticCapabilityProvider};
pub use receiver_protocol::{ReceiverCapabilities, VideoCodec, VideoProfile};
pub use service::{MediaReceiver, ReceiverService};
pub use session::{MediaSessionConfig, ReceiverSession};
pub use state::ReceiverState;
pub use validation::{select_output_format, validate_config};
