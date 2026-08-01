use gstreamer as gst;
use receiver_protocol::{DecoderAcceleration, VideoCodec};

use crate::PipelineError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CodecSupport {
    pub supported: bool,
    pub decoder_acceleration: DecoderAcceleration,
}

pub trait CodecPipelineFactory: Send + Sync {
    fn create_parser(&self, codec: VideoCodec) -> Result<gst::Element, PipelineError>;

    fn supports(&self, codec: VideoCodec) -> CodecSupport;
}

#[derive(Debug, Default, Clone, Copy)]
pub struct DefaultCodecPipelineFactory;

impl CodecPipelineFactory for DefaultCodecPipelineFactory {
    fn create_parser(&self, codec: VideoCodec) -> Result<gst::Element, PipelineError> {
        let factory = match codec {
            VideoCodec::H264 => "h264parse",
            VideoCodec::H265 => "h265parse",
        };
        gst::ElementFactory::make(factory).name(format!("{factory}-parser")).build().map_err(
            |error| PipelineError::ElementCreation {
                name: factory.to_owned(),
                reason: error.to_string(),
            },
        )
    }

    fn supports(&self, codec: VideoCodec) -> CodecSupport {
        let parser_name = match codec {
            VideoCodec::H264 => "h264parse",
            VideoCodec::H265 => "h265parse",
        };
        let shared_elements = [
            "udpsrc",
            "tsparse",
            "tsdemux",
            "decodebin",
            "videoconvert",
            "videoscale",
            "queue",
            "capsfilter",
        ];
        let supported = gst::ElementFactory::find(parser_name).is_some()
            && shared_elements.iter().all(|element| gst::ElementFactory::find(element).is_some());
        CodecSupport { supported, decoder_acceleration: DecoderAcceleration::Unknown }
    }
}
