use std::sync::{Arc, Mutex};

use receiver_core::{ReceiverError, ReceiverService};

#[derive(Clone)]
pub struct ControlState {
    service: Arc<Mutex<ReceiverService>>,
}

impl ControlState {
    #[must_use]
    pub fn new(service: ReceiverService) -> Self {
        Self { service: Arc::new(Mutex::new(service)) }
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

    pub fn refresh(&self) {
        if let Ok(mut service) = self.service.lock() {
            let _ = service.state();
        }
    }
}
