use std::path::Path;

use crate::{device::VideoDevice, LinuxPlatformError};

pub fn inspect_video_devices() -> Result<Vec<VideoDevice>, LinuxPlatformError> {
    let sysfs = Path::new("/sys/class/video4linux");
    let entries = std::fs::read_dir(sysfs)
        .map_err(|error| LinuxPlatformError::SysfsUnavailable(error.to_string()))?;
    let mut devices = entries
        .filter_map(Result::ok)
        .map(|entry| VideoDevice::from_sysfs_entry(&entry.path()))
        .collect::<Vec<_>>();
    devices.sort_by(|left, right| left.path.cmp(&right.path));
    Ok(devices)
}
