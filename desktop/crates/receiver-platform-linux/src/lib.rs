//! Linux video-device inspection and virtual-camera output.

mod demand_monitor;
mod device;
mod error;
mod inspection;
mod output_factory;
mod persistent_sink_factory;
mod v4l2loopback;
mod virtual_camera_output;

pub use demand_monitor::{
    DemandMonitorConfig, DemandMonitorHandle, V4l2LoopbackDemandMonitor, VirtualCameraDemandEvent,
    DEMAND_ACTIVATION_DEBOUNCE, DEMAND_POLL_INTERVAL, DEMAND_RELEASE_DEBOUNCE,
    PERSISTENT_PRODUCER_BASELINE,
};
pub use device::VideoDevice;
pub use error::LinuxPlatformError;
pub use inspection::inspect_video_devices;
pub use output_factory::LinuxVideoSinkFactory;
pub use persistent_sink_factory::PersistentVideoSinkFactory;
pub use v4l2loopback::{
    find_v4l2loopback_device, resolve_v4l2loopback_device, validate_v4l2loopback,
};
pub use virtual_camera_output::{
    PersistentVirtualCameraOutput, VirtualCameraOutputMode, STANDBY_FRAME_RATE,
    VIRTUAL_CAMERA_FRAME_RATE, VIRTUAL_CAMERA_HEIGHT, VIRTUAL_CAMERA_WIDTH,
};
