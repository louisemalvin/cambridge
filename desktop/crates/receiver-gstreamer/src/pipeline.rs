use std::sync::{Arc, Mutex};

use gst::prelude::*;
use gstreamer as gst;
use receiver_core::{
    MediaReceiver, MediaSessionConfig, ReceiverDiagnostics, ReceiverError, ReceiverState,
    ReceiverTransportMetrics,
};
use receiver_protocol::PixelFormat;

use crate::{
    codec_branch::DefaultCodecPipelineFactory,
    metrics::{Metrics, MetricsConfig},
    pipeline_builder::build_pipeline,
    pipeline_bus::poll,
    pipeline_event::PipelineObserver,
    PipelineError,
};

const PIPELINE_STOP_TIMEOUT_SECONDS: u64 = 5;

pub trait VideoSinkFactory: Send + Sync {
    fn create_sink(&self, format: PixelFormat) -> Result<gst::Element, PipelineError>;

    fn set_standby(&self) -> Result<(), PipelineError> {
        Ok(())
    }

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
            poll(&bus, observer, &mut metrics);
            if let Some(source) = pipeline.by_name("srt-source") {
                let stats = source.property::<gst::Structure>("stats");
                metrics.record_srt_stats(&stats);
            }
            if observer.state() == ReceiverState::Receiving && metrics.transport_timed_out() {
                metrics.record_timeout();
                observer.on_timeout(metrics.timeout_count());
            }
        }
        if matches!(observer.state(), ReceiverState::TimedOut | ReceiverState::Failed) {
            let _ = self.sink_factory.set_standby();
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
            metrics.reset(MetricsConfig {
                session_id: config.session_id,
                codec: config.codec,
                profile: config.profile.clone(),
                target_bitrate_bps: config.bitrate_bps,
                output_pixel_format: config.output_format,
                output_queue_max_frames: config.latency.output_queue_frames,
                transport_timeout_ms: config.transport_timeout_ms,
            });
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
        self.sink_factory
            .set_standby()
            .map_err(|error| ReceiverError::MediaStop(error.to_string()))?;
        if let Some(pipeline) = self.pipeline.take() {
            pipeline
                .set_state(gst::State::Null)
                .map_err(|error| ReceiverError::MediaStop(error.to_string()))?;
            let (success, state, _pending) =
                pipeline.state(gst::ClockTime::from_seconds(PIPELINE_STOP_TIMEOUT_SECONDS));
            if success.is_err() || state != gst::State::Null {
                return Err(ReceiverError::MediaStop(format!(
                    "pipeline did not reach Null: success={success:?} state={state:?}"
                )));
            }
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

    fn transport_metrics(&self) -> Option<ReceiverTransportMetrics> {
        self.poll_bus();
        self.metrics.lock().ok().and_then(|metrics| metrics.transport_metrics())
    }

    fn transport_connected(&self) -> bool {
        self.poll_bus();
        self.observer.as_ref().is_some_and(|observer| observer.transport_connected())
    }

    fn diagnostics(&self) -> Option<ReceiverDiagnostics> {
        self.poll_bus();
        let observer = self.observer.as_ref()?;
        let metrics = self.metrics.lock().ok()?;
        Some(metrics.snapshot(observer.state(), observer.diagnostics()))
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
    use receiver_protocol::{SrtEndpoint, SrtMode, SrtTransportKind, VideoCodec, VideoProfile};
    use uuid::Uuid;

    const TEST_SRT_PORT: u16 = 55_010;
    const TEST_TRANSPORT_TIMEOUT_MS: u64 = 2_000;

    fn config(codec: VideoCodec) -> MediaSessionConfig {
        MediaSessionConfig {
            session_id: Uuid::new_v4(),
            codec,
            profile: VideoProfile { width: 320, height: 240, fps: 30 },
            bitrate_bps: 500_000,
            output_format: PixelFormat::Yuy2,
            latency: LatencyConfig::default(),
            transport_timeout_ms: TEST_TRANSPORT_TIMEOUT_MS,
            srt_endpoint: SrtEndpoint {
                kind: SrtTransportKind::Srt,
                mode: SrtMode::Caller,
                host: "127.0.0.1".to_owned(),
                port: TEST_SRT_PORT,
                stream_id: "test-stream".to_owned(),
                latency_ms: 120,
                key_length_bytes: 32,
                passphrase: "test-passphrase-123".to_owned(),
            },
        }
    }

    #[test]
    fn h264_pipeline_has_bounded_queue_and_can_start() {
        gst::init().unwrap();
        if gst::ElementFactory::find("srtsrc").is_none() {
            eprintln!("skipped: GStreamer SRT source plugin is unavailable");
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
        if gst::ElementFactory::find("srtsrc").is_none() {
            eprintln!("skipped: GStreamer SRT source plugin is unavailable");
            return;
        }
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
