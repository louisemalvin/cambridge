use gst::prelude::*;
use gstreamer as gst;

use crate::PipelineError;

pub fn make(factory: &str, name: &str) -> Result<gst::Element, PipelineError> {
    gst::ElementFactory::make(factory).name(name).build().map_err(|error| {
        PipelineError::ElementCreation { name: name.to_owned(), reason: error.to_string() }
    })
}

pub fn link(elements: &[&gst::Element]) -> Result<(), PipelineError> {
    gst::Element::link_many(elements).map_err(|error| PipelineError::Link(error.to_string()))
}

pub fn configure_bounded_queue(queue: &gst::Element, frames: u32) {
    queue.set_property("max-size-buffers", frames);
    queue.set_property("max-size-bytes", 0u32);
    queue.set_property("max-size-time", 0u64);
    queue.set_property_from_str("leaky", "downstream");
}
