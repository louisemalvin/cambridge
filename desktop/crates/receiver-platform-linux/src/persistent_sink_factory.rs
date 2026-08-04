use std::path::Path;

use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use receiver_gstreamer::{PipelineError, VideoSinkFactory};
use receiver_protocol::PixelFormat;

use crate::{LinuxVideoSinkFactory, PersistentVirtualCameraOutput};

pub struct PersistentVideoSinkFactory {
    virtual_camera: LinuxVideoSinkFactory,
    persistent_output: PersistentVirtualCameraOutput,
}

impl PersistentVideoSinkFactory {
    pub fn new(device: impl AsRef<Path>) -> Result<Self, crate::LinuxPlatformError> {
        let virtual_camera = LinuxVideoSinkFactory::new(&device)?;
        let persistent_output = PersistentVirtualCameraOutput::start(&device)?;
        Ok(Self { virtual_camera, persistent_output })
    }

    #[must_use]
    pub fn persistent_output(&self) -> PersistentVirtualCameraOutput {
        self.persistent_output.clone()
    }
}

impl VideoSinkFactory for PersistentVideoSinkFactory {
    fn create_sink(&self, format: PixelFormat) -> Result<gst::Element, PipelineError> {
        if !self.virtual_camera.supported_formats().contains(&format) {
            return Err(PipelineError::ElementConfiguration {
                name: "persistent-output-appsink".to_owned(),
                reason: format!("unsupported persistent output format: {format:?}"),
            });
        }
        let element = gst::ElementFactory::make("appsink")
            .name("persistent-output-appsink")
            .build()
            .map_err(|error| PipelineError::ElementCreation {
                name: "appsink".to_owned(),
                reason: error.to_string(),
            })?;
        let appsink = element.clone().downcast::<gst_app::AppSink>().map_err(|_| {
            PipelineError::ElementConfiguration {
                name: "appsink".to_owned(),
                reason: "GStreamer created a non-AppSink element".to_owned(),
            }
        })?;
        appsink.set_property("emit-signals", false);
        appsink.set_property("max-buffers", 1u32);
        appsink.set_property("drop", true);
        appsink.set_property("sync", false);
        let output = self.persistent_output.clone();
        appsink.set_callbacks(
            gst_app::AppSinkCallbacks::builder()
                .new_sample(move |appsink| {
                    let sample = appsink.pull_sample().map_err(|_| gst::FlowError::Eos)?;
                    output.push_live_sample(&sample).map_err(|_| gst::FlowError::Error)?;
                    Ok(gst::FlowSuccess::Ok)
                })
                .build(),
        );
        Ok(appsink.upcast())
    }

    fn set_standby(&self) -> Result<(), PipelineError> {
        self.persistent_output.set_standby().map_err(|error| PipelineError::ElementConfiguration {
            name: "persistent-output".to_owned(),
            reason: error,
        })
    }

    fn device(&self) -> String {
        self.virtual_camera.device()
    }

    fn supported_formats(&self) -> Vec<PixelFormat> {
        self.virtual_camera.supported_formats()
    }
}
