use std::path::Path;

use anyhow::Result;
use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use receiver_gstreamer::{PipelineError, VideoSinkFactory};
use receiver_platform_linux::{LinuxVideoSinkFactory, PersistentVirtualCameraOutput};
use receiver_protocol::PixelFormat;

use crate::preview::PreviewStore;

pub struct DesktopSinkFactory {
    virtual_camera: LinuxVideoSinkFactory,
    persistent_output: PersistentVirtualCameraOutput,
    preview: PreviewStore,
}

impl DesktopSinkFactory {
    pub fn new(device: impl AsRef<Path>) -> Result<Self> {
        Ok(Self {
            virtual_camera: LinuxVideoSinkFactory::new(&device)?,
            persistent_output: PersistentVirtualCameraOutput::start(&device)?,
            preview: PreviewStore::default(),
        })
    }

    pub fn preview_store(&self) -> PreviewStore {
        self.preview.clone()
    }

    pub fn persistent_output(&self) -> PersistentVirtualCameraOutput {
        self.persistent_output.clone()
    }
}

impl VideoSinkFactory for DesktopSinkFactory {
    fn create_sink(&self, _format: PixelFormat) -> Result<gst::Element, PipelineError> {
        let element = gst::ElementFactory::make("appsink")
            .name("virtual-camera-frame-sink")
            .build()
            .map_err(|error| PipelineError::ElementCreation {
                name: "appsink".to_owned(),
                reason: error.to_string(),
            })?;
        let appsink = element.clone().downcast::<gst_app::AppSink>().map_err(|_| {
            PipelineError::ElementConfiguration {
                name: "appsink".to_owned(),
                reason: "GStreamer created an element that is not an AppSink".to_owned(),
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

    fn create_preview_sink(&self) -> Result<Option<gst::Element>, PipelineError> {
        Ok(Some(self.preview.create_sink()?))
    }

    fn device(&self) -> String {
        self.virtual_camera.device()
    }

    fn supported_formats(&self) -> Vec<PixelFormat> {
        self.virtual_camera.supported_formats()
    }
}
