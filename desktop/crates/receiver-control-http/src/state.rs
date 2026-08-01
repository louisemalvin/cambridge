use std::sync::{Arc, Mutex};

use receiver_core::ReceiverService;

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
}
