use gst::prelude::*;
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
        let parser = gst::ElementFactory::make(factory)
            .name(format!("{factory}-parser"))
            .build()
            .map_err(|error| PipelineError::ElementCreation {
            name: factory.to_owned(),
            reason: error.to_string(),
        })?;
        parser.set_property("config-interval", -1i32);
        Ok(parser)
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
            "videorate",
            "queue",
            "capsfilter",
        ];
        let supported = gst::ElementFactory::find(parser_name).is_some()
            && shared_elements.iter().all(|element| gst::ElementFactory::find(element).is_some());
        CodecSupport { supported, decoder_acceleration: DecoderAcceleration::Unknown }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gst::prelude::*;

    #[test]
    fn h265_parser_is_selected_when_available() {
        gst::init().unwrap();
        let factory = DefaultCodecPipelineFactory;
        if !factory.supports(VideoCodec::H265).supported {
            eprintln!("skipped: H.265 parser or shared GStreamer input elements are unavailable");
            return;
        }
        let parser = factory.create_parser(VideoCodec::H265).unwrap();
        assert_eq!(
            parser.factory().map(|factory| factory.name().to_string()),
            Some("h265parse".to_owned())
        );
        assert_eq!(parser.property::<i32>("config-interval"), -1);
    }

    #[test]
    fn codec_support_requires_the_shared_input_chain() {
        gst::init().unwrap();
        let factory = DefaultCodecPipelineFactory;
        let h264 = factory.supports(VideoCodec::H264);
        let h265 = factory.supports(VideoCodec::H265);
        assert_eq!(h264.supported, h265.supported);
    }
}
