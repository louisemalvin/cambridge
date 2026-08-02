use receiver_core::{ReceiverConfig, DEFAULT_LISTEN_ADDRESS};
use receiver_protocol::ReceiverCapabilities;
use std::path::Path;

use crate::report::PerformanceReport;

const NANOSECONDS_PER_MILLISECOND: u64 = 1_000_000;

pub fn print_banner(config: &ReceiverConfig, capabilities: &ReceiverCapabilities) {
    println!("Mobile Webcam Receiver");
    println!();
    println!(
        "Control API:      http://{}:{}",
        format_host(config.listen_addr),
        config.control_port
    );
    println!("Media input:      per-session UDP port");
    println!("Virtual camera:   {}", config.device.display());
    println!("Codecs:           {}", supported_codecs(capabilities));
    println!("State:            Idle");
}

pub fn print_capabilities(capabilities: &ReceiverCapabilities) -> anyhow::Result<()> {
    println!("{}", serde_json::to_string_pretty(capabilities)?);
    Ok(())
}

pub fn print_pipeline(config: &ReceiverConfig) {
    let timeout_nanos = config.udp_timeout_ms.saturating_mul(NANOSECONDS_PER_MILLISECOND);
    println!(
        "udpsrc address={DEFAULT_LISTEN_ADDRESS} port=<assigned-per-session> timeout={timeout_nanos}"
    );
    println!("  -> tsparse -> tsdemux latency={}", config.latency.demux_latency_ms);
    println!("  -> h264parse or h265parse -> decodebin");
    println!(
        "  -> videoconvert -> videoscale -> raw caps -> leaky queue max-size-buffers={}",
        config.latency.output_queue_frames
    );
    println!("  -> v4l2sink device={} sync=false", config.device.display());
}

pub fn print_inspection_report(report: &PerformanceReport, output_path: Option<&Path>, json: &str) {
    let observation = &report.final_observation;
    println!("Mobile Webcam Performance Report");
    println!("Run:               {}", report.run_id);
    println!("Session:           {}", report.session_id);
    println!("Capture duration:  {} ms", report.duration_ms);
    println!("State:             {:?} ({:?})", observation.state, observation.phase);
    println!(
        "Frames:            target {} FPS, observed {}",
        observation.target_profile.fps,
        observation.observed_fps.map_or_else(|| "n/a".to_owned(), |fps| format!("{fps:.2} FPS"))
    );
    println!(
        "Intervals:         p50 {}, p95 {}, max {} ms",
        format_metric(observation.frame_intervals.p50_ms),
        format_metric(observation.frame_intervals.p95_ms),
        format_metric(observation.frame_intervals.max_ms),
    );
    println!("Recent bitrate:    {} bps", observation.recent_received_bitrate_bps);
    println!("First frame:       {}", format_metric_u64(observation.first_frame_elapsed_ms));
    println!("Timeouts:          {}", observation.timeout_count);
    println!("Decoder:           {}", observation.decoder.as_deref().unwrap_or("n/a"));
    println!("Categories:         {}", format_categories(&report.categories));
    if !report.warnings.is_empty() {
        println!("Warnings:");
        for warning in &report.warnings {
            println!("  - {warning}");
        }
    }
    if let Some(path) = output_path {
        println!("JSON report:       {}", path.display());
    } else {
        println!();
        println!("{json}");
    }
}

fn format_metric(value: Option<f64>) -> String {
    value.map_or_else(|| "n/a".to_owned(), |value| format!("{value:.2}"))
}

fn format_metric_u64(value: Option<u64>) -> String {
    value.map_or_else(|| "n/a".to_owned(), |value| format!("{value} ms"))
}

fn format_categories(categories: &[crate::report::ReportCategory]) -> String {
    if categories.is_empty() {
        return "none".to_owned();
    }
    categories.iter().map(|category| format!("{category:?}")).collect::<Vec<_>>().join(", ")
}

fn supported_codecs(capabilities: &ReceiverCapabilities) -> String {
    capabilities
        .video_codecs
        .iter()
        .filter(|codec| codec.supported)
        .map(|codec| codec.codec.to_string())
        .collect::<Vec<_>>()
        .join(", ")
}

fn format_host(address: std::net::IpAddr) -> String {
    match address {
        std::net::IpAddr::V4(address) => address.to_string(),
        std::net::IpAddr::V6(address) => format!("[{address}]"),
    }
}
