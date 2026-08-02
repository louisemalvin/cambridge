use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};

use receiver_core::{
    diagnostic_timestamp_ms, DiagnosticPhase, FrameIntervalStatistics, QueueDiagnostics,
    ReceiverDiagnosticEvent, ReceiverDiagnostics, ReceiverState,
};
use receiver_protocol::{PixelFormat, VideoCodec, VideoProfile};
use uuid::Uuid;

const MIN_ELAPSED_NANOSECONDS: u128 = 1;
const BITS_PER_BYTE: u128 = 8;
const NANOSECONDS_PER_SECOND: u128 = 1_000_000_000;
const MILLISECONDS_PER_SECOND: f64 = 1_000.0;
const RECENT_METRICS_WINDOW: Duration = Duration::from_secs(5);
const NETWORK_SAMPLE_INTERVAL: Duration = Duration::from_millis(250);
const DECODER_STALL_GRACE: Duration = Duration::from_secs(1);
const MAX_FRAME_INTERVAL_SAMPLES: usize = 1_024;
const MAX_NETWORK_SAMPLES: usize = 512;
const PERCENTAGE_SCALE: u32 = 100;
const QUEUE_HIGH_WATERMARK_PERCENT: u32 = 80;
const P50_NUMERATOR: usize = 10;
const P95_NUMERATOR: usize = 19;
const PERCENTILE_DENOMINATOR: usize = 20;

pub(crate) struct MetricsConfig {
    pub(crate) session_id: Uuid,
    pub(crate) codec: VideoCodec,
    pub(crate) profile: VideoProfile,
    pub(crate) target_bitrate_bps: u32,
    pub(crate) output_pixel_format: PixelFormat,
    pub(crate) output_queue_max_frames: u32,
    pub(crate) udp_timeout_ms: u64,
}

#[derive(Debug, Default)]
pub(crate) struct Metrics {
    session_id: Option<Uuid>,
    codec: Option<VideoCodec>,
    profile: Option<VideoProfile>,
    target_bitrate_bps: u32,
    output_pixel_format: Option<PixelFormat>,
    udp_timeout: Duration,
    output_queue_max_frames: u32,
    started_at: Option<Instant>,
    started_at_ms: u64,
    first_frame_at: Option<Instant>,
    last_network_at: Option<Instant>,
    last_decoded_frame_at: Option<Instant>,
    received_bytes: u64,
    network_samples: VecDeque<NetworkSample>,
    last_network_sample_at: Option<Instant>,
    frame_intervals: VecDeque<FrameIntervalSample>,
    decoded_frames: u64,
    timeout_count: u64,
    continuity_warning_count: u64,
    pipeline_warning_count: u64,
    pipeline_error_count: u64,
    decoder: Option<String>,
    current_queue_frames: u32,
    maximum_observed_queue_frames: u32,
    high_watermark_samples: u64,
    queue_pressure_active: bool,
}

#[derive(Debug, Clone, Copy)]
struct NetworkSample {
    at: Instant,
    total_bytes: u64,
}

#[derive(Debug, Clone, Copy)]
struct FrameIntervalSample {
    at: Instant,
    interval_ms: f64,
}

impl Metrics {
    pub fn reset(&mut self, config: MetricsConfig) {
        *self = Self {
            session_id: Some(config.session_id),
            codec: Some(config.codec),
            profile: Some(config.profile),
            target_bitrate_bps: config.target_bitrate_bps,
            output_pixel_format: Some(config.output_pixel_format),
            udp_timeout: Duration::from_millis(config.udp_timeout_ms),
            output_queue_max_frames: config.output_queue_max_frames,
            started_at: Some(Instant::now()),
            started_at_ms: diagnostic_timestamp_ms(),
            ..Self::default()
        };
    }

    pub fn record_network_bytes(&mut self, bytes: usize) {
        let now = Instant::now();
        self.received_bytes = self.received_bytes.saturating_add(bytes as u64);
        self.last_network_at = Some(now);
        if self
            .last_network_sample_at
            .map_or(true, |last| now.duration_since(last) >= NETWORK_SAMPLE_INTERVAL)
        {
            self.network_samples
                .push_back(NetworkSample { at: now, total_bytes: self.received_bytes });
            self.last_network_sample_at = Some(now);
            while self.network_samples.len() > MAX_NETWORK_SAMPLES {
                self.network_samples.pop_front();
            }
        }
    }

    pub fn record_decoded_frame(&mut self) -> bool {
        let now = Instant::now();
        let first_frame = self.first_frame_at.is_none();
        if first_frame {
            self.first_frame_at = Some(now);
        }
        if let Some(previous) = self.last_decoded_frame_at {
            let interval_ms = now.duration_since(previous).as_secs_f64() * MILLISECONDS_PER_SECOND;
            self.frame_intervals.push_back(FrameIntervalSample { at: now, interval_ms });
            while self.frame_intervals.len() > MAX_FRAME_INTERVAL_SAMPLES {
                self.frame_intervals.pop_front();
            }
        }
        self.last_decoded_frame_at = Some(now);
        self.decoded_frames = self.decoded_frames.saturating_add(1);
        first_frame
    }

    pub fn set_decoder(&mut self, decoder: String) -> bool {
        if self.decoder.as_deref() == Some(decoder.as_str()) {
            return false;
        }
        self.decoder = Some(decoder);
        true
    }

    pub fn record_timeout(&mut self) {
        self.timeout_count = self.timeout_count.saturating_add(1);
    }

    pub fn record_pipeline_warning(&mut self, continuity: bool) {
        self.pipeline_warning_count = self.pipeline_warning_count.saturating_add(1);
        if continuity {
            self.continuity_warning_count = self.continuity_warning_count.saturating_add(1);
        }
    }

    pub fn record_pipeline_error(&mut self) {
        self.pipeline_error_count = self.pipeline_error_count.saturating_add(1);
    }

    pub fn record_queue_depth(&mut self, current_frames: u32, maximum_frames: u32) -> bool {
        self.current_queue_frames = current_frames;
        self.maximum_observed_queue_frames = self.maximum_observed_queue_frames.max(current_frames);
        let is_high = maximum_frames > 0
            && current_frames.saturating_mul(PERCENTAGE_SCALE)
                >= maximum_frames.saturating_mul(QUEUE_HIGH_WATERMARK_PERCENT);
        if is_high {
            self.high_watermark_samples = self.high_watermark_samples.saturating_add(1);
        }
        let pressure_started = is_high && !self.queue_pressure_active;
        self.queue_pressure_active = is_high;
        pressure_started
    }

    pub fn decoder(&self) -> Option<String> {
        self.decoder.clone()
    }

    pub const fn timeout_count(&self) -> u64 {
        self.timeout_count
    }

    pub fn received_bitrate_bps(&self) -> u32 {
        self.lifetime_received_bitrate_bps(Instant::now())
    }

    pub fn snapshot(
        &self,
        state: ReceiverState,
        events: Vec<ReceiverDiagnosticEvent>,
    ) -> ReceiverDiagnostics {
        let now = Instant::now();
        let captured_at_ms = diagnostic_timestamp_ms();
        let started_at = self.started_at.expect("metrics must be reset before snapshotting");
        let elapsed_ms = duration_millis(now.duration_since(started_at));
        let session_id =
            self.session_id.expect("metrics session must be configured before snapshotting");
        let codec = self.codec.expect("metrics codec must be configured before snapshotting");
        let profile =
            self.profile.clone().expect("metrics profile must be configured before snapshotting");
        let output_pixel_format = self
            .output_pixel_format
            .expect("metrics output format must be configured before snapshotting");
        let frame_intervals = self.frame_interval_statistics(now, f64::from(profile.fps));
        ReceiverDiagnostics {
            schema: receiver_core::DIAGNOSTICS_SCHEMA.to_owned(),
            session_id: session_id.to_string(),
            started_at_ms: self.started_at_ms,
            captured_at_ms,
            elapsed_ms,
            state,
            selected_codec: codec,
            target_profile: profile.clone(),
            target_bitrate_bps: self.target_bitrate_bps,
            output_pixel_format,
            decoder: self.decoder.clone(),
            first_frame_elapsed_ms: self
                .first_frame_at
                .map(|first| duration_millis(first.duration_since(started_at))),
            last_network_age_ms: self
                .last_network_at
                .map(|last| duration_millis(now.duration_since(last))),
            last_decoded_frame_age_ms: self
                .last_decoded_frame_at
                .map(|last| duration_millis(now.duration_since(last))),
            observed_fps: observed_fps(&frame_intervals),
            frame_intervals,
            received_bitrate_bps: self.lifetime_received_bitrate_bps(now),
            recent_received_bitrate_bps: self.recent_received_bitrate_bps(now),
            received_bytes: self.received_bytes,
            decoded_frames: self.decoded_frames,
            timeout_count: self.timeout_count,
            continuity_warning_count: self.continuity_warning_count,
            pipeline_warning_count: self.pipeline_warning_count,
            pipeline_error_count: self.pipeline_error_count,
            output_queue: QueueDiagnostics {
                configured_max_frames: self.output_queue_max_frames,
                current_frames: self.current_queue_frames,
                maximum_observed_frames: self.maximum_observed_queue_frames,
                high_watermark_samples: self.high_watermark_samples,
            },
            phase: self.phase(state, now),
            events,
        }
    }

    fn lifetime_received_bitrate_bps(&self, now: Instant) -> u32 {
        let Some(started_at) = self.started_at else {
            return 0;
        };
        bitrate(self.received_bytes, duration_nanos(now.duration_since(started_at)))
    }

    fn recent_received_bitrate_bps(&self, now: Instant) -> u32 {
        let Some(latest) = self.network_samples.back().copied() else {
            return 0;
        };
        let cutoff = now.checked_sub(RECENT_METRICS_WINDOW).unwrap_or(now);
        let earliest = self
            .network_samples
            .iter()
            .copied()
            .find(|sample| sample.at >= cutoff)
            .unwrap_or(latest);
        bitrate(
            latest.total_bytes.saturating_sub(earliest.total_bytes),
            duration_nanos(latest.at.duration_since(earliest.at)),
        )
    }

    fn frame_interval_statistics(&self, now: Instant, target_fps: f64) -> FrameIntervalStatistics {
        let cutoff = now.checked_sub(RECENT_METRICS_WINDOW).unwrap_or(now);
        let mut values: Vec<f64> = self
            .frame_intervals
            .iter()
            .filter(|sample| sample.at >= cutoff)
            .map(|sample| sample.interval_ms)
            .collect();
        if values.is_empty() {
            return FrameIntervalStatistics::default();
        }
        values.sort_by(f64::total_cmp);
        let sample_count = u64::try_from(values.len()).unwrap_or(u64::MAX);
        let sample_count_f64 = f64::from(u32::try_from(values.len()).unwrap_or(u32::MAX));
        let mean_ms = values.iter().sum::<f64>() / sample_count_f64;
        let target_interval_ms =
            target_fps.is_normal().then_some(MILLISECONDS_PER_SECOND / target_fps);
        let mean_absolute_jitter_ms = target_interval_ms.map(|target| {
            values.iter().map(|value| (value - target).abs()).sum::<f64>() / sample_count_f64
        });
        FrameIntervalStatistics {
            sample_count,
            min_ms: values.first().copied(),
            mean_ms: Some(mean_ms),
            p50_ms: percentile(&values, P50_NUMERATOR, PERCENTILE_DENOMINATOR),
            p95_ms: percentile(&values, P95_NUMERATOR, PERCENTILE_DENOMINATOR),
            max_ms: values.last().copied(),
            mean_absolute_jitter_ms,
        }
    }

    fn phase(&self, state: ReceiverState, now: Instant) -> DiagnosticPhase {
        if state == ReceiverState::Failed {
            return DiagnosticPhase::Failed;
        }
        if self.pipeline_error_count > 0 {
            return DiagnosticPhase::PipelineError;
        }
        if self.queue_pressure_active {
            return DiagnosticPhase::OutputBackpressure;
        }
        if state == ReceiverState::TimedOut
            || self.last_network_at.is_some_and(|last| now.duration_since(last) >= self.udp_timeout)
        {
            return DiagnosticPhase::PacketInterruption;
        }
        if self.first_frame_at.is_none() {
            return match state {
                ReceiverState::WaitingForStream => DiagnosticPhase::WaitingForPackets,
                _ => DiagnosticPhase::Starting,
            };
        }
        if self.last_network_at.is_some_and(|last| now.duration_since(last) < self.udp_timeout)
            && self
                .last_decoded_frame_at
                .is_some_and(|last| now.duration_since(last) >= DECODER_STALL_GRACE)
        {
            return DiagnosticPhase::DecoderStall;
        }
        DiagnosticPhase::SteadyState
    }
}

fn duration_nanos(duration: Duration) -> u128 {
    duration.as_nanos().max(MIN_ELAPSED_NANOSECONDS)
}

fn duration_millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

fn bitrate(bytes: u64, elapsed_nanos: u128) -> u32 {
    let bits_per_second = u128::from(bytes)
        .saturating_mul(BITS_PER_BYTE)
        .saturating_mul(NANOSECONDS_PER_SECOND)
        .checked_div(elapsed_nanos.max(MIN_ELAPSED_NANOSECONDS))
        .unwrap_or(0)
        .min(u128::from(u32::MAX));
    u32::try_from(bits_per_second).unwrap_or(u32::MAX)
}

fn observed_fps(statistics: &FrameIntervalStatistics) -> Option<f64> {
    statistics.mean_ms.filter(|mean| mean.is_normal()).map(|mean| MILLISECONDS_PER_SECOND / mean)
}

fn percentile(values: &[f64], numerator: usize, denominator: usize) -> Option<f64> {
    let last_index = values.len().checked_sub(1)?;
    let index = last_index.saturating_mul(numerator) / denominator;
    values.get(index.min(last_index)).copied()
}

#[cfg(test)]
mod tests {
    use super::*;
    use receiver_core::ReceiverState;

    fn config() -> MetricsConfig {
        MetricsConfig {
            session_id: Uuid::nil(),
            codec: VideoCodec::H264,
            profile: VideoProfile { width: 1_920, height: 1_080, fps: 30 },
            target_bitrate_bps: 8_000_000,
            output_pixel_format: PixelFormat::Yuy2,
            output_queue_max_frames: 2,
            udp_timeout_ms: 2_000,
        }
    }

    #[test]
    fn frame_statistics_are_bounded_to_the_recent_window() {
        let mut metrics = Metrics::default();
        metrics.reset(config());
        let now = Instant::now();
        metrics.frame_intervals = VecDeque::from([
            FrameIntervalSample { at: now, interval_ms: 30.0 },
            FrameIntervalSample { at: now, interval_ms: 34.0 },
            FrameIntervalSample { at: now, interval_ms: 42.0 },
        ]);

        let snapshot = metrics.snapshot(ReceiverState::Receiving, Vec::new());

        assert_eq!(snapshot.frame_intervals.sample_count, 3);
        assert_eq!(snapshot.frame_intervals.min_ms, Some(30.0));
        assert_eq!(snapshot.frame_intervals.p50_ms, Some(34.0));
        assert_eq!(snapshot.frame_intervals.max_ms, Some(42.0));
        assert!((snapshot.observed_fps.unwrap_or_default() - 28.3019).abs() < 0.001);
    }

    #[test]
    fn queue_pressure_and_pipeline_counts_are_reported_without_pipeline_changes() {
        let mut metrics = Metrics::default();
        metrics.reset(config());
        assert!(metrics.record_queue_depth(2, 2));
        assert!(!metrics.record_queue_depth(2, 2));
        metrics.record_pipeline_warning(true);
        metrics.record_pipeline_error();

        let snapshot = metrics.snapshot(ReceiverState::Receiving, Vec::new());

        assert_eq!(snapshot.output_queue.high_watermark_samples, 2);
        assert_eq!(snapshot.continuity_warning_count, 1);
        assert_eq!(snapshot.pipeline_error_count, 1);
        assert_eq!(snapshot.phase, DiagnosticPhase::PipelineError);
    }
}
