#![allow(unsafe_code)]

use std::{
    fs::OpenOptions,
    os::{fd::AsRawFd, unix::fs::OpenOptionsExt},
    path::Path,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, RwLock,
    },
    thread,
    time::Duration,
};

use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;

use crate::{validate_v4l2loopback, LinuxPlatformError};

pub const VIRTUAL_CAMERA_WIDTH: i32 = 1_280;
pub const VIRTUAL_CAMERA_HEIGHT: i32 = 720;
pub const VIRTUAL_CAMERA_FRAME_RATE: i32 = 30;
pub const STANDBY_FRAME_RATE: i32 = 5;
const APP_SOURCE_MAX_BUFFERS: u32 = 2;
const NANOSECONDS_PER_SECOND: u64 = 1_000_000_000;
const LIVE_FRAME_INTERVAL_MILLIS: u64 = 1_000 / VIRTUAL_CAMERA_FRAME_RATE as u64;
const STANDBY_FRAME_INTERVAL_MILLIS: u64 = 1_000 / STANDBY_FRAME_RATE as u64;
const FRAME_DURATION_NANOSECONDS: u64 = NANOSECONDS_PER_SECOND / VIRTUAL_CAMERA_FRAME_RATE as u64;
const BLACK_Y: u8 = 16;
const BLACK_CHROMA: u8 = 128;
const YUY2_BYTES_PER_PIXEL: usize = 2;
const APP_SOURCE_MAX_BYTES_UNBOUNDED: u64 = 0;
const PIPELINE_START_TIMEOUT_SECONDS: u64 = 5;
const V4L2_IOCTL_TYPE: u8 = b'V';
const V4L2_IOCTL_SET_CONTROL: u8 = 28;
const V4L2_USER_CONTROL_BASE: u32 = 0x0098_0900;
const V4L2LOOPBACK_CONTROL_BASE: u32 = V4L2_USER_CONTROL_BASE | 0xf000;
const V4L2_KEEP_FORMAT_CONTROL_ID: u32 = V4L2LOOPBACK_CONTROL_BASE;
const V4L2_KEEP_FORMAT_DISABLED: i32 = 0;
const V4L2_KEEP_FORMAT_ENABLED: i32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VirtualCameraOutputMode {
    Standby,
    Live,
}

#[derive(Clone)]
pub struct PersistentVirtualCameraOutput {
    shared: Arc<SharedOutput>,
}

struct SharedOutput {
    state: Mutex<OutputState>,
    live: AtomicBool,
    running: AtomicBool,
    latest_sample: RwLock<Option<gst::Sample>>,
    pusher: Mutex<Option<thread::JoinHandle<()>>>,
}

struct OutputState {
    pipeline: gst::Pipeline,
    appsrc: gst_app::AppSrc,
    standby_sample: gst::Sample,
}

#[allow(clippy::too_many_lines)]
impl PersistentVirtualCameraOutput {
    pub fn start(device: impl AsRef<std::path::Path>) -> Result<Self, LinuxPlatformError> {
        let device = device.as_ref();
        validate_v4l2loopback(device)?;
        gst::init().map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))?;

        let pipeline = gst::Pipeline::with_name("mobile-webcam-virtual-camera");
        let appsrc_element = make_element("appsrc", "virtual-camera-source")?;
        let appsrc = appsrc_element.clone().downcast::<gst_app::AppSrc>().map_err(|_| {
            LinuxPlatformError::PersistentOutput(
                "GStreamer appsrc element has the wrong type".to_owned(),
            )
        })?;
        let convert = make_element("videoconvert", "virtual-camera-convert")?;
        let scale = make_element("videoscale", "virtual-camera-scale")?;
        let output_caps_filter = make_element("capsfilter", "virtual-camera-caps")?;
        let sink = make_element("v4l2sink", "persistent-virtual-camera-sink")?;

        set_keep_format(device, V4L2_KEEP_FORMAT_DISABLED)?;

        appsrc.set_property("is-live", true);
        appsrc.set_property("do-timestamp", true);
        appsrc.set_property("block", false);
        appsrc.set_property("format", gst::Format::Time);
        appsrc.set_property("max-buffers", u64::from(APP_SOURCE_MAX_BUFFERS));
        appsrc.set_property("max-bytes", APP_SOURCE_MAX_BYTES_UNBOUNDED);
        appsrc.set_property("caps", output_caps());
        appsrc.set_property_from_str("leaky-type", "downstream");
        output_caps_filter.set_property("caps", output_caps());

        sink.set_property("device", device.to_string_lossy().as_ref());
        sink.set_property("sync", false);

        pipeline
            .add_many([&appsrc_element, &convert, &scale, &output_caps_filter, &sink])
            .map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))?;
        link(&[&appsrc_element, &convert, &scale, &output_caps_filter, &sink])?;

        let bus = pipeline.bus().ok_or_else(|| {
            LinuxPlatformError::PersistentOutput("persistent pipeline has no bus".to_owned())
        })?;
        bus.connect_message(None, |_bus, message| match message.view() {
            gst::MessageView::Error(error) => {
                tracing::error!(
                    src = ?error.src().map(gst::prelude::GstObjectExt::path_string),
                    error = %error.error(),
                    "persistent virtual-camera pipeline error",
                );
            }
            gst::MessageView::Warning(warning) => {
                tracing::warn!(
                    src = ?warning.src().map(gst::prelude::GstObjectExt::path_string),
                    warning = %warning.error(),
                    "persistent virtual-camera pipeline warning",
                );
            }
            _ => {}
        });
        pipeline
            .set_state(gst::State::Playing)
            .map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))?;

        let shared = Arc::new(SharedOutput {
            state: Mutex::new(OutputState { pipeline, appsrc, standby_sample: black_sample() }),
            live: AtomicBool::new(false),
            running: AtomicBool::new(true),
            latest_sample: RwLock::new(None),
            pusher: Mutex::new(None),
        });
        let pusher_shared = shared.clone();
        let pusher = thread::Builder::new()
            .name("mobile-webcam-output".to_owned())
            .spawn(move || pusher_loop(&pusher_shared))
            .map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))?;
        *shared.pusher.lock().map_err(|_| {
            LinuxPlatformError::PersistentOutput("persistent output lock poisoned".to_owned())
        })? = Some(pusher);

        let pipeline = shared
            .state
            .lock()
            .map_err(|_| {
                LinuxPlatformError::PersistentOutput("persistent output lock poisoned".to_owned())
            })?
            .pipeline
            .clone();
        let (success, state, _pending) =
            pipeline.state(gst::ClockTime::from_seconds(PIPELINE_START_TIMEOUT_SECONDS));
        let state_is_startable = matches!(state, gst::State::Paused | gst::State::Playing);
        if success.is_err() || !state_is_startable {
            let output = Self { shared };
            let _ = output.stop();
            return Err(LinuxPlatformError::PersistentOutput(format!(
                "persistent pipeline did not reach Playing: success={success:?} state={state:?}"
            )));
        }

        if let Err(error) = set_keep_format(device, V4L2_KEEP_FORMAT_ENABLED) {
            let output = Self { shared };
            let _ = output.stop();
            return Err(error);
        }

        Ok(Self { shared })
    }

    pub fn push_live_sample(&self, sample: &gst::Sample) -> Result<(), String> {
        if sample.buffer().is_none() {
            return Err("live sample has no buffer".to_owned());
        }
        *self
            .shared
            .latest_sample
            .write()
            .map_err(|_| "persistent output lock poisoned".to_owned())? = Some(sample.clone());
        self.shared.live.store(true, Ordering::Relaxed);
        Ok(())
    }

    pub fn set_standby(&self) -> Result<(), String> {
        self.shared.live.store(false, Ordering::Relaxed);
        *self
            .shared
            .latest_sample
            .write()
            .map_err(|_| "persistent output lock poisoned".to_owned())? = None;
        Ok(())
    }

    pub fn mode(&self) -> Result<VirtualCameraOutputMode, String> {
        if self.shared.live.load(Ordering::Relaxed) {
            Ok(VirtualCameraOutputMode::Live)
        } else {
            Ok(VirtualCameraOutputMode::Standby)
        }
    }

    pub fn stop(&self) -> Result<(), String> {
        self.shared.running.store(false, Ordering::Relaxed);
        if let Some(pusher) = self
            .shared
            .pusher
            .lock()
            .map_err(|_| "persistent output lock poisoned".to_owned())?
            .take()
        {
            let _ = pusher.join();
        }
        let state =
            self.shared.state.lock().map_err(|_| "persistent output lock poisoned".to_owned())?;
        state.pipeline.set_state(gst::State::Null).map_err(|error| error.to_string())?;
        Ok(())
    }
}

fn pusher_loop(shared: &Arc<SharedOutput>) {
    loop {
        if !shared.running.load(Ordering::Relaxed) {
            break;
        }
        let live = shared.live.load(Ordering::Relaxed);
        let sample = if live {
            shared.latest_sample.read().ok().and_then(|sample| sample.clone())
        } else {
            shared.state.lock().ok().map(|state| state.standby_sample.clone())
        };
        if let Some(sample) = sample {
            if let Ok(state) = shared.state.lock() {
                if live {
                    if let Some(buffer) = sample.buffer() {
                        let mut live_buffer = buffer.to_owned();
                        if let Some(buffer) = live_buffer.get_mut() {
                            buffer.set_pts(gst::ClockTime::NONE);
                            buffer.set_dts(gst::ClockTime::NONE);
                            buffer.set_duration(gst::ClockTime::from_nseconds(
                                FRAME_DURATION_NANOSECONDS,
                            ));
                        }
                        let _ = state.appsrc.push_buffer(live_buffer);
                    }
                } else {
                    let _ = state.appsrc.push_sample(&sample);
                }
            }
        }
        let interval =
            if live { LIVE_FRAME_INTERVAL_MILLIS } else { STANDBY_FRAME_INTERVAL_MILLIS };
        thread::sleep(Duration::from_millis(interval));
    }
}

fn black_sample() -> gst::Sample {
    let size = usize::try_from(VIRTUAL_CAMERA_WIDTH).unwrap()
        * usize::try_from(VIRTUAL_CAMERA_HEIGHT).unwrap()
        * YUY2_BYTES_PER_PIXEL;
    let mut pixels = vec![BLACK_CHROMA; size];
    for byte in pixels.iter_mut().step_by(YUY2_BYTES_PER_PIXEL) {
        *byte = BLACK_Y;
    }
    let mut buffer = gst::Buffer::from_mut_slice(pixels);
    buffer
        .get_mut()
        .unwrap()
        .set_duration(gst::ClockTime::from_nseconds(FRAME_DURATION_NANOSECONDS));
    gst::Sample::builder().caps(&output_caps()).buffer(&buffer).build()
}

fn make_element(factory: &str, name: &str) -> Result<gst::Element, LinuxPlatformError> {
    gst::ElementFactory::make(factory)
        .name(name)
        .build()
        .map_err(|error| LinuxPlatformError::PersistentOutput(format!("create {factory}: {error}")))
}

fn link(elements: &[&gst::Element]) -> Result<(), LinuxPlatformError> {
    gst::Element::link_many(elements)
        .map_err(|error| LinuxPlatformError::PersistentOutput(error.to_string()))
}

fn output_caps() -> gst::Caps {
    gst::Caps::builder("video/x-raw")
        .field("format", "YUY2")
        .field("width", VIRTUAL_CAMERA_WIDTH)
        .field("height", VIRTUAL_CAMERA_HEIGHT)
        .field("framerate", gst::Fraction::new(VIRTUAL_CAMERA_FRAME_RATE, 1))
        .build()
}

fn set_keep_format(device: &Path, value: i32) -> Result<(), LinuxPlatformError> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_NONBLOCK)
        .open(device)
        .map_err(|error| {
            LinuxPlatformError::PersistentOutput(format!(
                "open {} to set v4l2loopback keep_format: {error}",
                device.display()
            ))
        })?;
    let mut control = V4l2Control { id: V4L2_KEEP_FORMAT_CONTROL_ID, value };
    // Safety: the file descriptor is open and the ioctl receives a fixed-size
    // repr(C) control structure defined by the V4L2 userspace ABI.
    unsafe { vidioc_set_control(file.as_raw_fd(), &mut control) }.map_err(|error| {
        LinuxPlatformError::PersistentOutput(format!(
            "set v4l2loopback keep_format on {}: {error}",
            device.display()
        ))
    })?;
    Ok(())
}

#[repr(C)]
struct V4l2Control {
    id: u32,
    value: i32,
}

nix::ioctl_readwrite!(vidioc_set_control, V4L2_IOCTL_TYPE, V4L2_IOCTL_SET_CONTROL, V4l2Control);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standby_caps_use_the_fixed_virtual_camera_format() {
        gst::init().unwrap();
        let caps = output_caps();
        let structure = caps.structure(0).unwrap();
        assert_eq!(structure.get::<&str>("format"), Ok("YUY2"));
        assert_eq!(structure.get::<i32>("width"), Ok(VIRTUAL_CAMERA_WIDTH));
        assert_eq!(structure.get::<i32>("height"), Ok(VIRTUAL_CAMERA_HEIGHT));
        assert_eq!(
            structure.get::<gst::Fraction>("framerate"),
            Ok(gst::Fraction::new(VIRTUAL_CAMERA_FRAME_RATE, 1)),
        );
    }

    #[test]
    fn output_mode_has_explicit_standby_and_live_states() {
        assert_ne!(VirtualCameraOutputMode::Standby, VirtualCameraOutputMode::Live);
    }

    #[test]
    fn standby_frame_is_yuy2_black() {
        let sample = black_sample();
        let frame = sample.buffer().unwrap();
        let mapped = frame.map_readable().unwrap();
        let bytes = mapped.as_slice();
        let expected_bytes = usize::try_from(VIRTUAL_CAMERA_WIDTH).unwrap()
            * usize::try_from(VIRTUAL_CAMERA_HEIGHT).unwrap()
            * YUY2_BYTES_PER_PIXEL;
        assert_eq!(bytes.len(), expected_bytes);
        for (index, byte) in bytes.iter().enumerate() {
            let expected = if index % 2 == 0 { BLACK_Y } else { BLACK_CHROMA };
            assert_eq!(*byte, expected, "byte {index} is not black");
        }
        assert!(sample.caps().is_some());
    }

    #[test]
    fn frame_intervals_are_derived_from_the_named_frame_rates() {
        assert_eq!(LIVE_FRAME_INTERVAL_MILLIS, 33);
        assert_eq!(STANDBY_FRAME_INTERVAL_MILLIS, 200);
    }
}
