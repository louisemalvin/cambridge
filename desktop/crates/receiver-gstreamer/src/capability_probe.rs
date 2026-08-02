use gstreamer as gst;
use receiver_core::ReceiverError;
use receiver_protocol::{
    DecoderAcceleration, MediaCapabilities, MediaPortAssignment, OutputCapabilities,
    ReceiverCapabilities, SessionCapabilities, Transport, VideoCodec, VideoCodecCapability,
    MAXIMUM_CONCURRENT_SESSIONS, PROTOCOL_VERSION,
};

use crate::{CodecPipelineFactory, DefaultCodecPipelineFactory};

pub fn probe_capabilities(
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
        protocol_version: PROTOCOL_VERSION,
        media: MediaCapabilities {
            transport: Transport::MpegTsUdp,
            port_assignment: MediaPortAssignment::PerSession,
        },
        video_codecs,
        output: OutputCapabilities { device: device.into(), pixel_formats },
        session: SessionCapabilities {
            maximum_concurrent_sessions: MAXIMUM_CONCURRENT_SESSIONS,
            active: false,
        },
    })
}
