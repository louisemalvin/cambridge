use std::sync::{Arc, Mutex};

use gst::prelude::*;
use gstreamer as gst;
use receiver_core::MediaSessionConfig;
use receiver_protocol::{PixelFormat, VideoCodec};
use tracing::{debug, warn};

use crate::{
    codec_branch::CodecPipelineFactory,
    decoder::log_decoder_pad,
    elements::{configure_bounded_queue, link, make},
    metrics::Metrics,
    pipeline::VideoSinkFactory,
    pipeline_event::PipelineObserver,
    PipelineError,
};

pub(crate) fn build_pipeline<F: VideoSinkFactory>(
    config: &MediaSessionConfig,
    sink_factory: &F,
    codec_factory: &dyn CodecPipelineFactory,
    observer: Arc<PipelineObserver>,
    metrics: Arc<Mutex<Metrics>>,
) -> Result<gst::Pipeline, PipelineError> {
    let support = codec_factory.supports(config.codec);
    if !support.supported {
        return Err(PipelineError::NoDecoder(config.codec));
    }
    let pipeline = gst::Pipeline::with_name("mobile-webcam-receiver");
    let source = make("udpsrc", "udp-source")?;
    let tsparse = make("tsparse", "mpegts-parse")?;
    let demux = make("tsdemux", "mpegts-demux")?;
    let demux_queue = make("queue", "demux-queue")?;
    let parser = codec_factory.create_parser(config.codec)?;
    let decoder = make("decodebin", "video-decoder")?;
    let convert = make("videoconvert", "video-convert")?;
    let scale = make("videoscale", "video-scale")?;
    let capsfilter = make("capsfilter", "raw-video-caps")?;
    let output_queue = make("queue", "output-queue")?;
    let sink = sink_factory.create_sink(config.output_format)?;

    source.set_property("address", "0.0.0.0");
    source.set_property("port", i32::from(config.media_port));
    source.set_property("timeout", config.udp_timeout_ms.saturating_mul(1_000_000));
    demux.set_property("latency", config.latency.demux_latency_ms);
    parser.set_property("config-interval", -1i32);
    configure_bounded_queue(&demux_queue, config.latency.output_queue_frames);
    configure_bounded_queue(&output_queue, config.latency.output_queue_frames);
    capsfilter.set_property("caps", raw_caps(config));
    sink.set_property("sync", false);

    pipeline
        .add_many([
            &source,
            &tsparse,
            &demux,
            &demux_queue,
            &parser,
            &decoder,
            &convert,
            &scale,
            &capsfilter,
            &output_queue,
            &sink,
        ])
        .map_err(|error| PipelineError::Pipeline(error.to_string()))?;
    link(&[&source, &tsparse, &demux])?;
    link(&[&demux_queue, &parser, &decoder])?;
    link(&[&convert, &scale, &capsfilter, &output_queue, &sink])?;

    connect_demux_pad(&demux, &demux_queue, config.codec, observer.clone());
    connect_decoder_pad(&decoder, &convert, config.codec, metrics.clone());
    add_source_probe(&source, metrics.clone())?;
    add_output_probe(&output_queue, observer, metrics)?;
    Ok(pipeline)
}

fn connect_demux_pad(
    demux: &gst::Element,
    queue: &gst::Element,
    expected_codec: VideoCodec,
    observer: Arc<PipelineObserver>,
) {
    let queue = queue.clone();
    demux.connect_pad_added(move |_demux, pad| {
        let Some(codec) = pad_codec(pad) else {
            return;
        };
        if codec != expected_codec {
            warn!(expected = %expected_codec, received = %codec, "rejecting unexpected MPEG-TS video pad");
            observer.on_wrong_codec(codec);
            return;
        }
        let Some(sink_pad) = queue.static_pad("sink") else {
            return;
        };
        if sink_pad.is_linked() {
            return;
        }
        if let Err(error) = pad.link(&sink_pad) {
            warn!(error = %error, "failed to link MPEG-TS video pad");
        }
    });
}

fn connect_decoder_pad(
    decoder: &gst::Element,
    convert: &gst::Element,
    codec: VideoCodec,
    metrics: Arc<Mutex<Metrics>>,
) {
    let convert = convert.clone();
    decoder.connect_pad_added(move |_decoder, pad| {
        let Some(caps) = pad.current_caps() else {
            return;
        };
        let Some(structure) = caps.structure(0) else {
            return;
        };
        if structure.name() != "video/x-raw" {
            return;
        }
        let Some(sink_pad) = convert.static_pad("sink") else {
            return;
        };
        if sink_pad.is_linked() {
            return;
        }
        if let Err(error) = pad.link(&sink_pad) {
            warn!(error = %error, "failed to link decoded video pad");
            return;
        }
        if let Some(decoder_name) = log_decoder_pad(codec, pad) {
            if let Ok(mut metrics) = metrics.lock() {
                metrics.set_decoder(decoder_name);
            }
        }
    });
}

fn add_source_probe(
    source: &gst::Element,
    metrics: Arc<Mutex<Metrics>>,
) -> Result<(), PipelineError> {
    let Some(src_pad) = source.static_pad("src") else {
        return Err(PipelineError::MissingElement("udpsrc src pad".to_owned()));
    };
    src_pad.add_probe(gst::PadProbeType::BUFFER, move |_pad, info| {
        if let Some(gst::PadProbeData::Buffer(buffer)) = info.data.as_ref() {
            if let Ok(mut metrics) = metrics.lock() {
                metrics.record_network_bytes(buffer.size());
            }
        }
        gst::PadProbeReturn::Ok
    });
    Ok(())
}

fn add_output_probe(
    output_queue: &gst::Element,
    observer: Arc<PipelineObserver>,
    metrics: Arc<Mutex<Metrics>>,
) -> Result<(), PipelineError> {
    let Some(sink_pad) = output_queue.static_pad("sink") else {
        return Err(PipelineError::MissingElement("output queue sink pad".to_owned()));
    };
    sink_pad.add_probe(gst::PadProbeType::BUFFER, move |_pad, _info| {
        let (first_frame, decoder) = metrics.lock().map_or((false, None), |mut metrics| {
            let first_frame = !metrics.has_first_frame();
            if first_frame {
                let decoder = metrics.decoder();
                metrics.record_first_frame(decoder);
            }
            (first_frame, metrics.decoder())
        });
        if first_frame {
            debug!(decoder = ?decoder, "received first decoded video frame");
        }
        observer.on_frame(false);
        gst::PadProbeReturn::Ok
    });
    Ok(())
}

fn pad_codec(pad: &gst::Pad) -> Option<VideoCodec> {
    let caps = pad.current_caps()?;
    let structure = caps.structure(0)?;
    match structure.name().as_str() {
        "video/x-h264" => Some(VideoCodec::H264),
        "video/x-h265" => Some(VideoCodec::H265),
        _ => None,
    }
}

fn raw_caps(config: &MediaSessionConfig) -> gst::Caps {
    let width = i32::try_from(config.profile.width).unwrap_or(i32::MAX);
    let height = i32::try_from(config.profile.height).unwrap_or(i32::MAX);
    let fps = i32::try_from(config.profile.fps).unwrap_or(i32::MAX);
    gst::Caps::builder("video/x-raw")
        .field("format", pixel_format_name(config.output_format))
        .field("width", width)
        .field("height", height)
        .field("framerate", gst::Fraction::new(fps, 1))
        .build()
}

fn pixel_format_name(format: PixelFormat) -> &'static str {
    match format {
        PixelFormat::Yuy2 => "YUY2",
        PixelFormat::Nv12 => "NV12",
        PixelFormat::I420 => "I420",
    }
}
