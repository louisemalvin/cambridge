//! Linux video-device inspection and virtual-camera output.

mod device;
mod error;
mod inspection;
mod output_factory;
mod v4l2loopback;

pub use device::VideoDevice;
pub use error::LinuxPlatformError;
pub use inspection::inspect_video_devices;
pub use output_factory::LinuxVideoSinkFactory;
pub use v4l2loopback::{
    find_v4l2loopback_device, resolve_v4l2loopback_device, validate_v4l2loopback,
};
