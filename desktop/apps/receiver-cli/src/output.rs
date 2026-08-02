use receiver_core::ReceiverConfig;
use receiver_protocol::ReceiverCapabilities;

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
    println!("udpsrc address=0.0.0.0 port=<assigned-per-session> timeout={timeout_nanos}");
    println!("  -> tsparse -> tsdemux latency={}", config.latency.demux_latency_ms);
    println!("  -> h264parse or h265parse -> decodebin");
    println!(
        "  -> videoconvert -> videoscale -> raw caps -> leaky queue max-size-buffers={}",
        config.latency.output_queue_frames
    );
    println!("  -> v4l2sink device={} sync=false", config.device.display());
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
