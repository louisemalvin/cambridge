use std::{env, path::PathBuf, thread, time::Duration};

use receiver_platform_linux::PersistentVirtualCameraOutput;

const DEFAULT_RUN_DURATION: Duration = Duration::from_secs(10);

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let device = env::args().nth(1).map_or_else(|| PathBuf::from("/dev/video10"), PathBuf::from);
    let output = PersistentVirtualCameraOutput::start(&device)?;
    println!("persistent standby output active on {}", device.display());
    thread::sleep(DEFAULT_RUN_DURATION);
    output.stop()?;
    Ok(())
}
