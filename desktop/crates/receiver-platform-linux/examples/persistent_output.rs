use std::{env, path::PathBuf, thread, time::Duration};

use receiver_platform_linux::PersistentVirtualCameraOutput;

const DEFAULT_RUN_DURATION_SECONDS: u64 = 30;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let device = env::args().nth(1).map_or_else(|| PathBuf::from("/dev/video10"), PathBuf::from);
    let run_duration_seconds = env::args()
        .nth(2)
        .map_or(Ok(DEFAULT_RUN_DURATION_SECONDS), |value| value.parse::<u64>())?;
    let output = PersistentVirtualCameraOutput::start(&device)?;
    println!("persistent standby output active on {}", device.display());
    thread::sleep(Duration::from_secs(run_duration_seconds));
    output.stop()?;
    Ok(())
}
