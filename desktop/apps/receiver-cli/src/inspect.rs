use std::{
    fs, thread,
    time::{Duration, Instant},
};

use anyhow::{bail, Context, Result};

use crate::{
    cli::Cli,
    http::get_diagnostics,
    output::print_inspection_report,
    report::{build_report, read_structured_log},
};

pub fn run(cli: &Cli) -> Result<()> {
    let session_id =
        cli.inspect_session.as_deref().context("--inspect-session must contain a session ID")?;
    if cli.inspect_duration_seconds == 0 {
        bail!("--inspect-duration-seconds must be positive");
    }
    if cli.inspect_poll_ms == 0 {
        bail!("--inspect-poll-ms must be positive");
    }
    let capture_duration = Duration::from_secs(cli.inspect_duration_seconds);
    let poll_interval = Duration::from_millis(cli.inspect_poll_ms);
    let started = Instant::now();
    let mut snapshots = Vec::new();
    loop {
        snapshots.push(get_diagnostics(
            &cli.receiver_url,
            session_id,
            cli.control_token.as_deref(),
        )?);
        let elapsed = started.elapsed();
        if elapsed >= capture_duration {
            break;
        }
        thread::sleep(poll_interval.min(capture_duration.saturating_sub(elapsed)));
    }
    let sender_events = read_structured_log(cli.sender_log.as_deref())?;
    let receiver_log_events = read_structured_log(cli.receiver_log.as_deref())?;
    let report = build_report(cli, sender_events, receiver_log_events, &snapshots)?;
    let json = serde_json::to_string_pretty(&report).context("encode performance report")?;
    if let Some(path) = cli.output.as_deref() {
        fs::write(path, &json).with_context(|| format!("write report {}", path.display()))?;
    }
    print_inspection_report(&report, cli.output.as_deref(), &json);
    Ok(())
}
