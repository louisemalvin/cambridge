use gst::prelude::*;
use gstreamer as gst;
use receiver_protocol::VideoCodec;
use tracing::debug;

pub fn log_decoder_pad(codec: VideoCodec, pad: &gst::Pad) -> Option<String> {
    let parent = pad.parent_element()?;
    let decoder_name = parent.name().to_string();
    debug!(codec = %codec, decoder = %decoder_name, "GStreamer decodebin produced a video pad");
    Some(decoder_name)
}
