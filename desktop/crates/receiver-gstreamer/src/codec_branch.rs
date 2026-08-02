use gst::prelude::*;
use gstreamer as gst;
use receiver_protocol::{DecoderAcceleration, VideoCodec};

use crate::PipelineError;

const PARSER_CONFIG_INTERVAL_EVERY_KEYFRAME: i32 = -1;

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
        parser.set_property("config-interval", PARSER_CONFIG_INTERVAL_EVERY_KEYFRAME);
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
    fn codec_parsers_repeat_configuration_on_keyframes() {
        gst::init().unwrap();
        let factory = DefaultCodecPipelineFactory;
        for codec in VideoCodec::ALL {
            if !factory.supports(codec).supported {
                eprintln!("skipped: parser or shared GStreamer input elements are unavailable for {codec}");
                continue;
            }
            let parser = factory.create_parser(codec).unwrap();
            let expected_factory = match codec {
                VideoCodec::H264 => "h264parse",
                VideoCodec::H265 => "h265parse",
            };
            assert_eq!(
                parser.factory().map(|factory| factory.name().to_string()),
                Some(expected_factory.to_owned())
            );
            assert_eq!(
                parser.property::<i32>("config-interval"),
                PARSER_CONFIG_INTERVAL_EVERY_KEYFRAME,
            );
        }
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
