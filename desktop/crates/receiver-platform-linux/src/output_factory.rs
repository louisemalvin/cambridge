use std::path::{Path, PathBuf};

use gst::prelude::*;
use gstreamer as gst;
use receiver_gstreamer::{PipelineError, VideoSinkFactory};
use receiver_protocol::PixelFormat;

use crate::{validate_v4l2loopback, LinuxPlatformError};

pub struct LinuxVideoSinkFactory {
    device: PathBuf,
    formats: Vec<PixelFormat>,
}

impl LinuxVideoSinkFactory {
    pub fn new(device: impl AsRef<Path>) -> Result<Self, LinuxPlatformError> {
        let device = device.as_ref().to_path_buf();
        validate_v4l2loopback(&device)?;
        Ok(Self { device, formats: vec![PixelFormat::Yuy2, PixelFormat::Nv12, PixelFormat::I420] })
    }
}

impl VideoSinkFactory for LinuxVideoSinkFactory {
    fn create_sink(&self, format: PixelFormat) -> Result<gst::Element, PipelineError> {
        if !self.formats.contains(&format) {
            return Err(PipelineError::ElementConfiguration {
                name: "v4l2sink".to_owned(),
                reason: LinuxPlatformError::UnsupportedOutputFormat(format!("{format:?}"))
                    .to_string(),
            });
        }
        let sink = gst::ElementFactory::make("v4l2sink")
            .name("virtual-camera-sink")
            .build()
            .map_err(|error| PipelineError::ElementCreation {
                name: "v4l2sink".to_owned(),
                reason: error.to_string(),
            })?;
        sink.set_property("device", self.device.to_string_lossy().as_ref());
        sink.set_property("sync", false);
        Ok(sink)
    }

    fn device(&self) -> String {
        self.device.to_string_lossy().into_owned()
    }

    fn supported_formats(&self) -> Vec<PixelFormat> {
        self.formats.clone()
    }
}
