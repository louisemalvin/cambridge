use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DemandDrivenStreamState {
    Standby,
    WaitingForSender,
    WaitingForApproval(Uuid),
    Starting(Uuid),
    Streaming(Uuid),
    Stopping(Uuid),
    Failed { stream_id: Option<Uuid>, reason: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DemandDrivenStreamEvent {
    DemandActivated,
    DemandDeactivated,
    PreferredSenderReachable,
    PreferredSenderLost,
    StartAccepted(Uuid),
    StartApprovalRequired(Uuid),
    StartRejected(Uuid),
    StartFailed(Uuid, String),
    StopAccepted(Uuid),
    StopFailed(Uuid),
    RetryTimerFired,
    ShutdownRequested,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DemandDrivenStreamEffect {
    SendStart(Uuid),
    SendStop(Uuid),
    CancelRetry,
    ScheduleRetry,
    ReturnToStandby,
    ForceLocalCleanup,
}

#[allow(clippy::struct_excessive_bools)]
pub struct DemandDrivenStreamCoordinator {
    state: DemandDrivenStreamState,
    demand_active: bool,
    sender_reachable: bool,
    start_in_flight: bool,
    stop_in_flight: bool,
    shutting_down: bool,
}

impl Default for DemandDrivenStreamCoordinator {
    fn default() -> Self {
        Self::new()
    }
}

impl DemandDrivenStreamCoordinator {
    #[must_use]
    pub const fn new() -> Self {
        Self {
            state: DemandDrivenStreamState::Standby,
            demand_active: false,
            sender_reachable: false,
            start_in_flight: false,
            stop_in_flight: false,
            shutting_down: false,
        }
    }

    #[must_use]
    pub fn state(&self) -> &DemandDrivenStreamState {
        &self.state
    }

    #[must_use]
    pub fn stream_id(&self) -> Option<Uuid> {
        match self.state {
            DemandDrivenStreamState::WaitingForApproval(stream_id)
            | DemandDrivenStreamState::Starting(stream_id)
            | DemandDrivenStreamState::Streaming(stream_id)
            | DemandDrivenStreamState::Stopping(stream_id) => Some(stream_id),
            DemandDrivenStreamState::Failed { stream_id, .. } => stream_id,
            DemandDrivenStreamState::Standby | DemandDrivenStreamState::WaitingForSender => None,
        }
    }

    #[must_use]
    pub fn reduce(&mut self, event: DemandDrivenStreamEvent) -> Vec<DemandDrivenStreamEffect> {
        match event {
            DemandDrivenStreamEvent::DemandActivated => self.activate_demand(),
            DemandDrivenStreamEvent::DemandDeactivated => self.deactivate_demand(),
            DemandDrivenStreamEvent::PreferredSenderReachable => {
                self.sender_reachable = true;
                if self.demand_active
                    && matches!(self.state, DemandDrivenStreamState::WaitingForSender)
                {
                    self.begin_start()
                } else {
                    Vec::new()
                }
            }
            DemandDrivenStreamEvent::PreferredSenderLost => {
                self.sender_reachable = false;
                if self.demand_active
                    && matches!(
                        self.state,
                        DemandDrivenStreamState::Starting(_)
                            | DemandDrivenStreamState::WaitingForApproval(_)
                    )
                {
                    self.start_in_flight = false;
                    self.state = DemandDrivenStreamState::WaitingForSender;
                    vec![DemandDrivenStreamEffect::CancelRetry]
                } else {
                    Vec::new()
                }
            }
            DemandDrivenStreamEvent::StartAccepted(stream_id) => {
                if self.is_current_start(stream_id) {
                    self.start_in_flight = false;
                    self.state = DemandDrivenStreamState::Streaming(stream_id);
                    vec![DemandDrivenStreamEffect::CancelRetry]
                } else {
                    Vec::new()
                }
            }
            DemandDrivenStreamEvent::StartApprovalRequired(stream_id) => {
                if self.is_current_start(stream_id) {
                    self.start_in_flight = false;
                    self.state = DemandDrivenStreamState::WaitingForApproval(stream_id);
                    vec![DemandDrivenStreamEffect::ScheduleRetry]
                } else {
                    Vec::new()
                }
            }
            DemandDrivenStreamEvent::StartRejected(stream_id) => {
                if self.is_current_start(stream_id) {
                    self.start_in_flight = false;
                    self.state = DemandDrivenStreamState::Failed {
                        stream_id: Some(stream_id),
                        reason: "sender rejected the start request".to_owned(),
                    };
                }
                Vec::new()
            }
            DemandDrivenStreamEvent::StartFailed(stream_id, reason) => {
                if self.is_current_start(stream_id) {
                    self.start_in_flight = false;
                    self.state =
                        DemandDrivenStreamState::Failed { stream_id: Some(stream_id), reason };
                    vec![DemandDrivenStreamEffect::ScheduleRetry]
                } else {
                    Vec::new()
                }
            }
            DemandDrivenStreamEvent::StopAccepted(stream_id) => self.finish_stop(stream_id, false),
            DemandDrivenStreamEvent::StopFailed(stream_id) => self.finish_stop(stream_id, true),
            DemandDrivenStreamEvent::RetryTimerFired => {
                if self.demand_active
                    && self.sender_reachable
                    && !self.start_in_flight
                    && !self.stop_in_flight
                    && matches!(
                        self.state,
                        DemandDrivenStreamState::WaitingForSender
                            | DemandDrivenStreamState::WaitingForApproval(_)
                            | DemandDrivenStreamState::Failed { .. }
                    )
                {
                    self.begin_start()
                } else {
                    Vec::new()
                }
            }
            DemandDrivenStreamEvent::ShutdownRequested => {
                self.shutting_down = true;
                self.demand_active = false;
                self.deactivate_demand()
            }
        }
    }

    fn activate_demand(&mut self) -> Vec<DemandDrivenStreamEffect> {
        if self.shutting_down || self.demand_active {
            return Vec::new();
        }
        self.demand_active = true;
        if self.stop_in_flight {
            return Vec::new();
        }
        if self.sender_reachable {
            self.begin_start()
        } else {
            self.state = DemandDrivenStreamState::WaitingForSender;
            Vec::new()
        }
    }

    fn deactivate_demand(&mut self) -> Vec<DemandDrivenStreamEffect> {
        self.demand_active = false;
        let Some(stream_id) = self.stream_id() else {
            self.state = DemandDrivenStreamState::Standby;
            return vec![DemandDrivenStreamEffect::CancelRetry];
        };
        if self.stop_in_flight {
            return vec![DemandDrivenStreamEffect::CancelRetry];
        }
        self.start_in_flight = false;
        self.stop_in_flight = true;
        self.state = DemandDrivenStreamState::Stopping(stream_id);
        vec![
            DemandDrivenStreamEffect::CancelRetry,
            DemandDrivenStreamEffect::SendStop(stream_id),
            DemandDrivenStreamEffect::ReturnToStandby,
        ]
    }

    fn begin_start(&mut self) -> Vec<DemandDrivenStreamEffect> {
        if !self.demand_active || self.start_in_flight || self.stop_in_flight || self.shutting_down
        {
            return Vec::new();
        }
        let stream_id = self.stream_id().unwrap_or_else(Uuid::new_v4);
        self.start_in_flight = true;
        self.state = DemandDrivenStreamState::Starting(stream_id);
        vec![DemandDrivenStreamEffect::SendStart(stream_id)]
    }

    fn is_current_start(&self, stream_id: Uuid) -> bool {
        self.start_in_flight && self.stream_id() == Some(stream_id)
    }

    fn finish_stop(&mut self, stream_id: Uuid, failed: bool) -> Vec<DemandDrivenStreamEffect> {
        if !self.stop_in_flight || self.stream_id() != Some(stream_id) {
            return Vec::new();
        }
        self.stop_in_flight = false;
        self.state = DemandDrivenStreamState::Standby;
        let mut effects = vec![DemandDrivenStreamEffect::ReturnToStandby];
        if failed {
            effects.push(DemandDrivenStreamEffect::ForceLocalCleanup);
        }
        if self.demand_active && !self.shutting_down {
            if self.sender_reachable {
                effects.extend(self.begin_start());
            } else {
                self.state = DemandDrivenStreamState::WaitingForSender;
            }
        }
        effects
    }
}

#[cfg(test)]
mod tests {
    #![allow(unused_must_use)]

    use super::*;

    #[test]
    fn discovery_alone_does_not_start_a_stream() {
        let mut coordinator = DemandDrivenStreamCoordinator::new();
        assert!(coordinator.reduce(DemandDrivenStreamEvent::PreferredSenderReachable).is_empty());
        assert_eq!(coordinator.state(), &DemandDrivenStreamState::Standby);
    }

    #[test]
    fn first_demand_starts_once_and_duplicate_activation_is_ignored() {
        let mut coordinator = DemandDrivenStreamCoordinator::new();
        coordinator.reduce(DemandDrivenStreamEvent::PreferredSenderReachable);
        let effects = coordinator.reduce(DemandDrivenStreamEvent::DemandActivated);
        assert_eq!(effects.len(), 1);
        let stream_id = coordinator.stream_id().unwrap();
        assert!(matches!(effects[0], DemandDrivenStreamEffect::SendStart(_)));
        assert!(coordinator.reduce(DemandDrivenStreamEvent::DemandActivated).is_empty());
        assert!(!coordinator
            .reduce(DemandDrivenStreamEvent::StartAccepted(stream_id))
            .contains(&DemandDrivenStreamEffect::SendStart(stream_id)));
        assert_eq!(coordinator.state(), &DemandDrivenStreamState::Streaming(stream_id));
    }

    #[test]
    fn final_release_sends_one_stop_and_returns_to_standby() {
        let mut coordinator = DemandDrivenStreamCoordinator::new();
        coordinator.reduce(DemandDrivenStreamEvent::PreferredSenderReachable);
        coordinator.reduce(DemandDrivenStreamEvent::DemandActivated);
        let stream_id = coordinator.stream_id().unwrap();
        coordinator.reduce(DemandDrivenStreamEvent::StartAccepted(stream_id));
        let effects = coordinator.reduce(DemandDrivenStreamEvent::DemandDeactivated);
        assert!(effects.contains(&DemandDrivenStreamEffect::SendStop(stream_id)));
        assert!(!coordinator
            .reduce(DemandDrivenStreamEvent::DemandDeactivated)
            .contains(&DemandDrivenStreamEffect::SendStop(stream_id)));
        coordinator.reduce(DemandDrivenStreamEvent::StopAccepted(stream_id));
        assert_eq!(coordinator.state(), &DemandDrivenStreamState::Standby);
    }

    #[test]
    fn stale_responses_and_stops_do_not_mutate_a_new_generation() {
        let mut coordinator = DemandDrivenStreamCoordinator::new();
        coordinator.reduce(DemandDrivenStreamEvent::PreferredSenderReachable);
        coordinator.reduce(DemandDrivenStreamEvent::DemandActivated);
        let first = coordinator.stream_id().unwrap();
        coordinator.reduce(DemandDrivenStreamEvent::StartAccepted(first));
        coordinator.reduce(DemandDrivenStreamEvent::DemandDeactivated);
        coordinator.reduce(DemandDrivenStreamEvent::StopAccepted(first));
        coordinator.reduce(DemandDrivenStreamEvent::DemandActivated);
        let second = coordinator.stream_id().unwrap();
        assert_ne!(first, second);
        assert!(coordinator.reduce(DemandDrivenStreamEvent::StopAccepted(first)).is_empty());
        assert_eq!(coordinator.state(), &DemandDrivenStreamState::Starting(second));
        assert!(coordinator.reduce(DemandDrivenStreamEvent::StartAccepted(first)).is_empty());
        assert_eq!(coordinator.state(), &DemandDrivenStreamState::Starting(second));
    }

    #[test]
    fn demand_ending_during_approval_stops_the_same_generation() {
        let mut coordinator = DemandDrivenStreamCoordinator::new();
        coordinator.reduce(DemandDrivenStreamEvent::PreferredSenderReachable);
        coordinator.reduce(DemandDrivenStreamEvent::DemandActivated);
        let stream_id = coordinator.stream_id().unwrap();
        coordinator.reduce(DemandDrivenStreamEvent::StartApprovalRequired(stream_id));
        let effects = coordinator.reduce(DemandDrivenStreamEvent::DemandDeactivated);
        assert!(effects.contains(&DemandDrivenStreamEffect::SendStop(stream_id)));
        assert_eq!(coordinator.state(), &DemandDrivenStreamState::Stopping(stream_id));
    }

    #[test]
    fn failed_stop_forces_local_cleanup() {
        let mut coordinator = DemandDrivenStreamCoordinator::new();
        coordinator.reduce(DemandDrivenStreamEvent::PreferredSenderReachable);
        coordinator.reduce(DemandDrivenStreamEvent::DemandActivated);
        let stream_id = coordinator.stream_id().unwrap();
        coordinator.reduce(DemandDrivenStreamEvent::DemandDeactivated);
        let effects = coordinator.reduce(DemandDrivenStreamEvent::StopFailed(stream_id));
        assert!(effects.contains(&DemandDrivenStreamEffect::ForceLocalCleanup));
        assert_eq!(coordinator.state(), &DemandDrivenStreamState::Standby);
    }
}
