use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoDevice {
    pub path: PathBuf,
    pub name: Option<String>,
    pub driver: Option<String>,
    pub(crate) is_virtual_sysfs: bool,
}

impl VideoDevice {
    pub fn from_sysfs_entry(entry: &Path) -> Self {
        let name_path = entry.join("name");
        let name = std::fs::read_to_string(name_path).ok().map(|name| name.trim().to_owned());
        let driver = std::fs::read_link(entry.join("device/driver"))
            .ok()
            .and_then(|path| path.file_name().map(|name| name.to_string_lossy().into_owned()));
        let is_virtual_sysfs = std::fs::canonicalize(entry)
            .is_ok_and(|path| path.starts_with("/sys/devices/virtual/video4linux"));
        let path = PathBuf::from("/dev").join(entry.file_name().unwrap_or_default());
        Self { path, name, driver, is_virtual_sysfs }
    }

    pub fn is_v4l2loopback(&self) -> bool {
        self.is_v4l2loopback_with_module(std::path::Path::new("/sys/module/v4l2loopback").exists())
    }

    fn is_v4l2loopback_with_module(&self, module_loaded: bool) -> bool {
        self.driver.as_deref() == Some("v4l2loopback") || (self.is_virtual_sysfs && module_loaded)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn sysfs_entry_reports_v4l2loopback_driver() {
        let root =
            std::env::temp_dir().join(format!("mobile-webcam-device-test-{}", std::process::id()));
        let driver_dir = root.join("drivers/v4l2loopback");
        std::fs::create_dir_all(&driver_dir).unwrap();
        std::fs::create_dir_all(root.join("device")).unwrap();
        std::fs::write(root.join("name"), "Mobile Webcam\n").unwrap();
        std::os::unix::fs::symlink(&driver_dir, root.join("device/driver")).unwrap();
        let device = VideoDevice::from_sysfs_entry(&root);
        assert_eq!(device.name.as_deref(), Some("Mobile Webcam"));
        assert_eq!(device.driver.as_deref(), Some("v4l2loopback"));
        assert!(device.is_v4l2loopback());
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn virtual_sysfs_entry_requires_loaded_loopback_module() {
        let device = VideoDevice {
            path: PathBuf::from("/dev/video10"),
            name: Some("Mobile Webcam".to_owned()),
            driver: None,
            is_virtual_sysfs: true,
        };

        assert!(!device.is_v4l2loopback_with_module(false));
        assert!(device.is_v4l2loopback_with_module(true));
    }
}
