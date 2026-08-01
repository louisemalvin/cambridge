use std::os::unix::fs::FileTypeExt;
use std::path::Path;

use crate::{inspection::inspect_video_devices, LinuxPlatformError, VideoDevice};

pub fn validate_v4l2loopback(path: impl AsRef<Path>) -> Result<VideoDevice, LinuxPlatformError> {
    let path = path.as_ref();
    let metadata = std::fs::metadata(path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::PermissionDenied {
            LinuxPlatformError::PermissionDenied(path.display().to_string())
        } else {
            LinuxPlatformError::MissingDevice(path.display().to_string())
        }
    })?;
    if !metadata.file_type().is_char_device() {
        return Err(LinuxPlatformError::InvalidDevice(path.display().to_string()));
    }
    let device = inspect_video_devices()?
        .into_iter()
        .find(|device| device.path == path)
        .ok_or_else(|| LinuxPlatformError::SysfsUnavailable(path.display().to_string()))?;
    if !device.is_v4l2loopback() {
        return Err(LinuxPlatformError::MissingV4l2Loopback(path.display().to_string()));
    }
    Ok(device)
}
