use gst::prelude::*;
use gstreamer as gst;
use tracing::{debug, error, warn};

use crate::{metrics::Metrics, pipeline_event::PipelineObserver};

pub(crate) fn poll(bus: &gst::Bus, observer: &PipelineObserver, metrics: &mut Metrics) {
    while let Some(message) = bus.timed_pop(gst::ClockTime::ZERO) {
        match message.view() {
            gst::MessageView::Error(error_message) => {
                let source = message
                    .src()
                    .map_or_else(|| "unknown".to_owned(), |source| source.name().to_string());
                let error = error_message.error().to_string();
                error!(source = %source, error = %error, debug = ?error_message.debug(), "GStreamer pipeline error");
                metrics.record_pipeline_error();
                observer.on_pipeline_error(source, error);
                observer.set_state(receiver_core::ReceiverState::Failed);
            }
            gst::MessageView::Warning(warning_message) => {
                let source = message
                    .src()
                    .map_or_else(|| "unknown".to_owned(), |source| source.name().to_string());
                let warning = warning_message.error().to_string();
                let continuity = warning.starts_with("CONTINUITY:");
                metrics.record_pipeline_warning(continuity);
                observer.on_pipeline_warning(&source, &warning, continuity);
                if continuity {
                    debug!(source = %source, warning = %warning, debug = ?warning_message.debug(), "recoverable MPEG-TS continuity warning");
                } else {
                    warn!(source = %source, warning = %warning, debug = ?warning_message.debug(), "GStreamer pipeline warning");
                }
            }
            gst::MessageView::Element(element_message) => {
                let Some(structure) = element_message.structure() else {
                    continue;
                };
                let transport_lost = (structure.name() == "connection-removed"
                    || structure.name() == "caller-rejected")
                    && !matches!(
                        observer.state(),
                        receiver_core::ReceiverState::Idle | receiver_core::ReceiverState::Failed
                    );
                if transport_lost {
                    metrics.record_timeout();
                    observer.on_timeout(metrics.timeout_count());
                } else if structure.name() == "connection-added" {
                    observer.on_transport_connected();
                }
            }
            _ => {}
        }
    }
}
