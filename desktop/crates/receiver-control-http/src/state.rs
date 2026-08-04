use std::sync::{Arc, Mutex};

use receiver_core::{DemandCoordinator, ReceiverError, ReceiverService, VirtualCameraDemand};
use receiver_protocol::DemandEventV2;
use tokio::sync::broadcast;

const DEMAND_EVENT_CHANNEL_CAPACITY: usize = 16;

struct DemandHub {
    state: Mutex<DemandCoordinator>,
    events: broadcast::Sender<DemandEventV2>,
}

impl DemandHub {
    fn new() -> Self {
        let (events, _) = broadcast::channel(DEMAND_EVENT_CHANNEL_CAPACITY);
        Self { state: Mutex::new(DemandCoordinator::new()), events }
    }

    fn publish(&self, demand: VirtualCameraDemand) {
        let Ok(mut state) = self.state.lock() else {
            tracing::error!("demand coordinator lock poisoned");
            return;
        };
        if let Some(event) = state.observe(demand) {
            let _ = self.events.send(event);
        }
    }

    fn subscribe(&self) -> (DemandEventV2, broadcast::Receiver<DemandEventV2>) {
        let receiver = self.events.subscribe();
        let snapshot = self
            .state
            .lock()
            .map(|state| state.snapshot())
            .unwrap_or_else(|_| DemandCoordinator::new().snapshot());
        (snapshot, receiver)
    }
}

#[derive(Clone)]
pub struct ControlState {
    service: Arc<Mutex<ReceiverService>>,
    demand: Arc<DemandHub>,
}

impl ControlState {
    #[must_use]
    pub fn new(service: ReceiverService) -> Self {
        Self { service: Arc::new(Mutex::new(service)), demand: Arc::new(DemandHub::new()) }
    }

    pub(crate) fn service(&self) -> &Arc<Mutex<ReceiverService>> {
        &self.service
    }

    pub fn shutdown(&self) -> Result<(), ReceiverError> {
        let mut service = self
            .service
            .lock()
            .map_err(|_| ReceiverError::MediaStop("receiver service lock poisoned".to_owned()))?;
        service.shutdown()
    }

    pub fn stop_active_session(&self) -> Result<(), ReceiverError> {
        let mut service = self
            .service
            .lock()
            .map_err(|_| ReceiverError::MediaStop("receiver service lock poisoned".to_owned()))?;
        service.stop_active_session()
    }

    pub fn refresh(&self) {
        if let Ok(mut service) = self.service.lock() {
            let _ = service.state();
        }
    }

    pub fn publish_demand(&self, demand: VirtualCameraDemand) {
        self.demand.publish(demand);
    }

    pub(crate) fn subscribe_demand(&self) -> (DemandEventV2, broadcast::Receiver<DemandEventV2>) {
        self.demand.subscribe()
    }

    pub(crate) fn v2_authorized(&self, bearer_token: Option<&str>) -> bool {
        let Ok(service) = self.service.lock() else {
            return false;
        };
        service
            .config()
            .control_token
            .as_deref()
            .map_or(true, |expected| bearer_token == Some(expected))
    }
}
