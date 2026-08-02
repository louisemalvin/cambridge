use std::sync::{Arc, Mutex};

use gst::prelude::*;
use gstreamer as gst;
use receiver_core::MediaSessionConfig;
use receiver_protocol::{PixelFormat, VideoCodec};
use tracing::{debug, warn};

use crate::{
    codec_branch::CodecPipelineFactory,
    decoder::{connect_decoder_diagnostics, log_decoded_pad},
    elements::{configure_bounded_queue, link, make},
    metrics::Metrics,
    pipeline::VideoSinkFactory,
    pipeline_event::PipelineObserver,
    PipelineError,
};

const UDP_RECEIVE_BUFFER_SIZE_BYTES: i32 = 1_000_000;
const MPEG_TS_PACKET_SIZE_BYTES: i32 = 188;
const NANOSECONDS_PER_MILLISECOND: u64 = 1_000_000;

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
    let parser = codec_factory.create_parser(config.codec)?;
    let decoder = make("decodebin", "video-decoder")?;
    let convert = make("videoconvert", "video-convert")?;
    let scale = make("videoscale", "video-scale")?;
    let rate = make("videorate", "video-rate")?;
    let capsfilter = make("capsfilter", "raw-video-caps")?;
    let output_queue = make("queue", "output-queue")?;
    let sink = sink_factory.create_sink(config.output_format)?;
    let preview_sink = sink_factory.create_preview_sink()?;

    source.set_property("address", "0.0.0.0");
    source.set_property("port", i32::from(config.media_port));
    source.set_property("caps", mpeg_ts_caps());
    source.set_property("buffer-size", UDP_RECEIVE_BUFFER_SIZE_BYTES);
    source
        .set_property("timeout", config.udp_timeout_ms.saturating_mul(NANOSECONDS_PER_MILLISECOND));
    let demux_latency_ms = i32::try_from(config.latency.demux_latency_ms).unwrap_or(i32::MAX);
    demux.set_property("latency", demux_latency_ms);
    configure_bounded_queue(&output_queue, config.latency.output_queue_frames);
    capsfilter.set_property("caps", raw_caps(config));
    sink.set_property("sync", false);

    pipeline
        .add_many([
            &source,
            &tsparse,
            &demux,
            &parser,
            &decoder,
            &convert,
            &scale,
            &rate,
            &capsfilter,
            &output_queue,
            &sink,
        ])
        .map_err(|error| PipelineError::Pipeline(error.to_string()))?;
    link(&[&source, &tsparse, &demux])?;
    link(&[&parser, &decoder])?;
    build_output_branches(
        &pipeline,
        &OutputElements {
            convert: &convert,
            scale: &scale,
            rate: &rate,
            capsfilter: &capsfilter,
            output_queue: &output_queue,
            sink: &sink,
        },
        preview_sink,
        config,
    )?;

    connect_demux_pad(&demux, &parser, config.codec, observer.clone());
    connect_decoder_diagnostics(&decoder, config.codec, metrics.clone());
    connect_decoder_pad(&decoder, &convert, config.codec);
    add_source_probe(&source, metrics.clone())?;
    add_output_probe(&output_queue, observer, metrics)?;
    Ok(pipeline)
}

fn build_output_branches(
    pipeline: &gst::Pipeline,
    output: &OutputElements<'_>,
    preview_sink: Option<gst::Element>,
    config: &MediaSessionConfig,
) -> Result<(), PipelineError> {
    let Some(preview_sink) = preview_sink else {
        return link(&[
            output.convert,
            output.scale,
            output.rate,
            output.capsfilter,
            output.output_queue,
            output.sink,
        ]);
    };

    let tee = make("tee", "raw-video-tee")?;
    let preview_queue = make("queue", "preview-queue")?;
    let preview_convert = make("videoconvert", "preview-video-convert")?;
    let preview_capsfilter = make("capsfilter", "preview-video-caps")?;
    configure_bounded_queue(&preview_queue, config.latency.output_queue_frames);
    preview_capsfilter.set_property("caps", preview_caps());
    preview_sink.set_property("sync", false);
    pipeline
        .add_many([&tee, &preview_queue, &preview_convert, &preview_capsfilter, &preview_sink])
        .map_err(|error| PipelineError::Pipeline(error.to_string()))?;

    link(&[output.convert, &tee])?;
    link_tee_branch(&tee, output.scale)?;
    link_tee_branch(&tee, &preview_queue)?;
    link(&[output.scale, output.rate, output.capsfilter, output.output_queue, output.sink])?;
    link(&[&preview_queue, &preview_convert, &preview_capsfilter, &preview_sink])
}

struct OutputElements<'a> {
    convert: &'a gst::Element,
    scale: &'a gst::Element,
    rate: &'a gst::Element,
    capsfilter: &'a gst::Element,
    output_queue: &'a gst::Element,
    sink: &'a gst::Element,
}

fn link_tee_branch(tee: &gst::Element, branch: &gst::Element) -> Result<(), PipelineError> {
    let Some(source_pad) = tee.request_pad_simple("src_%u") else {
        return Err(PipelineError::MissingElement(format!("{} request src pad", tee.name())));
    };
    let Some(sink_pad) = branch.static_pad("sink") else {
        return Err(PipelineError::MissingElement(format!("{} sink pad", branch.name())));
    };
    source_pad.link(&sink_pad).map(|_| ()).map_err(|error| PipelineError::Link(error.to_string()))
}

fn connect_demux_pad(
    demux: &gst::Element,
    parser: &gst::Element,
    expected_codec: VideoCodec,
    observer: Arc<PipelineObserver>,
) {
    let parser = parser.clone();
    demux.connect_pad_added(move |_demux, pad| {
        let Some(codec) = pad_codec(pad) else {
            return;
        };
        if codec != expected_codec {
            warn!(expected = %expected_codec, received = %codec, "rejecting unexpected MPEG-TS video pad");
            observer.on_wrong_codec(codec);
            return;
        }
        let Some(sink_pad) = parser.static_pad("sink") else {
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

fn connect_decoder_pad(decoder: &gst::Element, convert: &gst::Element, codec: VideoCodec) {
    let convert = convert.clone();
    decoder.connect_pad_added(move |_decoder, pad| {
        let Some(caps) = pad_caps(pad) else {
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
        log_decoded_pad(codec, &caps);
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
    let caps = pad_caps(pad)?;
    let structure = caps.structure(0)?;
    match structure.name().as_str() {
        "video/x-h264" => Some(VideoCodec::H264),
        "video/x-h265" => Some(VideoCodec::H265),
        _ => None,
    }
}

fn pad_caps(pad: &gst::Pad) -> Option<gst::Caps> {
    let caps = pad.current_caps().unwrap_or_else(|| pad.query_caps(None));
    if caps.is_any() || caps.is_empty() {
        None
    } else {
        Some(caps)
    }
}

fn mpeg_ts_caps() -> gst::Caps {
    gst::Caps::builder("video/mpegts")
        .field("systemstream", true)
        .field("packetsize", MPEG_TS_PACKET_SIZE_BYTES)
        .build()
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

fn preview_caps() -> gst::Caps {
    gst::Caps::builder("video/x-raw").field("format", "RGBA").build()
}

fn pixel_format_name(format: PixelFormat) -> &'static str {
    match format {
        PixelFormat::Yuy2 => "YUY2",
        PixelFormat::Nv12 => "NV12",
        PixelFormat::I420 => "I420",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{DefaultCodecPipelineFactory, FakesinkFactory};
    use receiver_core::LatencyConfig;
    use receiver_protocol::{VideoCodec, VideoProfile};
    use uuid::Uuid;

    fn config() -> MediaSessionConfig {
        MediaSessionConfig {
            session_id: Uuid::new_v4(),
            codec: VideoCodec::H264,
            profile: VideoProfile { width: 1_920, height: 1_080, fps: 30 },
            bitrate_bps: 8_000_000,
            media_port: 55_011,
            output_format: PixelFormat::Yuy2,
            latency: LatencyConfig::default(),
            udp_timeout_ms: 2_000,
        }
    }

    #[test]
    fn source_caps_describe_standard_mpeg_ts_packets() {
        gst::init().unwrap();
        let caps = mpeg_ts_caps();
        let structure = caps.structure(0).unwrap();

        assert_eq!(structure.name(), "video/mpegts");
        assert_eq!(structure.get::<bool>("systemstream"), Ok(true));
        assert_eq!(structure.get::<i32>("packetsize"), Ok(MPEG_TS_PACKET_SIZE_BYTES),);
    }

    #[test]
    fn output_chain_converts_frame_rate_before_fixed_output_caps() {
        gst::init().unwrap();
        let config = config();
        let observer = PipelineObserver::new(config.session_id, config.codec);
        let metrics = Arc::new(Mutex::new(Metrics::default()));
        let pipeline = build_pipeline(
            &config,
            &FakesinkFactory,
            &DefaultCodecPipelineFactory,
            observer,
            metrics,
        )
        .unwrap();

        let rate = pipeline.by_name("video-rate").expect("videorate must be present");
        let capsfilter = pipeline.by_name("raw-video-caps").unwrap();
        let rate_source = rate.static_pad("src").unwrap();
        let caps_sink = capsfilter.static_pad("sink").unwrap();

        assert_eq!(rate_source.peer().as_ref(), Some(&caps_sink));
    }

    #[test]
    fn compressed_video_is_not_routed_through_a_leaky_queue() {
        gst::init().unwrap();
        let config = config();
        let observer = PipelineObserver::new(config.session_id, config.codec);
        let metrics = Arc::new(Mutex::new(Metrics::default()));
        let pipeline = build_pipeline(
            &config,
            &FakesinkFactory,
            &DefaultCodecPipelineFactory,
            observer,
            metrics,
        )
        .unwrap();

        assert!(pipeline.by_name("demux-queue").is_none());
        assert!(pipeline.by_name("output-queue").is_some());
    }

    #[test]
    fn preview_caps_only_require_the_display_pixel_format() {
        gst::init().unwrap();
        let caps = preview_caps();
        let structure = caps.structure(0).unwrap();

        assert_eq!(structure.get::<&str>("format"), Ok("RGBA"));
        assert!(!structure.has_field("width"));
        assert!(!structure.has_field("height"));
        assert!(!structure.has_field("framerate"));
    }
}
