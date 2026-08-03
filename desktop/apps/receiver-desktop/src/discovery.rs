use std::{
    collections::{HashMap, HashSet},
    fs,
    io::{BufRead, BufReader, Read, Write},
    net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream},
    os::unix::fs::OpenOptionsExt,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use anyhow::{Context, Result};
use if_addrs::IfAddr;
use receiver_core::VirtualCameraDemand;
use sender_control_protocol::{
    DescribeSenderRequest, SenderAdvertisement, SenderAvailability, SenderControlAction,
    StartStreamRequest, StartStreamResponse, StartStreamStatus, StopStreamRequest,
    StopStreamResponse, StopStreamStatus, MAX_MESSAGE_BYTES, PROTOCOL_VERSION, SENDER_CONTROL_PORT,
};
use serde::{Deserialize, Serialize};
use tracing::{info, warn};
use uuid::Uuid;

const RETRY_MIN_INTERVAL: Duration = Duration::from_secs(2);
const RETRY_MAX_INTERVAL: Duration = Duration::from_secs(30);
const BUSY_RETRY_INTERVAL: Duration = Duration::from_secs(15);
const RETRY_BACKOFF_BASE_SECONDS: u64 = RETRY_MIN_INTERVAL.as_secs();
const RETRY_BACKOFF_SHIFT_LIMIT: u32 = 4;
const SCAN_INTERVAL: Duration = Duration::from_secs(5);
const PHONE_EXPIRY: Duration = Duration::from_secs(12);
const PROBE_TIMEOUT: Duration = Duration::from_millis(120);
const CONTROL_TIMEOUT: Duration = Duration::from_secs(10);
const COMMAND_POLL_INTERVAL: Duration = Duration::from_millis(200);
const SCAN_RETRY_SLEEP: Duration = Duration::from_millis(100);
const MAX_SCAN_HOSTS: u32 = 4_096;
const SCAN_WORKERS: usize = 32;
const IPV4_ADDRESS_BITS: u32 = 32;
const RESERVED_SUBNET_HOSTS: u32 = 2;
const FIRST_HOST_OFFSET: u32 = 1;
const MIN_SCAN_CHUNK_SIZE: usize = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredPhone {
    pub sender_id: String,
    pub display_name: String,
    pub availability: SenderAvailability,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectionState {
    Searching,
    PairedStandby(String),
    WaitingForSelection,
    Connecting(String),
    WaitingForApproval(String),
    CameraPermissionRequired(String),
    Connected(String),
    Stopping(String),
    Error(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MediaLifecycle {
    Idle,
    Starting,
    Streaming,
    Stopping,
    Failed(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoverySnapshot {
    pub phones: Vec<DiscoveredPhone>,
    pub selected_sender_id: Option<String>,
    pub connection: ConnectionState,
    pub demand: VirtualCameraDemand,
    pub media: MediaLifecycle,
}

#[derive(Clone)]
pub struct DiscoveryHandle {
    command_tx: Sender<DiscoveryCommand>,
    event_rx: Arc<Mutex<Receiver<DiscoverySnapshot>>>,
}

impl DiscoveryHandle {
    pub fn attach(&self, sender_id: impl Into<String>) {
        let _ = self.command_tx.send(DiscoveryCommand::Attach(sender_id.into()));
    }

    pub fn set_demand(&self, demand: VirtualCameraDemand) {
        let _ = self.command_tx.send(DiscoveryCommand::Demand(demand));
    }

    pub fn drain(&self) -> Vec<DiscoverySnapshot> {
        let Ok(receiver) = self.event_rx.lock() else {
            return Vec::new();
        };
        receiver.try_iter().collect()
    }
}

pub struct DiscoveryService {
    command_tx: Sender<DiscoveryCommand>,
    shutdown: Arc<AtomicBool>,
    worker_thread: Option<JoinHandle<()>>,
    scanner_thread: Option<JoinHandle<()>>,
}

impl DiscoveryService {
    pub fn start(
        receiver_control_port: u16,
        store_path: PathBuf,
        local_cleanup: Arc<dyn Fn() + Send + Sync>,
    ) -> Result<(Self, DiscoveryHandle)> {
        let pairings = PairingStore::load(store_path)?;
        let (command_tx, command_rx) = mpsc::channel();
        let (event_tx, event_rx) = mpsc::channel();
        let (scan_tx, scan_rx) = mpsc::channel();
        let shutdown = Arc::new(AtomicBool::new(false));

        let scanner_shutdown = shutdown.clone();
        let scanner_thread = thread::Builder::new()
            .name("mobile-webcam-scanner".to_owned())
            .spawn(move || scanner_loop(&scan_tx, &scanner_shutdown))
            .context("start phone scanner thread")?;

        let worker_thread = thread::Builder::new()
            .name("mobile-webcam-discovery".to_owned())
            .spawn(move || {
                let mut worker =
                    DiscoveryWorker::new(receiver_control_port, pairings, local_cleanup, event_tx);
                worker.publish();
                loop {
                    match command_rx.recv_timeout(COMMAND_POLL_INTERVAL) {
                        Ok(DiscoveryCommand::Attach(sender_id)) => worker.select(sender_id),
                        Ok(DiscoveryCommand::Demand(demand)) => worker.set_demand(demand),
                        Ok(DiscoveryCommand::Shutdown)
                        | Err(mpsc::RecvTimeoutError::Disconnected) => {
                            worker.shutdown();
                            break;
                        }
                        Err(mpsc::RecvTimeoutError::Timeout) => {}
                    }
                    worker.receive_scan_results(&scan_rx);
                    worker.expire_stale_phones();
                    worker.maybe_connect();
                }
            })
            .context("start phone discovery thread")?;

        let handle = DiscoveryHandle {
            command_tx: command_tx.clone(),
            event_rx: Arc::new(Mutex::new(event_rx)),
        };
        Ok((
            Self {
                command_tx,
                shutdown,
                worker_thread: Some(worker_thread),
                scanner_thread: Some(scanner_thread),
            },
            handle,
        ))
    }

    fn stop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
        let _ = self.command_tx.send(DiscoveryCommand::Shutdown);
        if let Some(thread) = self.worker_thread.take() {
            let _ = thread.join();
        }
        if let Some(thread) = self.scanner_thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for DiscoveryService {
    fn drop(&mut self) {
        self.stop();
    }
}

enum DiscoveryCommand {
    Attach(String),
    Demand(VirtualCameraDemand),
    Shutdown,
}

#[derive(Debug, Clone)]
struct PhoneEndpoint {
    phone: DiscoveredPhone,
    address: SocketAddr,
    last_seen: Instant,
}

struct ScannedPhone {
    advertisement: SenderAdvertisement,
    address: SocketAddr,
}

#[derive(Debug, Clone)]
struct ActiveStream {
    stream_id: Uuid,
    sender_id: String,
}

struct DiscoveryWorker {
    receiver_control_port: u16,
    pairings: PairingStore,
    phones: HashMap<String, PhoneEndpoint>,
    selected_sender_id: Option<String>,
    connection: ConnectionState,
    demand: VirtualCameraDemand,
    media: MediaLifecycle,
    retry_allowed: bool,
    last_attempt: Option<Instant>,
    retry_attempt: u32,
    retry_delay: Duration,
    active_stream: Option<ActiveStream>,
    local_cleanup: Arc<dyn Fn() + Send + Sync>,
    event_tx: Sender<DiscoverySnapshot>,
}

impl DiscoveryWorker {
    fn new(
        receiver_control_port: u16,
        pairings: PairingStore,
        local_cleanup: Arc<dyn Fn() + Send + Sync>,
        event_tx: Sender<DiscoverySnapshot>,
    ) -> Self {
        Self {
            receiver_control_port,
            pairings,
            phones: HashMap::new(),
            selected_sender_id: None,
            connection: ConnectionState::Searching,
            demand: VirtualCameraDemand::Inactive,
            media: MediaLifecycle::Idle,
            retry_allowed: true,
            last_attempt: None,
            retry_attempt: 0,
            retry_delay: RETRY_MIN_INTERVAL,
            active_stream: None,
            local_cleanup,
            event_tx,
        }
    }

    fn receive_scan_results(&mut self, receiver: &Receiver<Vec<ScannedPhone>>) {
        let mut changed = false;
        for scan in receiver.try_iter() {
            for result in scan {
                let sender_id = result.advertisement.sender_id;
                let display_name = result.advertisement.display_name;
                let availability = result.advertisement.availability;
                let is_new = !self.phones.contains_key(&sender_id);
                self.phones
                    .entry(sender_id.clone())
                    .and_modify(|phone| {
                        phone.phone.display_name.clone_from(&display_name);
                        phone.phone.availability = availability;
                        phone.address = result.address;
                        phone.last_seen = Instant::now();
                    })
                    .or_insert(PhoneEndpoint {
                        phone: DiscoveredPhone {
                            sender_id: sender_id.clone(),
                            display_name,
                            availability,
                        },
                        address: result.address,
                        last_seen: Instant::now(),
                    });
                if is_new {
                    info!(%sender_id, address = %result.address, "discovered mobile sender");
                }
                changed = true;
            }
        }
        if changed {
            self.reconcile_selection();
        }
    }

    fn expire_stale_phones(&mut self) {
        let previous_count = self.phones.len();
        self.phones.retain(|_, phone| phone.last_seen.elapsed() < PHONE_EXPIRY);
        if self.phones.len() == previous_count {
            return;
        }
        if self
            .active_stream
            .as_ref()
            .is_some_and(|stream| !self.phones.contains_key(&stream.sender_id))
        {
            (self.local_cleanup)();
            self.finish_stop();
        }
        if self
            .selected_sender_id
            .as_ref()
            .is_some_and(|selected| !self.phones.contains_key(selected))
        {
            self.selected_sender_id = None;
            self.reset_retry();
        }
        self.reconcile_selection();
    }

    fn reconcile_selection(&mut self) {
        if self.phones.is_empty() {
            if self.active_stream.is_none() {
                self.selected_sender_id = None;
                self.connection = ConnectionState::Searching;
            }
        } else if self.active_stream.is_none() {
            let preferred = self
                .pairings
                .preferred_sender_id
                .as_ref()
                .filter(|sender_id| self.phones.contains_key(*sender_id))
                .cloned();
            let has_preferred = self.pairings.preferred_sender_id.is_some();
            let sender_id = self
                .selected_sender_id
                .as_ref()
                .filter(|sender_id| self.phones.contains_key(*sender_id))
                .cloned()
                .or(preferred)
                .or_else(|| {
                    (!has_preferred && self.phones.len() == 1)
                        .then(|| self.phones.keys().next().cloned())
                        .flatten()
                });
            if self.selected_sender_id != sender_id {
                self.selected_sender_id = sender_id;
                self.reset_retry();
            }
        }
        if self.selected_sender_id.is_none()
            && self.phones.len() > 1
            && self.active_stream.is_none()
        {
            self.connection = ConnectionState::WaitingForSelection;
        } else if !self.demand.is_active() {
            if let Some(sender_id) = self.selected_sender_id.as_ref() {
                if let Some(phone) = self.phones.get(sender_id) {
                    self.connection =
                        ConnectionState::PairedStandby(phone.phone.display_name.clone());
                }
            }
        }
        self.publish();
    }

    fn select(&mut self, sender_id: String) {
        if !self.phones.contains_key(&sender_id) {
            return;
        }
        if self.active_stream.as_ref().is_some_and(|stream| stream.sender_id != sender_id) {
            return;
        }
        self.selected_sender_id = Some(sender_id);
        self.reset_retry();
        self.publish();
    }

    fn set_demand(&mut self, demand: VirtualCameraDemand) {
        if self.demand == demand {
            return;
        }
        let was_active = self.demand.is_active();
        self.demand = demand;
        if !was_active && demand.is_active() {
            self.retry_allowed = true;
            self.reset_retry();
        } else if was_active && !demand.is_active() {
            self.stop_active_stream();
            self.retry_allowed = false;
        }
        self.publish();
    }

    fn reset_retry(&mut self) {
        self.retry_allowed = true;
        self.last_attempt = None;
        self.retry_attempt = 0;
        self.retry_delay = RETRY_MIN_INTERVAL;
    }

    fn maybe_connect(&mut self) {
        if !self.demand.is_active()
            || matches!(self.media, MediaLifecycle::Streaming | MediaLifecycle::Stopping)
        {
            return;
        }
        let Some(sender_id) = self.selected_sender_id.clone() else {
            return;
        };
        if !self.retry_allowed
            || self.last_attempt.is_some_and(|attempt| attempt.elapsed() < self.retry_delay)
        {
            return;
        }
        let Some(endpoint) = self.phones.get(&sender_id).cloned() else {
            return;
        };
        let stream_id =
            self.active_stream.as_ref().map_or_else(Uuid::new_v4, |stream| stream.stream_id);
        self.active_stream = Some(ActiveStream { stream_id, sender_id: sender_id.clone() });
        self.last_attempt = Some(Instant::now());
        self.retry_attempt = self.retry_attempt.saturating_add(1);
        self.retry_delay = retry_delay(self.retry_attempt);
        self.media = MediaLifecycle::Starting;
        self.connection = ConnectionState::Connecting(endpoint.phone.display_name.clone());
        self.publish();

        let request = StartStreamRequest {
            protocol_version: PROTOCOL_VERSION,
            action: SenderControlAction::Start,
            stream_id,
            receiver_id: self.pairings.receiver_id.clone(),
            receiver_name: "Desktop receiver".to_owned(),
            receiver_control_port: self.receiver_control_port,
            pairing_token: self.pairings.tokens.get(&sender_id).cloned(),
        };
        match send_json_request::<_, StartStreamResponse>(
            endpoint.address,
            &request,
            CONTROL_TIMEOUT,
        ) {
            Ok(response) if response.sender_id != sender_id => {
                self.set_error("Discovered phone returned a different sender ID".to_owned());
            }
            Ok(response)
                if response.protocol_version != PROTOCOL_VERSION
                    || response.action != SenderControlAction::StartResult =>
            {
                self.set_error("Phone returned an invalid start response".to_owned());
            }
            Ok(response) if response.stream_id != stream_id => {
                self.set_error(
                    "Phone returned a response for a different stream generation".to_owned(),
                );
            }
            Ok(response) => self.handle_response(&endpoint.phone, response),
            Err(error) => {
                self.media = MediaLifecycle::Failed(error.to_string());
                self.connection = ConnectionState::Error(format!(
                    "Could not contact {}: {error}",
                    endpoint.phone.display_name
                ));
                self.publish();
            }
        }
    }

    fn handle_response(&mut self, phone: &DiscoveredPhone, response: StartStreamResponse) {
        let name = phone.display_name.clone();
        info!(sender_id = %phone.sender_id, status = ?response.status, "phone reverse-control response");
        self.connection = match response.status {
            StartStreamStatus::Accepted => {
                self.retry_allowed = false;
                self.media = MediaLifecycle::Streaming;
                if let Some(token) = response.pairing_token {
                    if let Err(error) = self.pairings.set_token(&phone.sender_id, token) {
                        warn!(%error, "could not persist sender pairing token");
                    }
                }
                if let Err(error) = self.pairings.set_preferred_sender(&phone.sender_id) {
                    warn!(%error, "could not persist preferred sender");
                }
                ConnectionState::Connected(name)
            }
            StartStreamStatus::ApprovalRequired => {
                self.media = MediaLifecycle::Starting;
                ConnectionState::WaitingForApproval(name)
            }
            StartStreamStatus::CameraPermissionRequired => {
                self.media = MediaLifecycle::Failed("camera permission required".to_owned());
                ConnectionState::CameraPermissionRequired(name)
            }
            StartStreamStatus::Busy => {
                self.retry_allowed = true;
                self.retry_delay = BUSY_RETRY_INTERVAL;
                self.media = MediaLifecycle::Failed("phone is busy".to_owned());
                ConnectionState::Error(
                    response.message.unwrap_or_else(|| {
                        format!("{name} is already streaming to another receiver")
                    }),
                )
            }
            StartStreamStatus::Rejected => {
                self.retry_allowed = false;
                self.media = MediaLifecycle::Failed("phone rejected the request".to_owned());
                ConnectionState::Error(
                    response.message.unwrap_or_else(|| format!("{name} rejected the connection")),
                )
            }
            StartStreamStatus::InvalidRequest => {
                self.retry_allowed = false;
                self.media = MediaLifecycle::Failed("invalid sender-control request".to_owned());
                ConnectionState::Error(
                    response
                        .message
                        .unwrap_or_else(|| "Phone rejected the control request".to_owned()),
                )
            }
        };
        self.publish();
    }

    fn set_error(&mut self, message: String) {
        self.media = MediaLifecycle::Failed(message.clone());
        self.connection = ConnectionState::Error(message);
        self.publish();
    }

    fn stop_active_stream(&mut self) {
        let Some(stream) = self.active_stream.clone() else {
            self.media = MediaLifecycle::Idle;
            self.reconcile_selection();
            return;
        };
        let Some(endpoint) = self.phones.get(&stream.sender_id).cloned() else {
            (self.local_cleanup)();
            self.finish_stop();
            return;
        };
        let Some(pairing_token) = self.pairings.tokens.get(&stream.sender_id).cloned() else {
            (self.local_cleanup)();
            self.finish_stop();
            return;
        };
        self.media = MediaLifecycle::Stopping;
        self.connection = ConnectionState::Stopping(endpoint.phone.display_name.clone());
        self.publish();
        let request = StopStreamRequest {
            protocol_version: PROTOCOL_VERSION,
            action: SenderControlAction::Stop,
            stream_id: stream.stream_id,
            receiver_id: self.pairings.receiver_id.clone(),
            pairing_token,
        };
        match send_json_request::<_, StopStreamResponse>(
            endpoint.address,
            &request,
            CONTROL_TIMEOUT,
        ) {
            Ok(response)
                if response.sender_id == stream.sender_id
                    && response.protocol_version == PROTOCOL_VERSION
                    && response.action == SenderControlAction::StopResult
                    && response.stream_id == stream.stream_id
                    && matches!(
                        response.status,
                        StopStreamStatus::Stopped
                            | StopStreamStatus::AlreadyStopped
                            | StopStreamStatus::StaleStream
                    ) =>
            {
                self.finish_stop();
            }
            Ok(response) => {
                warn!(status = ?response.status, "phone did not confirm the active stream stop");
                (self.local_cleanup)();
                self.finish_stop();
            }
            Err(error) => {
                warn!(%error, "phone stop request failed; forcing local receiver cleanup");
                (self.local_cleanup)();
                self.finish_stop();
            }
        }
    }

    fn finish_stop(&mut self) {
        self.active_stream = None;
        self.media = MediaLifecycle::Idle;
        self.retry_allowed = false;
        self.last_attempt = None;
        self.connection = self
            .selected_sender_id
            .as_ref()
            .and_then(|sender_id| self.phones.get(sender_id))
            .map_or(ConnectionState::Searching, |phone| {
                ConnectionState::PairedStandby(phone.phone.display_name.clone())
            });
        self.publish();
    }

    fn shutdown(&mut self) {
        self.stop_active_stream();
    }

    fn publish(&self) {
        let mut phones: Vec<_> = self.phones.values().map(|entry| entry.phone.clone()).collect();
        phones.sort_by(|left, right| left.display_name.cmp(&right.display_name));
        let _ = self.event_tx.send(DiscoverySnapshot {
            phones,
            selected_sender_id: self.selected_sender_id.clone(),
            connection: self.connection.clone(),
            demand: self.demand,
            media: self.media.clone(),
        });
    }
}

fn retry_delay(attempt: u32) -> Duration {
    let shift = attempt.saturating_sub(1).min(RETRY_BACKOFF_SHIFT_LIMIT);
    let multiplier = 1u64.checked_shl(shift).unwrap_or(u64::MAX);
    let seconds = RETRY_BACKOFF_BASE_SECONDS.saturating_mul(multiplier);
    Duration::from_secs(seconds).min(RETRY_MAX_INTERVAL)
}

fn scanner_loop(sender: &Sender<Vec<ScannedPhone>>, shutdown: &AtomicBool) {
    while !shutdown.load(Ordering::Relaxed) {
        let _ = sender.send(scan_local_senders());
        let started = Instant::now();
        while started.elapsed() < SCAN_INTERVAL && !shutdown.load(Ordering::Relaxed) {
            thread::sleep(SCAN_RETRY_SLEEP);
        }
    }
}

fn scan_local_senders() -> Vec<ScannedPhone> {
    let candidates = local_scan_candidates();
    if candidates.is_empty() {
        return Vec::new();
    }
    let found = Mutex::new(Vec::new());
    let chunk_size = candidates.len().div_ceil(SCAN_WORKERS).max(MIN_SCAN_CHUNK_SIZE);
    thread::scope(|scope| {
        for chunk in candidates.chunks(chunk_size) {
            let found = &found;
            scope.spawn(move || {
                for address in chunk {
                    if let Some(phone) = probe_sender(*address) {
                        if let Ok(mut found) = found.lock() {
                            found.push(phone);
                        }
                    }
                }
            });
        }
    });
    found.into_inner().unwrap_or_default()
}

fn local_scan_candidates() -> Vec<SocketAddr> {
    let Ok(interfaces) = if_addrs::get_if_addrs() else {
        return Vec::new();
    };
    let mut candidates = HashSet::new();
    for interface in interfaces {
        if !interface.is_oper_up() || interface.is_loopback() || interface.is_p2p() {
            continue;
        }
        let IfAddr::V4(address) = interface.addr else {
            continue;
        };
        let host_count =
            1u32.checked_shl(IPV4_ADDRESS_BITS - u32::from(address.prefixlen)).unwrap_or(u32::MAX);
        if host_count <= RESERVED_SUBNET_HOSTS || host_count > MAX_SCAN_HOSTS {
            continue;
        }
        let local = u32::from(address.ip);
        let mask = u32::from(address.netmask);
        let network = local & mask;
        let broadcast = network | !mask;
        for host in (network + FIRST_HOST_OFFSET)..broadcast {
            if host != local {
                candidates
                    .insert(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(host)), SENDER_CONTROL_PORT));
            }
        }
    }
    candidates.into_iter().collect()
}

fn probe_sender(address: SocketAddr) -> Option<ScannedPhone> {
    let request = DescribeSenderRequest {
        protocol_version: PROTOCOL_VERSION,
        action: SenderControlAction::Describe,
    };
    let advertisement =
        send_json_request::<_, SenderAdvertisement>(address, &request, PROBE_TIMEOUT).ok()?;
    if !advertisement.is_valid() || advertisement.control_port != SENDER_CONTROL_PORT {
        return None;
    }
    Some(ScannedPhone { advertisement, address })
}

fn send_json_request<Request, Response>(
    address: SocketAddr,
    request: &Request,
    timeout: Duration,
) -> Result<Response>
where
    Request: Serialize,
    Response: for<'de> Deserialize<'de>,
{
    let mut stream = TcpStream::connect_timeout(&address, timeout)
        .with_context(|| format!("connect to {address}"))?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    let mut encoded = serde_json::to_vec(request)?;
    if encoded.len() as u64 >= MAX_MESSAGE_BYTES {
        anyhow::bail!("sender-control request exceeds {MAX_MESSAGE_BYTES} bytes");
    }
    encoded.push(b'\n');
    stream.write_all(&encoded)?;
    stream.flush()?;

    let mut response = String::new();
    BufReader::new(stream).take(MAX_MESSAGE_BYTES).read_line(&mut response)?;
    if response.trim().is_empty() {
        anyhow::bail!("phone closed the connection without a response");
    }
    Ok(serde_json::from_str(&response)?)
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredPairings {
    receiver_id: String,
    #[serde(default)]
    tokens: HashMap<String, String>,
    #[serde(default)]
    preferred_sender_id: Option<String>,
}

struct PairingStore {
    path: PathBuf,
    receiver_id: String,
    tokens: HashMap<String, String>,
    preferred_sender_id: Option<String>,
}

impl PairingStore {
    fn load(path: PathBuf) -> Result<Self> {
        if path.exists() {
            let stored: StoredPairings = serde_json::from_slice(
                &fs::read(&path).with_context(|| format!("read {}", path.display()))?,
            )
            .with_context(|| format!("parse {}", path.display()))?;
            return Ok(Self {
                path,
                receiver_id: stored.receiver_id,
                tokens: stored.tokens,
                preferred_sender_id: stored.preferred_sender_id,
            });
        }
        let store = Self {
            path,
            receiver_id: Uuid::new_v4().to_string(),
            tokens: HashMap::new(),
            preferred_sender_id: None,
        };
        store.persist()?;
        Ok(store)
    }

    fn set_token(&mut self, sender_id: &str, token: String) -> Result<()> {
        self.tokens.insert(sender_id.to_owned(), token);
        self.persist()
    }

    fn set_preferred_sender(&mut self, sender_id: &str) -> Result<()> {
        if self.preferred_sender_id.as_deref() == Some(sender_id) {
            return Ok(());
        }
        self.preferred_sender_id = Some(sender_id.to_owned());
        self.persist()
    }

    fn persist(&self) -> Result<()> {
        let parent = self.path.parent().context("pairing store path has no parent")?;
        fs::create_dir_all(parent)?;
        let temp = temporary_path(&self.path);
        let stored = StoredPairings {
            receiver_id: self.receiver_id.clone(),
            tokens: self.tokens.clone(),
            preferred_sender_id: self.preferred_sender_id.clone(),
        };
        let mut file = fs::OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .mode(0o600)
            .open(&temp)?;
        serde_json::to_writer_pretty(&mut file, &stored)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(temp, &self.path)?;
        Ok(())
    }
}

fn temporary_path(path: &Path) -> PathBuf {
    path.with_extension(format!("tmp-{}", Uuid::new_v4()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pairing_store_persists_receiver_identity_and_sender_token() {
        let directory = std::env::temp_dir().join(format!("mobile-webcam-{}", Uuid::new_v4()));
        let path = directory.join("pairings.json");
        let mut first = PairingStore::load(path.clone()).unwrap();
        let receiver_id = first.receiver_id.clone();
        first.set_token("phone-1", "secret".to_owned()).unwrap();

        let loaded = PairingStore::load(path).unwrap();
        assert_eq!(loaded.receiver_id, receiver_id);
        assert_eq!(loaded.tokens.get("phone-1").map(String::as_str), Some("secret"));

        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn local_scan_candidates_only_include_other_hosts_on_bounded_subnets() {
        let candidates = local_scan_candidates();
        assert!(candidates.iter().all(|address| address.port() == SENDER_CONTROL_PORT));
        assert!(candidates.iter().all(|address| !address.ip().is_loopback()));
    }

    #[test]
    fn a_reachable_phone_does_not_start_without_virtual_camera_demand() {
        let directory =
            std::env::temp_dir().join(format!("mobile-webcam-standby-{}", Uuid::new_v4()));
        let path = directory.join("pairings.json");
        let pairings = PairingStore::load(path.clone()).unwrap();
        let (event_tx, _event_rx) = mpsc::channel();
        let mut worker = DiscoveryWorker::new(5_001, pairings, Arc::new(|| {}), event_tx);
        worker.selected_sender_id = Some("phone-1".to_owned());
        worker.phones.insert(
            "phone-1".to_owned(),
            PhoneEndpoint {
                phone: DiscoveredPhone {
                    sender_id: "phone-1".to_owned(),
                    display_name: "Phone".to_owned(),
                    availability: SenderAvailability::Standby,
                },
                address: SocketAddr::from(([127, 0, 0, 1], SENDER_CONTROL_PORT)),
                last_seen: Instant::now(),
            },
        );
        worker.maybe_connect();
        assert!(worker.active_stream.is_none());
        assert_eq!(worker.media, MediaLifecycle::Idle);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn preferred_sender_is_selected_without_replacing_an_existing_selection() {
        let directory =
            std::env::temp_dir().join(format!("mobile-webcam-preferred-{}", Uuid::new_v4()));
        let path = directory.join("pairings.json");
        let mut pairings = PairingStore::load(path.clone()).unwrap();
        pairings.set_preferred_sender("phone-2").unwrap();
        let (scan_tx, scan_rx) = mpsc::channel();
        let (event_tx, _event_rx) = mpsc::channel();
        let mut worker = DiscoveryWorker::new(5_001, pairings, Arc::new(|| {}), event_tx);
        scan_tx
            .send(vec![
                ScannedPhone {
                    advertisement: SenderAdvertisement {
                        protocol_version: PROTOCOL_VERSION,
                        action: SenderControlAction::DescribeResult,
                        sender_id: "phone-1".to_owned(),
                        display_name: "Phone 1".to_owned(),
                        control_port: SENDER_CONTROL_PORT,
                        availability: SenderAvailability::Standby,
                    },
                    address: SocketAddr::from(([127, 0, 0, 1], SENDER_CONTROL_PORT)),
                },
                ScannedPhone {
                    advertisement: SenderAdvertisement {
                        protocol_version: PROTOCOL_VERSION,
                        action: SenderControlAction::DescribeResult,
                        sender_id: "phone-2".to_owned(),
                        display_name: "Phone 2".to_owned(),
                        control_port: SENDER_CONTROL_PORT,
                        availability: SenderAvailability::Standby,
                    },
                    address: SocketAddr::from(([127, 0, 0, 2], SENDER_CONTROL_PORT)),
                },
            ])
            .unwrap();
        worker.receive_scan_results(&scan_rx);
        assert_eq!(worker.selected_sender_id.as_deref(), Some("phone-2"));
        worker.select("phone-1".to_owned());
        assert_eq!(worker.selected_sender_id.as_deref(), Some("phone-1"));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn retry_delay_is_bounded_exponential_and_busy_is_slow() {
        assert_eq!(retry_delay(1), RETRY_MIN_INTERVAL);
        assert_eq!(retry_delay(2), Duration::from_secs(4));
        assert_eq!(retry_delay(100), RETRY_MAX_INTERVAL);
        assert_eq!(BUSY_RETRY_INTERVAL, Duration::from_secs(15));
    }
}
