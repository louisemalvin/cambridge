use std::{
    net::{Ipv4Addr, UdpSocket},
    os::fd::OwnedFd,
    sync::{Arc, Mutex},
};

use gst::prelude::*;
use gstreamer as gst;
use receiver_core::{
    MediaReceiver, MediaSessionConfig, ReceiverError, ReceiverState, MEDIA_PORT_RANGE_END,
    MEDIA_PORT_RANGE_SIZE, MEDIA_PORT_RANGE_START, PORT_UNASSIGNED,
};
use receiver_protocol::PixelFormat;

use crate::{
    codec_branch::DefaultCodecPipelineFactory, metrics::Metrics, pipeline_builder::build_pipeline,
    pipeline_bus::poll, pipeline_event::PipelineObserver, PipelineError,
};

pub trait VideoSinkFactory: Send + Sync {
    fn create_sink(&self, format: PixelFormat) -> Result<gst::Element, PipelineError>;

    fn create_preview_sink(&self) -> Result<Option<gst::Element>, PipelineError> {
        Ok(None)
    }

    fn device(&self) -> String;

    fn supported_formats(&self) -> Vec<PixelFormat>;
}

#[derive(Debug, Default, Clone, Copy)]
pub struct FakesinkFactory;

impl VideoSinkFactory for FakesinkFactory {
    fn create_sink(&self, _format: PixelFormat) -> Result<gst::Element, PipelineError> {
        gst::ElementFactory::make("fakesink").name("fake-video-sink").build().map_err(|error| {
            PipelineError::ElementCreation {
                name: "fakesink".to_owned(),
                reason: error.to_string(),
            }
        })
    }

    fn device(&self) -> String {
        "fakesink".to_owned()
    }

    fn supported_formats(&self) -> Vec<PixelFormat> {
        vec![PixelFormat::Yuy2, PixelFormat::Nv12, PixelFormat::I420]
    }
}

pub struct GStreamerReceiver<F> {
    sink_factory: F,
    pipeline: Option<gst::Pipeline>,
    observer: Option<Arc<PipelineObserver>>,
    metrics: Arc<Mutex<Metrics>>,
    codec_factory: DefaultCodecPipelineFactory,
}

impl<F> GStreamerReceiver<F>
where
    F: VideoSinkFactory,
{
    pub fn new(sink_factory: F) -> Result<Self, ReceiverError> {
        gst::init().map_err(|error| ReceiverError::GStreamer(error.to_string()))?;
        Ok(Self {
            sink_factory,
            pipeline: None,
            observer: None,
            metrics: Arc::new(Mutex::new(Metrics::default())),
            codec_factory: DefaultCodecPipelineFactory,
        })
    }

    pub fn take_events(&self) -> Vec<receiver_core::ReceiverEvent> {
        self.observer.as_ref().map_or_else(Vec::new, |observer| observer.take_events())
    }

    pub fn sink_factory(&self) -> &F {
        &self.sink_factory
    }

    fn poll_bus(&self) {
        let Some(pipeline) = self.pipeline.as_ref() else {
            return;
        };
        let Some(bus) = pipeline.bus() else {
            return;
        };
        let Some(observer) = self.observer.as_ref() else {
            return;
        };
        if let Ok(mut metrics) = self.metrics.lock() {
            let was_timed_out = observer.state() == ReceiverState::TimedOut;
            let result = poll(&bus, observer, &mut metrics);
            if result.timed_out && !was_timed_out {
                recover_pipeline_after_timeout(pipeline);
            }
        }
    }
}

fn recover_pipeline_after_timeout(pipeline: &gst::Pipeline) {
    tracing::info!("resetting GStreamer pipeline after UDP timeout");
    if let Err(error) = pipeline.set_state(gst::State::Ready) {
        tracing::warn!(%error, "failed to reset GStreamer pipeline to Ready after UDP timeout");
        return;
    }
    if let Err(error) = pipeline.set_state(gst::State::Playing) {
        tracing::warn!(%error, "failed to resume GStreamer pipeline after UDP timeout");
    }
}

impl<F> MediaReceiver for GStreamerReceiver<F>
where
    F: VideoSinkFactory + 'static,
{
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<u16, ReceiverError> {
        if self.pipeline.is_some() {
            return Err(ReceiverError::MediaPreparation(
                "a GStreamer pipeline is already prepared".to_owned(),
            ));
        }
        let observer = PipelineObserver::new(config.session_id, config.codec);
        if let Ok(mut metrics) = self.metrics.lock() {
            metrics.reset();
        }
        let media_socket = if config.media_port == PORT_UNASSIGNED {
            Some(bind_session_socket(config.session_id)?)
        } else {
            None
        };
        let pipeline = build_pipeline(
            &config,
            &self.sink_factory,
            &self.codec_factory,
            observer.clone(),
            self.metrics.clone(),
        )
        .map_err(|error| ReceiverError::MediaPreparation(error.to_string()))?;
        if let Some((socket, _port)) = media_socket.as_ref() {
            let source = pipeline.by_name("udp-source").ok_or_else(|| {
                ReceiverError::MediaPreparation("GStreamer UDP source is missing".to_owned())
            })?;
            source.set_property("socket", socket);
            source.set_property("close-socket", true);
        }
        pipeline
            .set_state(gst::State::Ready)
            .map_err(|error| ReceiverError::MediaPreparation(error.to_string()))?;
        let media_port = media_socket.map_or_else(
            || {
                pipeline
                    .by_name("udp-source")
                    .map(|source| source.property::<i32>("port"))
                    .and_then(|port| u16::try_from(port).ok())
                    .filter(|port| *port != PORT_UNASSIGNED)
                    .ok_or_else(|| {
                        ReceiverError::MediaPreparation(
                            "GStreamer did not bind the configured UDP media port".to_owned(),
                        )
                    })
            },
            |(_socket, port)| Ok(port),
        )?;
        self.observer = Some(observer);
        self.pipeline = Some(pipeline);
        Ok(media_port)
    }

    fn start(&mut self) -> Result<(), ReceiverError> {
        let Some(pipeline) = self.pipeline.as_ref() else {
            return Err(ReceiverError::MediaStart("pipeline is not prepared".to_owned()));
        };
        pipeline
            .set_state(gst::State::Playing)
            .map_err(|error| ReceiverError::MediaStart(error.to_string()))?;
        if let Some(observer) = self.observer.as_ref() {
            observer.set_state(ReceiverState::WaitingForStream);
        }
        Ok(())
    }

    fn stop(&mut self) -> Result<(), ReceiverError> {
        if let Some(pipeline) = self.pipeline.take() {
            pipeline
                .set_state(gst::State::Null)
                .map_err(|error| ReceiverError::MediaStop(error.to_string()))?;
        }
        if let Some(observer) = self.observer.as_ref() {
            observer.set_state(ReceiverState::Idle);
        }
        self.observer = None;
        Ok(())
    }

    fn state(&self) -> ReceiverState {
        self.poll_bus();
        self.observer.as_ref().map_or(ReceiverState::Idle, |observer| observer.state())
    }

    fn decoder_name(&self) -> Option<String> {
        self.metrics.lock().ok().and_then(|metrics| metrics.decoder())
    }

    fn received_bitrate_bps(&self) -> u32 {
        self.metrics.lock().map_or(0, |metrics| metrics.received_bitrate_bps())
    }

    fn timeout_count(&self) -> u64 {
        self.metrics.lock().map_or(0, |metrics| metrics.timeout_count())
    }
}

fn bind_session_socket(session_id: uuid::Uuid) -> Result<(gio::Socket, u16), ReceiverError> {
    let port_count = MEDIA_PORT_RANGE_SIZE;
    let id = session_id.as_bytes();
    let first_offset = u16::from_be_bytes([id[0], id[1]]) % port_count;
    for offset in 0..port_count {
        let port = MEDIA_PORT_RANGE_START + (first_offset + offset) % port_count;
        let socket = match UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port)) {
            Ok(socket) => socket,
            Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => continue,
            Err(error) => {
                return Err(ReceiverError::MediaPreparation(format!(
                    "could not bind UDP media port {port}: {error}"
                )));
            }
        };
        let owned_fd: OwnedFd = socket.into();
        let socket = gio::Socket::from_fd(owned_fd).map_err(|error| {
            ReceiverError::MediaPreparation(format!(
                "could not hand UDP media port {port} to GStreamer: {error}"
            ))
        })?;
        return Ok((socket, port));
    }
    Err(ReceiverError::MediaPreparation(format!(
        "no UDP media port is available in {MEDIA_PORT_RANGE_START}-{MEDIA_PORT_RANGE_END}"
    )))
}

impl<F> Drop for GStreamerReceiver<F> {
    fn drop(&mut self) {
        if let Some(pipeline) = self.pipeline.take() {
            let _ = pipeline.set_state(gst::State::Null);
        }
        self.observer = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::CodecPipelineFactory;
    use receiver_core::LatencyConfig;
    use receiver_protocol::{VideoCodec, VideoProfile};
    use uuid::Uuid;

    fn config(codec: VideoCodec) -> MediaSessionConfig {
        MediaSessionConfig {
            session_id: Uuid::new_v4(),
            codec,
            profile: VideoProfile { width: 320, height: 240, fps: 30 },
            bitrate_bps: 500_000,
            media_port: 55_010,
            output_format: PixelFormat::Yuy2,
            latency: LatencyConfig::default(),
            udp_timeout_ms: 2_000,
        }
    }

    #[test]
    fn h264_pipeline_has_bounded_queue_and_can_start() {
        gst::init().unwrap();
        if gst::ElementFactory::find("udpsrc").is_none() {
            eprintln!("skipped: GStreamer UDP source plugin is unavailable");
            return;
        }
        let mut receiver = GStreamerReceiver::new(FakesinkFactory).unwrap();
        receiver.prepare(config(VideoCodec::H264)).unwrap();
        assert_eq!(receiver.state(), ReceiverState::Prepared);
        receiver.start().unwrap();
        assert_eq!(receiver.state(), ReceiverState::WaitingForStream);
        receiver.stop().unwrap();
        assert_eq!(receiver.state(), ReceiverState::Idle);
    }

    #[test]
    fn h265_pipeline_is_constructible_when_parser_is_installed() {
        gst::init().unwrap();
        let factory = DefaultCodecPipelineFactory;
        if !factory.supports(VideoCodec::H265).supported {
            eprintln!("skipped: required GStreamer H.265 or shared input plugin is unavailable");
            return;
        }
        let mut receiver = GStreamerReceiver::new(FakesinkFactory).unwrap();
        receiver.prepare(config(VideoCodec::H265)).unwrap();
        receiver.stop().unwrap();
    }

    #[test]
    fn zero_media_port_allocates_a_session_specific_udp_port() {
        gst::init().unwrap();
        let mut receiver = GStreamerReceiver::new(FakesinkFactory).unwrap();
        let mut config = config(VideoCodec::H264);
        config.media_port = 0;

        let allocated = receiver.prepare(config).unwrap();

        assert!((MEDIA_PORT_RANGE_START..=MEDIA_PORT_RANGE_END).contains(&allocated));
        assert!(UdpSocket::bind((Ipv4Addr::UNSPECIFIED, allocated)).is_err());
        receiver.stop().unwrap();
        assert!(UdpSocket::bind((Ipv4Addr::UNSPECIFIED, allocated)).is_ok());
    }
}
