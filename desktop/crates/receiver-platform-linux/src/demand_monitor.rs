#![allow(unsafe_code)]

use std::{
    fs::OpenOptions,
    os::fd::{AsFd, AsRawFd},
    os::unix::fs::OpenOptionsExt,
    path::Path,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
        Arc,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use nix::{
    errno::Errno,
    poll::{poll, PollFd, PollFlags, PollTimeout},
};
use receiver_core::VirtualCameraDemand;

use crate::{validate_v4l2loopback, LinuxPlatformError};

pub const DEMAND_ACTIVATION_DEBOUNCE: Duration = Duration::from_millis(200);
pub const DEMAND_RELEASE_DEBOUNCE: Duration = Duration::from_millis(100);
pub const DEMAND_POLL_INTERVAL: Duration = Duration::from_millis(100);
pub const PERSISTENT_PRODUCER_BASELINE: u32 = 0;

const V4L2_EVENT_PRIVATE_START: u32 = 0x0800_0000;
const V4L2LOOPBACK_EVENT_OFFSET: u32 = 0x08e0_0000;
const V4L2_EVENT_PRI_CLIENT_USAGE: u32 = V4L2_EVENT_PRIVATE_START + V4L2LOOPBACK_EVENT_OFFSET + 1;
const V4L2_EVENT_SUB_FL_SEND_INITIAL: u32 = 1;
const V4L2_IOCTL_TYPE: u8 = b'V';
const V4L2_IOCTL_DEQUEUE_EVENT: u8 = 89;
const V4L2_IOCTL_SUBSCRIBE_EVENT: u8 = 90;
const V4L2_IOCTL_UNSUBSCRIBE_EVENT: u8 = 91;
const V4L2_EVENT_SUBSCRIPTION_RESERVED_WORDS: usize = 5;
#[cfg(test)]
const NO_PRODUCER_BASELINE: u32 = 0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VirtualCameraDemandEvent {
    pub demand: VirtualCameraDemand,
}

#[derive(Debug, Clone, Copy)]
pub struct DemandMonitorConfig {
    pub activation_debounce: Duration,
    pub release_debounce: Duration,
    pub poll_interval: Duration,
}

impl Default for DemandMonitorConfig {
    fn default() -> Self {
        Self {
            activation_debounce: DEMAND_ACTIVATION_DEBOUNCE,
            release_debounce: DEMAND_RELEASE_DEBOUNCE,
            poll_interval: DEMAND_POLL_INTERVAL,
        }
    }
}

pub struct V4l2LoopbackDemandMonitor {
    shutdown: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl V4l2LoopbackDemandMonitor {
    pub fn start(
        device: impl AsRef<Path>,
        producer_baseline: u32,
    ) -> Result<(Self, Receiver<VirtualCameraDemandEvent>), LinuxPlatformError> {
        Self::start_with_config(device, producer_baseline, DemandMonitorConfig::default())
    }

    pub fn start_with_config(
        device: impl AsRef<Path>,
        producer_baseline: u32,
        config: DemandMonitorConfig,
    ) -> Result<(Self, Receiver<VirtualCameraDemandEvent>), LinuxPlatformError> {
        let device = device.as_ref().to_path_buf();
        validate_v4l2loopback(&device)?;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(&device)
            .map_err(|error| {
                LinuxPlatformError::DemandMonitor(format!(
                    "open {} for client-usage events: {error}",
                    device.display()
                ))
            })?;
        subscribe_client_usage(file.as_raw_fd()).map_err(|error| {
            LinuxPlatformError::DemandEventsUnavailable(device.display().to_string(), error)
        })?;

        let (event_tx, event_rx) = mpsc::channel();
        let shutdown = Arc::new(AtomicBool::new(false));
        let thread_shutdown = shutdown.clone();
        let thread_device = device.clone();
        let thread = thread::Builder::new()
            .name("mobile-webcam-demand".to_owned())
            .spawn(move || {
                monitor_loop(
                    &file,
                    &thread_device,
                    producer_baseline,
                    config,
                    &thread_shutdown,
                    &event_tx,
                );
            })
            .map_err(|error| LinuxPlatformError::DemandMonitor(error.to_string()))?;
        Ok((Self { shutdown, thread: Some(thread) }, event_rx))
    }

    pub fn stop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for V4l2LoopbackDemandMonitor {
    fn drop(&mut self) {
        self.stop();
    }
}

pub struct DemandMonitorHandle {
    monitor: V4l2LoopbackDemandMonitor,
    events: Receiver<VirtualCameraDemandEvent>,
}

impl DemandMonitorHandle {
    pub fn start(
        device: impl AsRef<Path>,
        producer_baseline: u32,
    ) -> Result<Self, LinuxPlatformError> {
        let (monitor, events) = V4l2LoopbackDemandMonitor::start(device, producer_baseline)?;
        Ok(Self { monitor, events })
    }

    pub fn try_recv(&self) -> Result<VirtualCameraDemandEvent, mpsc::TryRecvError> {
        self.events.try_recv()
    }

    pub fn stop(&mut self) {
        self.monitor.stop();
    }
}

impl Drop for DemandMonitorHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

fn monitor_loop(
    file: &std::fs::File,
    device: &Path,
    producer_baseline: u32,
    config: DemandMonitorConfig,
    shutdown: &AtomicBool,
    event_tx: &Sender<VirtualCameraDemandEvent>,
) {
    let mut debouncer = DemandDebouncer::new(config);
    let mut poll_fd = [PollFd::new(file.as_fd(), PollFlags::POLLPRI)];
    while !shutdown.load(Ordering::Relaxed) {
        let timeout = PollTimeout::try_from(config.poll_interval).unwrap_or(PollTimeout::MAX);
        if let Err(error) = poll(&mut poll_fd, timeout) {
            if error != Errno::EINTR {
                tracing::error!(%error, path = %device.display(), "v4l2loopback demand poll failed");
            }
            continue;
        }
        loop {
            match dequeue_client_usage(file.as_raw_fd()) {
                Ok(raw_count) => {
                    let effective_count = effective_consumer_count(raw_count, producer_baseline);
                    if let Some(event) = debouncer.observe(effective_count, Instant::now()) {
                        let _ = event_tx.send(event);
                    }
                }
                Err(error)
                    if error == Errno::EAGAIN
                        || error == Errno::EWOULDBLOCK
                        || error == Errno::ENOENT =>
                {
                    break;
                }
                Err(error) => {
                    tracing::error!(%error, path = %device.display(), "v4l2loopback event dequeue failed");
                    break;
                }
            }
        }
        if let Some(event) = debouncer.expire(Instant::now()) {
            let _ = event_tx.send(event);
        }
    }
    let _ = unsubscribe_client_usage(file.as_raw_fd());
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DebouncedDemand {
    Inactive,
    Active(u32),
}

#[derive(Debug, Clone, Copy)]
struct PendingDemand {
    target: DebouncedDemand,
    deadline: Instant,
}

#[derive(Debug, Clone, Copy)]
struct DemandDebouncer {
    current: DebouncedDemand,
    pending: Option<PendingDemand>,
    config: DemandMonitorConfig,
}

impl DemandDebouncer {
    fn new(config: DemandMonitorConfig) -> Self {
        Self { current: DebouncedDemand::Inactive, pending: None, config }
    }

    fn observe(&mut self, consumer_count: u32, now: Instant) -> Option<VirtualCameraDemandEvent> {
        let target = if consumer_count == 0 {
            DebouncedDemand::Inactive
        } else {
            DebouncedDemand::Active(consumer_count)
        };
        if self.current.is_active() == target.is_active() {
            self.current = target;
            self.pending = None;
            return None;
        }
        if self.pending.map_or(true, |pending| pending.target.is_active() != target.is_active()) {
            let debounce = match target {
                DebouncedDemand::Inactive => self.config.release_debounce,
                DebouncedDemand::Active(_) => self.config.activation_debounce,
            };
            self.pending = Some(PendingDemand { target, deadline: now + debounce });
        } else if let Some(pending) = &mut self.pending {
            pending.target = target;
        }
        self.expire(now)
    }

    fn expire(&mut self, now: Instant) -> Option<VirtualCameraDemandEvent> {
        let pending = self.pending?;
        if now < pending.deadline {
            return None;
        }
        self.pending = None;
        self.current = pending.target;
        Some(VirtualCameraDemandEvent {
            demand: match pending.target {
                DebouncedDemand::Inactive => VirtualCameraDemand::Inactive,
                DebouncedDemand::Active(consumer_count) => {
                    VirtualCameraDemand::Active { consumer_count }
                }
            },
        })
    }
}

impl DebouncedDemand {
    fn is_active(self) -> bool {
        matches!(self, Self::Active(_))
    }
}

fn effective_consumer_count(raw_count: u32, producer_baseline: u32) -> u32 {
    raw_count.saturating_sub(producer_baseline)
}

fn subscribe_client_usage(fd: i32) -> Result<(), String> {
    let subscription = V4l2EventSubscription {
        event_type: V4L2_EVENT_PRI_CLIENT_USAGE,
        id: 0,
        flags: V4L2_EVENT_SUB_FL_SEND_INITIAL,
        reserved: [0; V4L2_EVENT_SUBSCRIPTION_RESERVED_WORDS],
    };
    // Safety: the file descriptor is open, the subscription is repr(C), and the
    // ioctl only reads the fixed-size structure supplied by this call.
    unsafe { vidioc_subscribe_event(fd, &subscription) }
        .map(|_| ())
        .map_err(|error| error.to_string())
}

fn unsubscribe_client_usage(fd: i32) -> Result<(), String> {
    let subscription = V4l2EventSubscription {
        event_type: V4L2_EVENT_PRI_CLIENT_USAGE,
        id: 0,
        flags: 0,
        reserved: [0; V4L2_EVENT_SUBSCRIPTION_RESERVED_WORDS],
    };
    // Safety: the file descriptor remains open until this function returns and
    // the ioctl receives the same fixed-size subscription used for subscribe.
    unsafe { vidioc_unsubscribe_event(fd, &subscription) }
        .map(|_| ())
        .map_err(|error| error.to_string())
}

fn dequeue_client_usage(fd: i32) -> Result<u32, Errno> {
    let mut event = V4l2Event::default();
    // Safety: the driver writes one v4l2_event into this zeroed repr(C) buffer;
    // the buffer layout matches the Linux UAPI structure exactly.
    unsafe { vidioc_dequeue_event(fd, &mut event) }?;
    if event.event_type != V4L2_EVENT_PRI_CLIENT_USAGE {
        return Err(Errno::EINVAL);
    }
    Ok(u32::from_ne_bytes(event.data.bytes[..std::mem::size_of::<u32>()].try_into().unwrap()))
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
struct V4l2EventSubscription {
    event_type: u32,
    id: u32,
    flags: u32,
    reserved: [u32; V4L2_EVENT_SUBSCRIPTION_RESERVED_WORDS],
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
struct V4l2Event {
    event_type: u32,
    data: V4l2EventData,
    pending: u32,
    sequence: u32,
    timestamp: libc::timespec,
    id: u32,
    reserved: [u32; 8],
}

#[repr(C, align(8))]
#[derive(Debug, Clone, Copy)]
struct V4l2EventData {
    bytes: [u8; 64],
}

impl Default for V4l2Event {
    fn default() -> Self {
        Self {
            event_type: 0,
            data: V4l2EventData { bytes: [0; 64] },
            pending: 0,
            sequence: 0,
            timestamp: libc::timespec { tv_sec: 0, tv_nsec: 0 },
            id: 0,
            reserved: [0; 8],
        }
    }
}

nix::ioctl_write_ptr!(
    vidioc_subscribe_event,
    V4L2_IOCTL_TYPE,
    V4L2_IOCTL_SUBSCRIBE_EVENT,
    V4l2EventSubscription
);
nix::ioctl_read!(vidioc_dequeue_event, V4L2_IOCTL_TYPE, V4L2_IOCTL_DEQUEUE_EVENT, V4l2Event);
nix::ioctl_write_ptr!(
    vidioc_unsubscribe_event,
    V4L2_IOCTL_TYPE,
    V4L2_IOCTL_UNSUBSCRIBE_EVENT,
    V4l2EventSubscription
);

#[cfg(test)]
mod tests {
    use super::*;

    fn test_now() -> Instant {
        Instant::now()
    }

    #[test]
    fn producer_baseline_is_subtracted_without_underflow() {
        assert_eq!(effective_consumer_count(4, 1), 3);
        assert_eq!(effective_consumer_count(1, 4), 0);
        assert_eq!(effective_consumer_count(0, NO_PRODUCER_BASELINE), 0);
    }

    #[test]
    fn event_struct_matches_linux_uapi_size() {
        assert_eq!(std::mem::size_of::<V4l2Event>(), 136);
        assert_eq!(std::mem::size_of::<V4l2EventSubscription>(), 32);
        assert_eq!(std::mem::offset_of!(V4l2Event, data), 8);
        assert_eq!(std::mem::offset_of!(V4l2Event, pending), 72);
    }

    #[test]
    fn activation_requires_sustained_usage() {
        let config = DemandMonitorConfig {
            activation_debounce: Duration::from_millis(20),
            release_debounce: Duration::from_millis(10),
            poll_interval: DEMAND_POLL_INTERVAL,
        };
        let mut debouncer = DemandDebouncer::new(config);
        let now = test_now();
        assert!(debouncer.observe(1, now).is_none());
        assert!(debouncer.observe(0, now + Duration::from_millis(19)).is_none());
        assert!(debouncer.observe(1, now + Duration::from_millis(20)).is_none());
        let event = debouncer.observe(1, now + Duration::from_millis(40)).unwrap();
        assert_eq!(event.demand, VirtualCameraDemand::Active { consumer_count: 1 });
    }

    #[test]
    fn release_is_debounced_and_multiple_consumers_remain_active() {
        let config = DemandMonitorConfig {
            activation_debounce: Duration::ZERO,
            release_debounce: Duration::from_millis(10),
            poll_interval: DEMAND_POLL_INTERVAL,
        };
        let mut debouncer = DemandDebouncer::new(config);
        let now = test_now();
        let active = debouncer.observe(2, now).unwrap();
        assert_eq!(active.demand, VirtualCameraDemand::Active { consumer_count: 2 });
        assert!(debouncer.observe(1, now + Duration::from_millis(1)).is_none());
        assert!(debouncer.observe(0, now + Duration::from_millis(2)).is_none());
        let inactive = debouncer.observe(0, now + Duration::from_millis(12)).unwrap();
        assert_eq!(inactive.demand, VirtualCameraDemand::Inactive);
    }

    #[test]
    fn duplicate_raw_events_do_not_emit_duplicate_state_changes() {
        let config = DemandMonitorConfig {
            activation_debounce: Duration::ZERO,
            release_debounce: Duration::ZERO,
            poll_interval: DEMAND_POLL_INTERVAL,
        };
        let mut debouncer = DemandDebouncer::new(config);
        let now = test_now();
        assert!(debouncer.observe(1, now).is_some());
        assert!(debouncer.observe(1, now).is_none());
        assert!(debouncer.observe(0, now).is_some());
        assert!(debouncer.observe(0, now).is_none());
    }
}
