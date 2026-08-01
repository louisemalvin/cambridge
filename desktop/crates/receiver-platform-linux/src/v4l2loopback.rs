use std::os::unix::fs::FileTypeExt;
use std::path::Path;

use crate::{inspection::inspect_video_devices, LinuxPlatformError, VideoDevice};

pub fn resolve_v4l2loopback_device(
    explicit_path: Option<&Path>,
) -> Result<VideoDevice, LinuxPlatformError> {
    match explicit_path {
        Some(path) => validate_v4l2loopback(path),
        None => find_v4l2loopback_device(),
    }
}

pub fn find_v4l2loopback_device() -> Result<VideoDevice, LinuxPlatformError> {
    let devices = inspect_video_devices()?;
    select_v4l2loopback_device(&devices).ok_or(LinuxPlatformError::NoV4l2LoopbackDevice)
}

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

fn select_v4l2loopback_device(devices: &[VideoDevice]) -> Option<VideoDevice> {
    devices
        .iter()
        .filter(|device| device.is_v4l2loopback())
        .min_by_key(|device| video_device_number(device).unwrap_or(u32::MAX))
        .cloned()
}

fn video_device_number(device: &VideoDevice) -> Option<u32> {
    device
        .path
        .file_name()
        .and_then(|name| name.to_str())
        .and_then(|name| name.strip_prefix("video"))
        .and_then(|number| number.parse().ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(path: &str, driver: Option<&str>) -> VideoDevice {
        VideoDevice {
            path: Path::new(path).to_path_buf(),
            name: None,
            driver: driver.map(str::to_owned),
        }
    }

    #[test]
    fn selects_the_first_loopback_device_in_sorted_order() {
        let devices = [
            device("/dev/video2", Some("uvcvideo")),
            device("/dev/video11", Some("v4l2loopback")),
            device("/dev/video10", Some("v4l2loopback")),
            device("/dev/video2", Some("v4l2loopback")),
        ];

        assert_eq!(select_v4l2loopback_device(&devices).unwrap().path, Path::new("/dev/video2"));
    }

    #[test]
    fn returns_no_candidate_when_loopback_is_not_present() {
        let devices = [device("/dev/video2", Some("uvcvideo"))];

        assert!(select_v4l2loopback_device(&devices).is_none());
    }
}
