use std::{env, path::PathBuf, sync::mpsc::RecvTimeoutError, time::Duration};

use receiver_platform_linux::V4l2LoopbackDemandMonitor;

const DEFAULT_PRODUCER_BASELINE: u32 = 0;
const EVENT_WAIT_TIMEOUT: Duration = Duration::from_secs(1);

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let device = env::args().nth(1).map_or_else(|| PathBuf::from("/dev/video10"), PathBuf::from);
    let (mut monitor, events) =
        V4l2LoopbackDemandMonitor::start(&device, DEFAULT_PRODUCER_BASELINE)?;
    println!("monitoring {} - press Ctrl-C to stop", device.display());
    loop {
        match events.recv_timeout(EVENT_WAIT_TIMEOUT) {
            Ok(event) => println!("demand: {:?}", event.demand),
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => break,
        }
    }
    monitor.stop();
    Ok(())
}
