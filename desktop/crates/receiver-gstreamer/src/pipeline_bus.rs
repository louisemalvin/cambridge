use gst::prelude::*;
use gstreamer as gst;
use tracing::{debug, error, warn};

use crate::{metrics::Metrics, pipeline_event::PipelineObserver};

#[derive(Debug, Default, Clone, Copy)]
pub(crate) struct BusPollResult {
    pub(crate) timed_out: bool,
}

pub(crate) fn poll(
    bus: &gst::Bus,
    observer: &PipelineObserver,
    metrics: &mut Metrics,
) -> BusPollResult {
    let mut result = BusPollResult::default();
    while let Some(message) = bus.timed_pop(gst::ClockTime::from_nseconds(0)) {
        match message.view() {
            gst::MessageView::Error(error_message) => {
                error!(
                    source = %message.src().map_or_else(|| "unknown".to_owned(), |source| source.name().to_string()),
                    error = %error_message.error(),
                    debug = ?error_message.debug(),
                    "GStreamer pipeline error"
                );
                observer.set_state(receiver_core::ReceiverState::Failed);
            }
            gst::MessageView::Warning(warning_message) => {
                let source = message
                    .src()
                    .map_or_else(|| "unknown".to_owned(), |source| source.name().to_string());
                let warning = warning_message.error().to_string();
                if warning.starts_with("CONTINUITY:") {
                    debug!(source = %source, warning = %warning, debug = ?warning_message.debug(), "recoverable MPEG-TS continuity warning");
                } else {
                    warn!(source = %source, warning = %warning, debug = ?warning_message.debug(), "GStreamer pipeline warning");
                }
            }
            gst::MessageView::Element(element_message) => {
                let Some(structure) = element_message.structure() else {
                    continue;
                };
                if structure.name() == "GstUDPSrcTimeout" {
                    metrics.record_timeout();
                    observer.on_timeout(metrics.timeout_count());
                    result.timed_out = true;
                }
            }
            _ => {}
        }
    }
    result
}
