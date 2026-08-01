use std::sync::{Arc, Mutex};

use gst::prelude::*;
use gstreamer as gst;
use receiver_core::{MediaReceiver, MediaSessionConfig, ReceiverError, ReceiverState};
use receiver_protocol::PixelFormat;

use crate::{
    codec_branch::DefaultCodecPipelineFactory, metrics::Metrics, pipeline_builder::build_pipeline,
    pipeline_bus::poll, pipeline_event::PipelineObserver, PipelineError,
};

pub trait VideoSinkFactory: Send + Sync {
    fn create_sink(&self, format: PixelFormat) -> Result<gst::Element, PipelineError>;

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
            poll(&bus, observer, &mut metrics);
        }
    }
}

impl<F> MediaReceiver for GStreamerReceiver<F>
where
    F: VideoSinkFactory + 'static,
{
    fn prepare(&mut self, config: MediaSessionConfig) -> Result<(), ReceiverError> {
        if self.pipeline.is_some() {
            return Err(ReceiverError::MediaPreparation(
                "a GStreamer pipeline is already prepared".to_owned(),
            ));
        }
        let observer = PipelineObserver::new(config.session_id, config.codec);
        if let Ok(mut metrics) = self.metrics.lock() {
            metrics.reset();
        }
        let pipeline = build_pipeline(
            &config,
            &self.sink_factory,
            &self.codec_factory,
            observer.clone(),
            self.metrics.clone(),
        )
        .map_err(|error| ReceiverError::MediaPreparation(error.to_string()))?;
        pipeline
            .set_state(gst::State::Ready)
            .map_err(|error| ReceiverError::MediaPreparation(error.to_string()))?;
        self.observer = Some(observer);
        self.pipeline = Some(pipeline);
        Ok(())
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
}
