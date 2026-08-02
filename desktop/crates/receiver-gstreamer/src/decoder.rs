use gst::prelude::*;
use gstreamer as gst;
use receiver_protocol::VideoCodec;
use std::sync::{Arc, Mutex};
use tracing::debug;

use crate::{metrics::Metrics, pipeline_event::PipelineObserver};

pub fn connect_decoder_diagnostics(
    decodebin: &gst::Element,
    codec: VideoCodec,
    metrics: Arc<Mutex<Metrics>>,
    observer: Arc<PipelineObserver>,
) {
    let Ok(decodebin) = decodebin.clone().downcast::<gst::Bin>() else {
        return;
    };
    decodebin.connect_deep_element_added(move |_decodebin, _sub_bin, element| {
        let Some(factory) = element.factory() else {
            return;
        };
        let Some(class) = factory.metadata("klass") else {
            return;
        };
        if !class.contains("Decoder/Video") {
            return;
        }
        let factory_name = factory.name().to_string();
        let decoder_name = format!("{} ({factory_name})", element.name());
        let selected = metrics.lock().is_ok_and(|mut metrics| metrics.set_decoder(decoder_name.clone()));
        if selected {
            observer.on_decoder_selected(decoder_name.clone());
        }
        debug!(codec = %codec, decoder = %decoder_name, factory = %factory_name, "GStreamer selected video decoder");
    });
}

pub fn log_decoded_pad(codec: VideoCodec, caps: &gst::Caps) {
    debug!(
        codec = %codec,
        caps = %caps,
        "GStreamer decodebin produced a raw video pad"
    );
}
