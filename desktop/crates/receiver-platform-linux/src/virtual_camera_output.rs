use std::sync::{Arc, Mutex};

use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;

use crate::{validate_v4l2loopback, LinuxPlatformError};

pub const VIRTUAL_CAMERA_WIDTH: i32 = 1_920;
pub const VIRTUAL_CAMERA_HEIGHT: i32 = 1_080;
pub const VIRTUAL_CAMERA_FRAME_RATE: i32 = 30;
pub const STANDBY_FRAME_RATE: i32 = 5;
const OUTPUT_QUEUE_MAX_BUFFERS: u32 = 2;
const APP_SOURCE_MAX_BUFFERS: u32 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VirtualCameraOutputMode {
    Standby,
    Live,
}

#[derive(Clone)]
pub struct PersistentVirtualCameraOutput {
    state: Arc<Mutex<OutputState>>,
}

struct OutputState {
    pipeline: gst::Pipeline,
    appsrc: gst_app::AppSrc,
    selector: gst::Element,
    standby_pad: gst::Pad,
    live_pad: gst::Pad,
    mode: VirtualCameraOutputMode,
}

impl PersistentVirtualCameraOutput {
    pub fn start(device: impl AsRef<std::path::Path>) -> Result<Self, LinuxPlatformError> {
        let device = device.as_ref();
        validate_v4l2loopback(device)?;
        gst::init().map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))?;

        let pipeline = gst::Pipeline::with_name("mobile-webcam-virtual-camera");
        let standby_source = make_element("videotestsrc", "standby-source")?;
        let standby_convert = make_element("videoconvert", "standby-convert")?;
        let standby_scale = make_element("videoscale", "standby-scale")?;
        let standby_rate = make_element("videorate", "standby-rate")?;
        let standby_input_filter = make_element("capsfilter", "standby-input-caps")?;
        let standby_output_caps = make_element("capsfilter", "standby-output-caps")?;
        let standby_queue = make_element("queue", "standby-queue")?;
        let appsrc_element = make_element("appsrc", "live-source")?;
        let appsrc = appsrc_element.clone().downcast::<gst_app::AppSrc>().map_err(|_| {
            LinuxPlatformError::PersistentOutput(
                "GStreamer appsrc element has the wrong type".to_owned(),
            )
        })?;
        let live_convert = make_element("videoconvert", "live-convert")?;
        let live_scale = make_element("videoscale", "live-scale")?;
        let live_rate = make_element("videorate", "live-rate")?;
        let live_caps = make_element("capsfilter", "live-caps")?;
        let live_queue = make_element("queue", "live-queue")?;
        let selector = make_element("input-selector", "virtual-camera-selector")?;
        let sink = make_element("v4l2sink", "persistent-virtual-camera-sink")?;

        standby_source.set_property("is-live", true);
        standby_source.set_property_from_str("pattern", "black");
        standby_input_filter.set_property("caps", standby_input_caps());
        standby_output_caps.set_property("caps", output_caps());
        configure_queue(&standby_queue);

        appsrc.set_property("is-live", true);
        appsrc.set_property("format", gst::Format::Time);
        appsrc.set_property("block", false);
        appsrc.set_property("max-buffers", u64::from(APP_SOURCE_MAX_BUFFERS));
        appsrc.set_property("max-bytes", 0u64);
        appsrc.set_property_from_str("leaky-type", "downstream");
        live_caps.set_property("caps", output_caps());
        configure_queue(&live_queue);

        sink.set_property("device", device.to_string_lossy().as_ref());
        sink.set_property("sync", false);
        selector.set_property("sync-streams", false);

        pipeline
            .add_many([
                &standby_source,
                &standby_convert,
                &standby_scale,
                &standby_rate,
                &standby_input_filter,
                &standby_output_caps,
                &standby_queue,
                &appsrc_element,
                &live_convert,
                &live_scale,
                &live_rate,
                &live_caps,
                &live_queue,
                &selector,
                &sink,
            ])
            .map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))?;

        link(&[
            &standby_source,
            &standby_input_filter,
            &standby_convert,
            &standby_scale,
            &standby_rate,
            &standby_output_caps,
            &standby_queue,
        ])?;
        link(&[&appsrc_element, &live_convert, &live_scale, &live_rate, &live_caps, &live_queue])?;

        let standby_pad = request_selector_pad(&selector, &standby_queue)?;
        let live_pad = request_selector_pad(&selector, &live_queue)?;
        let selector_src = selector.static_pad("src").ok_or_else(|| {
            LinuxPlatformError::PersistentOutput("input-selector has no source pad".to_owned())
        })?;
        let sink_pad = sink.static_pad("sink").ok_or_else(|| {
            LinuxPlatformError::PersistentOutput("v4l2sink has no sink pad".to_owned())
        })?;
        selector_src.link(&sink_pad).map_err(|error| {
            LinuxPlatformError::PersistentOutput(format!("link persistent v4l2sink: {error}"))
        })?;
        selector.set_property("active-pad", &standby_pad);
        pipeline
            .set_state(gst::State::Playing)
            .map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))?;

        Ok(Self {
            state: Arc::new(Mutex::new(OutputState {
                pipeline,
                appsrc,
                selector,
                standby_pad,
                live_pad,
                mode: VirtualCameraOutputMode::Standby,
            })),
        })
    }

    pub fn push_live_sample(&self, sample: &gst::Sample) -> Result<(), String> {
        let mut state =
            self.state.lock().map_err(|_| "persistent output lock poisoned".to_owned())?;
        if state.mode != VirtualCameraOutputMode::Live {
            state.selector.set_property("active-pad", &state.live_pad);
            state.mode = VirtualCameraOutputMode::Live;
        }
        state.appsrc.push_sample(sample).map(|_| ()).map_err(|error| error.to_string())
    }

    pub fn set_standby(&self) -> Result<(), String> {
        let mut state =
            self.state.lock().map_err(|_| "persistent output lock poisoned".to_owned())?;
        if state.mode == VirtualCameraOutputMode::Standby {
            return Ok(());
        }
        state.selector.set_property("active-pad", &state.standby_pad);
        state.mode = VirtualCameraOutputMode::Standby;
        Ok(())
    }

    pub fn mode(&self) -> Result<VirtualCameraOutputMode, String> {
        self.state
            .lock()
            .map(|state| state.mode)
            .map_err(|_| "persistent output lock poisoned".to_owned())
    }

    pub fn stop(&self) -> Result<(), String> {
        let state = self.state.lock().map_err(|_| "persistent output lock poisoned".to_owned())?;
        state.pipeline.set_state(gst::State::Null).map_err(|error| error.to_string())?;
        Ok(())
    }
}

impl Drop for OutputState {
    fn drop(&mut self) {
        let _ = self.pipeline.set_state(gst::State::Null);
    }
}

fn make_element(factory: &str, name: &str) -> Result<gst::Element, LinuxPlatformError> {
    gst::ElementFactory::make(factory)
        .name(name)
        .build()
        .map_err(|error| LinuxPlatformError::PersistentOutput(format!("create {factory}: {error}")))
}

fn link(elements: &[&gst::Element]) -> Result<(), LinuxPlatformError> {
    gst::Element::link_many(elements)
        .map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))
}

fn configure_queue(queue: &gst::Element) {
    queue.set_property("max-size-buffers", OUTPUT_QUEUE_MAX_BUFFERS);
    queue.set_property("max-size-bytes", 0u32);
    queue.set_property("max-size-time", 0u64);
    queue.set_property_from_str("leaky", "downstream");
}

fn request_selector_pad(
    selector: &gst::Element,
    branch: &gst::Element,
) -> Result<gst::Pad, LinuxPlatformError> {
    let pad = selector.request_pad_simple("sink_%u").ok_or_else(|| {
        LinuxPlatformError::PersistentOutput(
            "input-selector could not allocate a sink pad".to_owned(),
        )
    })?;
    let branch_pad = branch.static_pad("src").ok_or_else(|| {
        LinuxPlatformError::PersistentOutput(format!("{} has no source pad", branch.name()))
    })?;
    branch_pad.link(&pad).map_err(|error| {
        LinuxPlatformError::PersistentOutput(format!("link input-selector branch: {error}"))
    })?;
    Ok(pad)
}

fn standby_input_caps() -> gst::Caps {
    gst::Caps::builder("video/x-raw")
        .field("width", VIRTUAL_CAMERA_WIDTH)
        .field("height", VIRTUAL_CAMERA_HEIGHT)
        .field("framerate", gst::Fraction::new(STANDBY_FRAME_RATE, 1))
        .build()
}

fn output_caps() -> gst::Caps {
    gst::Caps::builder("video/x-raw")
        .field("format", "YUY2")
        .field("width", VIRTUAL_CAMERA_WIDTH)
        .field("height", VIRTUAL_CAMERA_HEIGHT)
        .field("framerate", gst::Fraction::new(VIRTUAL_CAMERA_FRAME_RATE, 1))
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standby_caps_use_the_fixed_virtual_camera_format() {
        gst::init().unwrap();
        let caps = output_caps();
        let structure = caps.structure(0).unwrap();
        assert_eq!(structure.get::<&str>("format"), Ok("YUY2"));
        assert_eq!(structure.get::<i32>("width"), Ok(VIRTUAL_CAMERA_WIDTH));
        assert_eq!(structure.get::<i32>("height"), Ok(VIRTUAL_CAMERA_HEIGHT));
        assert_eq!(
            structure.get::<gst::Fraction>("framerate"),
            Ok(gst::Fraction::new(VIRTUAL_CAMERA_FRAME_RATE, 1)),
        );
    }

    #[test]
    fn output_mode_has_explicit_standby_and_live_states() {
        assert_ne!(VirtualCameraOutputMode::Standby, VirtualCameraOutputMode::Live);
    }
}
