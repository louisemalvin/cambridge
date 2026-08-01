use thiserror::Error;

#[derive(Debug, Error)]
pub enum LinuxPlatformError {
    #[error("video device path does not exist: {0}")]
    MissingDevice(String),
    #[error("video device is not a character device: {0}")]
    InvalidDevice(String),
    #[error("permission denied accessing video device: {0}")]
    PermissionDenied(String),
    #[error("v4l2loopback is not attached to {0}")]
    MissingV4l2Loopback(String),
    #[error("no v4l2loopback video device was found")]
    NoV4l2LoopbackDevice,
    #[error("Linux video sysfs is unavailable: {0}")]
    SysfsUnavailable(String),
    #[error("output format {0} is not supported by the configured virtual camera")]
    UnsupportedOutputFormat(String),
    #[error("GStreamer v4l2sink is unavailable: {0}")]
    MissingV4l2Sink(String),
    #[error("failed to create virtual-camera sink: {0}")]
    SinkCreation(String),
}
