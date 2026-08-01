use gstreamer as gst;
use receiver_core::ReceiverError;
use receiver_protocol::{
    DecoderAcceleration, MediaCapabilities, OutputCapabilities, ReceiverCapabilities,
    SessionCapabilities, Transport, VideoCodec, VideoCodecCapability,
};

use crate::{CodecPipelineFactory, DefaultCodecPipelineFactory};

pub fn probe_capabilities(
    media_port: u16,
    device: impl Into<String>,
    pixel_formats: Vec<receiver_protocol::PixelFormat>,
) -> Result<ReceiverCapabilities, ReceiverError> {
    gst::init().map_err(|error| ReceiverError::GStreamer(error.to_string()))?;
    let factory = DefaultCodecPipelineFactory;
    let video_codecs = VideoCodec::ALL
        .into_iter()
        .map(|codec| {
            let support = factory.supports(codec);
            VideoCodecCapability {
                codec,
                supported: support.supported,
                decoder_acceleration: DecoderAcceleration::Unknown,
            }
        })
        .collect();
    Ok(ReceiverCapabilities {
        protocol_version: 1,
        media: MediaCapabilities { transport: Transport::MpegTsUdp, default_port: media_port },
        video_codecs,
        output: OutputCapabilities { device: device.into(), pixel_formats },
        session: SessionCapabilities { maximum_concurrent_sessions: 1, active: false },
    })
}
