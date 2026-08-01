use std::path::Path;

use anyhow::Result;
use gstreamer as gst;
use receiver_gstreamer::{PipelineError, VideoSinkFactory};
use receiver_platform_linux::LinuxVideoSinkFactory;
use receiver_protocol::PixelFormat;

use crate::preview::PreviewStore;

pub struct DesktopSinkFactory {
    virtual_camera: LinuxVideoSinkFactory,
    preview: PreviewStore,
}

impl DesktopSinkFactory {
    pub fn new(device: impl AsRef<Path>) -> Result<Self> {
        Ok(Self {
            virtual_camera: LinuxVideoSinkFactory::new(device)?,
            preview: PreviewStore::default(),
        })
    }

    pub fn preview_store(&self) -> PreviewStore {
        self.preview.clone()
    }
}

impl VideoSinkFactory for DesktopSinkFactory {
    fn create_sink(&self, format: PixelFormat) -> Result<gst::Element, PipelineError> {
        self.virtual_camera.create_sink(format)
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
