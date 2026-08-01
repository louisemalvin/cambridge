use gst::prelude::*;
use gstreamer as gst;
use receiver_protocol::VideoCodec;
use tracing::debug;

pub fn log_decoder_pad(codec: VideoCodec, pad: &gst::Pad) -> Option<String> {
    let parent = pad.parent_element()?;
    let element_name = parent.name().to_string();
    let factory_name = parent.factory().map(|factory| factory.name().to_string());
    let decoder_name = factory_name
        .as_deref()
        .map_or_else(|| element_name.clone(), |factory| format!("{element_name} ({factory})"));
    debug!(
        codec = %codec,
        decoder = %decoder_name,
        factory = ?factory_name,
        "GStreamer decodebin produced a video pad"
    );
    Some(decoder_name)
}
