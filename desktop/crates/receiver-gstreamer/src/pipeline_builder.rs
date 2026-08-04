use std::sync::{Arc, Mutex};

use gst::prelude::*;
use gstreamer as gst;
use receiver_core::{MediaSessionConfig, DEFAULT_LISTEN_ADDRESS};
use receiver_protocol::{PixelFormat, VideoCodec, SRT_KEY_LENGTH_BYTES};
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
    let source = make("srtsrc", "srt-source")?;
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

    configure_media_source(&source, config);
    connect_srt_stream_validation(&source, config.srt_endpoint.stream_id.clone(), observer.clone());
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
    connect_decoder_diagnostics(&decoder, config.codec, metrics.clone(), observer.clone());
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
        return Err(PipelineError::MissingElement("media source src pad".to_owned()));
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

fn configure_media_source(source: &gst::Element, config: &MediaSessionConfig) {
    let endpoint = &config.srt_endpoint;
    let port = endpoint.port;
    let uri = format!("srt://{DEFAULT_LISTEN_ADDRESS}:{port}");
    source.set_property("uri", uri);
    source.set_property_from_str("mode", "listener");
    source.set_property("localaddress", DEFAULT_LISTEN_ADDRESS.to_string());
    source.set_property("localport", u32::from(port));
    source.set_property("streamid", endpoint.stream_id.as_str());
    source.set_property("latency", i32::try_from(endpoint.latency_ms).unwrap_or(i32::MAX));
    source.set_property("passphrase", endpoint.passphrase.as_str());
    source.set_property_from_str("pbkeylen", &SRT_KEY_LENGTH_BYTES.to_string());
    source.set_property("authentication", true);
    source.set_property("auto-reconnect", true);
    source.set_property("keep-listening", true);
    source.set_property("wait-for-connection", true);
}

fn connect_srt_stream_validation(
    source: &gst::Element,
    expected_stream_id: String,
    observer: Arc<PipelineObserver>,
) {
    let accepted_observer = observer.clone();
    source.connect_closure(
        "caller-added",
        false,
        gst::glib::closure!(move |_source: gst::Element,
                                  _unused: i32,
                                  _address: gio::SocketAddress| {
            accepted_observer.on_transport_connected();
        }),
    );
    let rejected_observer = observer.clone();
    source.connect_closure(
        "caller-rejected",
        false,
        gst::glib::closure!(move |_source: gst::Element,
                                  _address: gio::SocketAddress,
                                  _stream_id: String| {
            rejected_observer.on_transport_rejected();
        }),
    );
    source.connect_closure(
        "caller-connecting",
        false,
        gst::glib::closure!(move |_source: gst::Element,
                                  _address: gio::SocketAddress,
                                  stream_id: String|
              -> bool {
            if stream_id == expected_stream_id {
                true
            } else {
                observer.on_transport_rejected();
                false
            }
        }),
    );
}

fn add_output_probe(
    output_queue: &gst::Element,
    observer: Arc<PipelineObserver>,
    metrics: Arc<Mutex<Metrics>>,
) -> Result<(), PipelineError> {
    let Some(sink_pad) = output_queue.static_pad("sink") else {
        return Err(PipelineError::MissingElement("output queue sink pad".to_owned()));
    };
    let output_queue = output_queue.clone();
    sink_pad.add_probe(gst::PadProbeType::BUFFER, move |_pad, _info| {
        let (first_frame, decoder, queue_pressure) =
            metrics.lock().map_or((false, None, None), |mut metrics| {
                let first_frame = metrics.record_decoded_frame();
                let decoder = metrics.decoder();
                let current_frames = output_queue.property::<u32>("current-level-buffers");
                let maximum_frames = output_queue.property::<u32>("max-size-buffers");
                let pressure_started = metrics.record_queue_depth(current_frames, maximum_frames);
                let queue_pressure = pressure_started.then_some((current_frames, maximum_frames));
                (first_frame, decoder, queue_pressure)
            });
        if first_frame {
            debug!(decoder = ?decoder, "received first decoded video frame");
        }
        if let Some((current_frames, maximum_frames)) = queue_pressure {
            observer.on_queue_pressure(current_frames, maximum_frames);
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
    use receiver_protocol::{SrtEndpoint, SrtMode, SrtTransportKind, VideoCodec, VideoProfile};
    use uuid::Uuid;

    const TEST_SRT_PORT: u16 = 5_000;
    const TEST_SRT_LATENCY_MS: u32 = 120;
    const TEST_SRT_KEY_LENGTH_BYTES: u16 = 32;

    fn config() -> MediaSessionConfig {
        MediaSessionConfig {
            session_id: Uuid::new_v4(),
            codec: VideoCodec::H264,
            profile: VideoProfile { width: 1_920, height: 1_080, fps: 30 },
            bitrate_bps: 8_000_000,
            output_format: PixelFormat::Yuy2,
            latency: LatencyConfig::default(),
            transport_timeout_ms: 2_000,
            srt_endpoint: SrtEndpoint {
                kind: SrtTransportKind::Srt,
                mode: SrtMode::Caller,
                host: "127.0.0.1".to_owned(),
                port: TEST_SRT_PORT,
                stream_id: "stream-test".to_owned(),
                latency_ms: TEST_SRT_LATENCY_MS,
                key_length_bytes: TEST_SRT_KEY_LENGTH_BYTES,
                passphrase: "test-passphrase".to_owned(),
            },
        }
    }

    #[test]
    fn srt_pipeline_uses_a_listener_with_encrypted_stream_identity() {
        gst::init().unwrap();
        if gst::ElementFactory::find("srtsrc").is_none() {
            eprintln!("skipped: GStreamer SRT source plugin is unavailable");
            return;
        }
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

        let source = pipeline.by_name("srt-source").expect("SRT source must be present");
        assert_eq!(source.property::<String>("uri"), "srt://0.0.0.0:5000");
        assert_eq!(source.property::<u32>("localport"), u32::from(TEST_SRT_PORT));
        assert_eq!(source.property::<String>("streamid"), "stream-test");
        assert_eq!(source.property::<i32>("latency"), i32::try_from(TEST_SRT_LATENCY_MS).unwrap(),);
        assert_eq!(source.property_value("pbkeylen").type_().name(), "GstSRTKeyLength");
        assert!(source.property::<bool>("authentication"));
        assert!(source.property::<bool>("keep-listening"));
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
    fn decoded_output_caps_match_the_negotiated_profile_dimensions() {
        gst::init().unwrap();
        let config = config();
        let caps = raw_caps(&config);
        let structure = caps.structure(0).unwrap();
        let expected_width = i32::try_from(config.profile.width).unwrap();
        let expected_height = i32::try_from(config.profile.height).unwrap();
        let expected_fps = i32::try_from(config.profile.fps).unwrap();

        assert_eq!(structure.get::<&str>("format"), Ok("YUY2"));
        assert_eq!(structure.get::<i32>("width"), Ok(expected_width));
        assert_eq!(structure.get::<i32>("height"), Ok(expected_height));
        assert_eq!(
            structure.get::<gst::Fraction>("framerate"),
            Ok(gst::Fraction::new(expected_fps, 1)),
        );
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
