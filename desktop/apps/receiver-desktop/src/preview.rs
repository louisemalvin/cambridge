use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};

use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use gstreamer_video as gst_video;
use receiver_gstreamer::PipelineError;
use tracing::info;

const PREVIEW_MAX_BUFFERS: u32 = 1;

#[derive(Clone)]
pub struct PreviewStore {
    frame: Arc<Mutex<Option<PreviewFrame>>>,
    received_frame: Arc<AtomicBool>,
}

impl Default for PreviewStore {
    fn default() -> Self {
        Self { frame: Arc::new(Mutex::new(None)), received_frame: Arc::new(AtomicBool::new(false)) }
    }
}

#[derive(Debug, Clone)]
pub struct PreviewFrame {
    pub width: i32,
    pub height: i32,
    pub stride: usize,
    pub pixels: Vec<u8>,
}

impl PreviewStore {
    pub fn validate() -> Result<(), PipelineError> {
        for factory in ["appsink", "tee", "videoconvert"] {
            if gst::ElementFactory::find(factory).is_none() {
                return Err(PipelineError::MissingElement(factory.to_owned()));
            }
        }
        Ok(())
    }

    pub fn take_latest(&self) -> Option<PreviewFrame> {
        self.frame.lock().ok().and_then(|mut frame| frame.take())
    }

    fn publish(&self, frame: PreviewFrame) {
        if let Ok(mut latest) = self.frame.lock() {
            *latest = Some(frame);
        }
        if !self.received_frame.swap(true, Ordering::Relaxed) {
            info!("desktop preview received first decoded frame");
        }
    }

    pub fn create_sink(&self) -> Result<gst::Element, PipelineError> {
        let element =
            gst::ElementFactory::make("appsink").name("preview-sink").build().map_err(|error| {
                PipelineError::ElementCreation {
                    name: "appsink".to_owned(),
                    reason: error.to_string(),
                }
            })?;
        let appsink = element.clone().downcast::<gst_app::AppSink>().map_err(|_| {
            PipelineError::ElementConfiguration {
                name: "appsink".to_owned(),
                reason: "GStreamer created an element that is not an AppSink".to_owned(),
            }
        })?;
        appsink.set_property("emit-signals", false);
        appsink.set_property("max-buffers", PREVIEW_MAX_BUFFERS);
        appsink.set_property("drop", true);
        appsink.set_property("sync", false);

        let store = self.clone();
        appsink.set_callbacks(
            gst_app::AppSinkCallbacks::builder()
                .new_sample(move |appsink| {
                    let sample = appsink.pull_sample().map_err(|_| gst::FlowError::Eos)?;
                    let frame = frame_from_sample(&sample)?;
                    store.publish(frame);
                    Ok(gst::FlowSuccess::Ok)
                })
                .build(),
        );
        Ok(appsink.upcast())
    }
}

fn frame_from_sample(sample: &gst::Sample) -> Result<PreviewFrame, gst::FlowError> {
    let caps = sample.caps().ok_or(gst::FlowError::NotNegotiated)?;
    let info = gst_video::VideoInfo::from_caps(caps).map_err(|_| gst::FlowError::NotNegotiated)?;
    let width = i32::try_from(info.width()).map_err(|_| gst::FlowError::Error)?;
    let height = i32::try_from(info.height()).map_err(|_| gst::FlowError::Error)?;
    let height_usize = usize::try_from(info.height()).map_err(|_| gst::FlowError::Error)?;
    let stride = usize::try_from(*info.stride().first().ok_or(gst::FlowError::Error)?)
        .map_err(|_| gst::FlowError::Error)?;
    let buffer = sample.buffer().ok_or(gst::FlowError::Error)?;
    let mapped = buffer.map_readable().map_err(|_| gst::FlowError::Error)?;
    let required_size = stride.checked_mul(height_usize).ok_or(gst::FlowError::Error)?;
    if mapped.size() < required_size {
        return Err(gst::FlowError::Error);
    }
    Ok(PreviewFrame { width, height, stride, pixels: mapped.as_slice()[..required_size].to_vec() })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn copies_a_rgba_sample_into_the_latest_frame_slot() {
        gst::init().unwrap();
        let caps = gst::Caps::builder("video/x-raw")
            .field("format", "RGBA")
            .field("width", 2i32)
            .field("height", 1i32)
            .field("framerate", gst::Fraction::new(30, 1))
            .build();
        let pixels = vec![1, 2, 3, 4, 5, 6, 7, 8];
        let buffer = gst::Buffer::from_mut_slice(pixels.clone());
        let sample = gst::Sample::builder().caps(&caps).buffer(&buffer).build();

        let store = PreviewStore::default();
        store.publish(frame_from_sample(&sample).unwrap());
        let frame = store.take_latest().unwrap();

        assert_eq!(frame.width, 2);
        assert_eq!(frame.height, 1);
        assert_eq!(frame.stride, 8);
        assert_eq!(frame.pixels, pixels);
    }
}
