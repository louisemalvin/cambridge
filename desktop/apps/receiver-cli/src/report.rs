use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

use anyhow::{Context, Result};
use receiver_core::{DiagnosticPhase, ReceiverDiagnosticEvent, ReceiverDiagnostics, ReceiverState};
use receiver_protocol::VideoProfile;
use serde::Serialize;
use serde_json::Value;

use crate::cli::Cli;

const REPORT_SCHEMA: &str = "mobile-webcam-performance-report-v1";
const FALLBACK_RUN_ID_PREFIX: &str = "run-";
const STARTUP_DELAY_WARNING_MS: u64 = 3_000;
const JITTER_WARNING_RATIO: f64 = 0.20;
const P95_INTERVAL_WARNING_RATIO: f64 = 1.50;
const MILLISECONDS_PER_SECOND: f64 = 1_000.0;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PerformanceReport {
    pub schema: &'static str,
    pub run_id: String,
    pub session_id: String,
    pub started_at_ms: u64,
    pub captured_at_ms: u64,
    pub duration_ms: u64,
    pub conditions: ReportConditions,
    pub sender_events: Vec<StructuredLogEvent>,
    pub receiver_events: Vec<ReceiverDiagnosticEvent>,
    pub receiver_log_events: Vec<StructuredLogEvent>,
    pub samples: Vec<ReceiverSample>,
    pub final_observation: ReceiverDiagnostics,
    pub categories: Vec<ReportCategory>,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReportConditions {
    pub phone: Option<String>,
    pub android_api: Option<u64>,
    pub network: String,
    pub receiver_host: String,
    pub consumer: String,
    pub codec: Option<String>,
    pub profile: Option<VideoProfile>,
    pub target_bitrate_bps: Option<u32>,
    pub lens: Option<String>,
    pub stabilization_enabled: Option<bool>,
    pub decoder: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverSample {
    pub captured_at_ms: u64,
    pub elapsed_ms: u64,
    pub state: ReceiverState,
    pub phase: DiagnosticPhase,
    pub observed_fps: Option<f64>,
    pub recent_received_bitrate_bps: u32,
    pub timeout_count: u64,
    pub continuity_warning_count: u64,
    pub pipeline_error_count: u64,
    pub queue_current_frames: u32,
    pub queue_high_watermark_samples: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReportCategory {
    StartupDelay,
    SteadyStateJitter,
    PacketInterruption,
    DecoderStall,
    OutputBackpressure,
    PipelineError,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StructuredLogEvent {
    pub timestamp_ms: Option<u64>,
    pub source: Option<String>,
    pub event: String,
    pub fields: BTreeMap<String, Value>,
}

pub fn read_structured_log(path: Option<&Path>) -> Result<Vec<StructuredLogEvent>> {
    let Some(path) = path else {
        return Ok(Vec::new());
    };
    let contents =
        fs::read_to_string(path).with_context(|| format!("read log {}", path.display()))?;
    Ok(contents.lines().filter_map(parse_structured_log_line).collect())
}

pub fn build_report(
    cli: &Cli,
    sender_events: Vec<StructuredLogEvent>,
    receiver_log_events: Vec<StructuredLogEvent>,
    snapshots: &[ReceiverDiagnostics],
) -> Result<PerformanceReport> {
    let final_observation =
        snapshots.last().cloned().context("receiver diagnostics did not return an observation")?;
    let session_id = cli.inspect_session.clone().context("inspection session ID is required")?;
    let run_id = cli
        .run_id
        .clone()
        .or_else(|| sender_events.iter().find_map(|event| field_string(event, "runId")))
        .unwrap_or_else(|| format!("{FALLBACK_RUN_ID_PREFIX}{session_id}"));
    let receiver_events = deduplicate_receiver_events(snapshots);
    let samples = snapshots.iter().map(receiver_sample).collect::<Vec<_>>();
    let conditions = report_conditions(cli, &sender_events, &final_observation);
    let categories = categories(&final_observation);
    let warnings = warnings(&final_observation, &categories);
    let started_at_ms = snapshots
        .first()
        .map_or(final_observation.started_at_ms, |snapshot| snapshot.started_at_ms);
    let captured_at_ms = final_observation.captured_at_ms;
    let duration_ms = snapshots
        .first()
        .map_or(0, |snapshot| captured_at_ms.saturating_sub(snapshot.captured_at_ms));
    Ok(PerformanceReport {
        schema: REPORT_SCHEMA,
        run_id,
        session_id,
        started_at_ms,
        captured_at_ms,
        duration_ms,
        conditions,
        sender_events,
        receiver_events,
        receiver_log_events,
        samples,
        final_observation,
        categories,
        warnings,
    })
}

fn report_conditions(
    cli: &Cli,
    sender_events: &[StructuredLogEvent],
    observation: &ReceiverDiagnostics,
) -> ReportConditions {
    let environment = sender_events.iter().rev().find(|event| event.event == "sender_environment");
    let phone = environment.and_then(|event| {
        let manufacturer = field_string(event, "phoneManufacturer");
        let model = field_string(event, "phoneModel");
        match (manufacturer, model) {
            (Some(manufacturer), Some(model)) => Some(format!("{manufacturer} {model}")),
            (None, model) => model,
            (manufacturer, None) => manufacturer,
        }
    });
    let lens = sender_events.iter().rev().find_map(|event| {
        field_string(event, "selectedLens").or_else(|| field_string(event, "lens"))
    });
    let stabilization_enabled = sender_events
        .iter()
        .rev()
        .find_map(|event| field_bool(event, "applied").or_else(|| field_bool(event, "enabled")));
    ReportConditions {
        phone,
        android_api: environment.and_then(|event| field_u64(event, "androidApi")),
        network: cli.network.clone(),
        receiver_host: receiver_host(&cli.receiver_url),
        consumer: cli.consumer.clone(),
        codec: Some(observation.selected_codec.to_string()),
        profile: Some(observation.target_profile.clone()),
        target_bitrate_bps: Some(observation.target_bitrate_bps),
        lens,
        stabilization_enabled,
        decoder: observation.decoder.clone(),
    }
}

fn deduplicate_receiver_events(snapshots: &[ReceiverDiagnostics]) -> Vec<ReceiverDiagnosticEvent> {
    let mut events = BTreeMap::new();
    for snapshot in snapshots {
        for event in &snapshot.events {
            events.insert(event.sequence, event.clone());
        }
    }
    events.into_values().collect()
}

fn receiver_sample(snapshot: &ReceiverDiagnostics) -> ReceiverSample {
    ReceiverSample {
        captured_at_ms: snapshot.captured_at_ms,
        elapsed_ms: snapshot.elapsed_ms,
        state: snapshot.state,
        phase: snapshot.phase,
        observed_fps: snapshot.observed_fps,
        recent_received_bitrate_bps: snapshot.recent_received_bitrate_bps,
        timeout_count: snapshot.timeout_count,
        continuity_warning_count: snapshot.continuity_warning_count,
        pipeline_error_count: snapshot.pipeline_error_count,
        queue_current_frames: snapshot.output_queue.current_frames,
        queue_high_watermark_samples: snapshot.output_queue.high_watermark_samples,
    }
}

fn categories(observation: &ReceiverDiagnostics) -> Vec<ReportCategory> {
    let mut categories = BTreeSet::new();
    if observation.first_frame_elapsed_ms.is_none()
        || observation
            .first_frame_elapsed_ms
            .is_some_and(|elapsed| elapsed >= STARTUP_DELAY_WARNING_MS)
    {
        categories.insert(ReportCategory::StartupDelay);
    }
    let target_interval_ms = target_interval_ms(observation.target_profile.fps);
    let jitter_warning = target_interval_ms.is_some_and(|target| {
        observation
            .frame_intervals
            .p95_ms
            .is_some_and(|p95| p95 >= target * P95_INTERVAL_WARNING_RATIO)
            || observation
                .frame_intervals
                .mean_absolute_jitter_ms
                .is_some_and(|jitter| jitter >= target * JITTER_WARNING_RATIO)
    });
    if jitter_warning {
        categories.insert(ReportCategory::SteadyStateJitter);
    }
    if observation.timeout_count > 0 || observation.phase == DiagnosticPhase::PacketInterruption {
        categories.insert(ReportCategory::PacketInterruption);
    }
    if observation.phase == DiagnosticPhase::DecoderStall {
        categories.insert(ReportCategory::DecoderStall);
    }
    if observation.output_queue.high_watermark_samples > 0
        || observation.phase == DiagnosticPhase::OutputBackpressure
    {
        categories.insert(ReportCategory::OutputBackpressure);
    }
    if observation.pipeline_error_count > 0 || observation.phase == DiagnosticPhase::PipelineError {
        categories.insert(ReportCategory::PipelineError);
    }
    categories.into_iter().collect()
}

fn warnings(observation: &ReceiverDiagnostics, categories: &[ReportCategory]) -> Vec<String> {
    let mut warnings = categories
        .iter()
        .map(|category| match category {
            ReportCategory::StartupDelay => {
                "first decoded frame was slow or not observed".to_owned()
            }
            ReportCategory::SteadyStateJitter => {
                "recent frame cadence exceeded the jitter warning threshold".to_owned()
            }
            ReportCategory::PacketInterruption => {
                format!("receiver observed {} SRT timeout(s)", observation.timeout_count)
            }
            ReportCategory::DecoderStall => {
                "network input continued while decoded frames stopped".to_owned()
            }
            ReportCategory::OutputBackpressure => {
                "output queue reached its high watermark".to_owned()
            }
            ReportCategory::PipelineError => {
                format!("receiver observed {} pipeline error(s)", observation.pipeline_error_count)
            }
        })
        .collect::<Vec<_>>();
    if observation.continuity_warning_count > 0 {
        warnings.push(format!(
            "receiver observed {} MPEG-TS continuity warning(s)",
            observation.continuity_warning_count
        ));
    }
    if observation.pipeline_warning_count > observation.continuity_warning_count {
        warnings.push(format!(
            "receiver observed {} pipeline warning(s)",
            observation.pipeline_warning_count
        ));
    }
    warnings
}

fn target_interval_ms(fps: u32) -> Option<f64> {
    (fps > 0).then(|| MILLISECONDS_PER_SECOND / f64::from(fps))
}

fn parse_structured_log_line(line: &str) -> Option<StructuredLogEvent> {
    let start = line.find('{')?;
    let end = line.rfind('}')?;
    let value: Value = serde_json::from_str(&line[start..=end]).ok()?;
    let object = value.as_object()?;
    let nested_fields = object.get("fields").and_then(Value::as_object);
    let event = object
        .get("event")
        .or_else(|| nested_fields.and_then(|fields| fields.get("event")))?
        .as_str()?
        .to_owned();
    let timestamp_ms = object
        .get("timestampMs")
        .and_then(Value::as_u64)
        .or_else(|| object.get("timestamp_ms").and_then(Value::as_u64));
    let source = object
        .get("source")
        .or_else(|| nested_fields.and_then(|fields| fields.get("source")))
        .and_then(Value::as_str)
        .map(str::to_owned);
    let mut fields = object
        .iter()
        .filter(|(key, _)| {
            !matches!(
                key.as_str(),
                "schema" | "source" | "event" | "timestampMs" | "timestamp_ms" | "fields"
            )
        })
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect::<BTreeMap<_, _>>();
    if let Some(nested_fields) = nested_fields {
        for (key, value) in nested_fields {
            if !matches!(key.as_str(), "event" | "source") {
                fields.insert(key.clone(), value.clone());
            }
        }
    }
    Some(StructuredLogEvent { timestamp_ms, source, event, fields })
}

fn field_string(event: &StructuredLogEvent, key: &str) -> Option<String> {
    event.fields.get(key).and_then(|value| value.as_str()).map(str::to_owned)
}

fn field_u64(event: &StructuredLogEvent, key: &str) -> Option<u64> {
    event.fields.get(key).and_then(Value::as_u64)
}

fn field_bool(event: &StructuredLogEvent, key: &str) -> Option<bool> {
    event.fields.get(key).and_then(Value::as_bool)
}

fn receiver_host(url: &str) -> String {
    let authority = url.strip_prefix("http://").unwrap_or(url).trim_end_matches('/');
    if let Some(address) = authority.strip_prefix('[') {
        return address
            .split_once(']')
            .map_or_else(|| address.to_owned(), |(host, _)| host.to_owned());
    }
    authority
        .rsplit_once(':')
        .filter(|(_, port)| port.parse::<u16>().is_ok())
        .map_or_else(|| authority.to_owned(), |(host, _)| host.to_owned())
}

#[cfg(test)]
mod tests {
    use clap::Parser;

    use super::*;

    const ANDROID_LOG: &str = include_str!("../tests/fixtures/android-diagnostic.log");
    const RECEIVER_LOG: &str = include_str!("../tests/fixtures/receiver-json.log");
    const RECEIVER_DIAGNOSTICS: &str = include_str!("../tests/fixtures/receiver-diagnostics.json");

    #[test]
    fn parses_logcat_prefix_and_preserves_typed_fields() {
        let events = ANDROID_LOG.lines().filter_map(parse_structured_log_line).collect::<Vec<_>>();

        assert_eq!(events.len(), 4);
        assert_eq!(events[0].event, "stream_start_requested");
        assert_eq!(field_string(&events[1], "phoneModel").as_deref(), Some("Phone One"));
        assert_eq!(field_u64(&events[1], "androidApi"), Some(35));
        assert_eq!(field_bool(&events[2], "stabilizationSupported"), Some(true));
    }

    #[test]
    fn report_classifies_fixture_observations_and_correlates_run_context() {
        let sender_events = ANDROID_LOG.lines().filter_map(parse_structured_log_line).collect();
        let observation: ReceiverDiagnostics = serde_json::from_str(RECEIVER_DIAGNOSTICS).unwrap();
        let cli = Cli::parse_from([
            "mobile-webcam-receiver",
            "--inspect-session",
            "fixture-session",
            "--network",
            "wifi",
            "--consumer",
            "obs",
        ]);

        let snapshots = vec![observation];
        let report = build_report(&cli, sender_events, Vec::new(), &snapshots).unwrap();

        assert_eq!(report.run_id, "run-fixture");
        assert_eq!(report.conditions.phone.as_deref(), Some("Example Phone One"));
        assert_eq!(report.conditions.network, "wifi");
        assert_eq!(report.conditions.consumer, "obs");
        assert!(report.categories.contains(&ReportCategory::StartupDelay));
        assert!(report.categories.contains(&ReportCategory::SteadyStateJitter));
        assert!(report.categories.contains(&ReportCategory::PacketInterruption));
        assert!(report.categories.contains(&ReportCategory::OutputBackpressure));
        assert_eq!(report.receiver_events.len(), 3);
    }

    #[test]
    fn parses_tracing_json_nested_fields() {
        let event = RECEIVER_LOG.lines().find_map(parse_structured_log_line).unwrap();

        assert_eq!(event.event, "receiver_decoder_selected");
        assert_eq!(field_string(&event, "decoder").as_deref(), Some("avdec_h264"));
    }
}
