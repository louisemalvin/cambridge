use receiver_protocol::{DemandEventV2, DemandStateV2, V2_PROTOCOL_VERSION};

const NEXT_DEMAND_GENERATION: u64 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VirtualCameraDemand {
    Inactive,
    Active { consumer_count: u32 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DemandCoordinator {
    demand: VirtualCameraDemand,
    generation: u64,
}

impl Default for DemandCoordinator {
    fn default() -> Self {
        Self::new()
    }
}

impl DemandCoordinator {
    #[must_use]
    pub const fn new() -> Self {
        Self {
            demand: VirtualCameraDemand::Inactive,
            generation: receiver_protocol::INITIAL_DEMAND_GENERATION,
        }
    }

    #[must_use]
    pub const fn demand(&self) -> VirtualCameraDemand {
        self.demand
    }

    #[must_use]
    pub const fn generation(&self) -> u64 {
        self.generation
    }

    #[must_use]
    pub fn snapshot(&self) -> DemandEventV2 {
        event(self.generation, self.demand)
    }

    pub fn observe(&mut self, demand: VirtualCameraDemand) -> Option<DemandEventV2> {
        let was_active = self.demand.is_active();
        let is_active = demand.is_active();
        self.demand = demand;
        if was_active == is_active {
            return None;
        }
        if is_active {
            self.generation = self.generation.saturating_add(NEXT_DEMAND_GENERATION);
        }
        Some(event(self.generation, demand))
    }
}

fn event(generation: u64, demand: VirtualCameraDemand) -> DemandEventV2 {
    DemandEventV2 {
        protocol_version: V2_PROTOCOL_VERSION,
        generation,
        demand: if demand.is_active() { DemandStateV2::Active } else { DemandStateV2::Inactive },
        consumer_count: demand.consumer_count(),
    }
}

impl VirtualCameraDemand {
    #[must_use]
    pub const fn consumer_count(self) -> u32 {
        match self {
            Self::Inactive => 0,
            Self::Active { consumer_count } => consumer_count,
        }
    }

    #[must_use]
    pub const fn is_active(self) -> bool {
        !matches!(self, Self::Inactive)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn demand_state_exposes_the_effective_consumer_count() {
        assert_eq!(VirtualCameraDemand::Inactive.consumer_count(), 0);
        assert_eq!(VirtualCameraDemand::Active { consumer_count: 2 }.consumer_count(), 2);
        assert!(VirtualCameraDemand::Active { consumer_count: 1 }.is_active());
        assert!(!VirtualCameraDemand::Inactive.is_active());
    }

    #[test]
    fn connected_standby_has_no_generation_and_does_not_emit_media_demand() {
        let coordinator = DemandCoordinator::new();

        assert_eq!(coordinator.demand(), VirtualCameraDemand::Inactive);
        assert_eq!(coordinator.generation(), receiver_protocol::INITIAL_DEMAND_GENERATION);
        assert_eq!(coordinator.snapshot().demand, DemandStateV2::Inactive);
        assert!(coordinator.snapshot().validate().is_ok());
    }

    #[test]
    fn first_demand_creates_one_generation_and_duplicate_demand_is_ignored() {
        let mut coordinator = DemandCoordinator::new();

        let event = coordinator.observe(VirtualCameraDemand::Active { consumer_count: 1 }).unwrap();
        assert_eq!(event.generation, 1);
        assert_eq!(event.demand, DemandStateV2::Active);
        assert_eq!(event.consumer_count, 1);
        assert!(coordinator.observe(VirtualCameraDemand::Active { consumer_count: 2 }).is_none());
    }

    #[test]
    fn final_release_keeps_generation_for_idempotent_stop() {
        let mut coordinator = DemandCoordinator::new();
        let active =
            coordinator.observe(VirtualCameraDemand::Active { consumer_count: 1 }).unwrap();

        let inactive = coordinator.observe(VirtualCameraDemand::Inactive).unwrap();

        assert_eq!(inactive.generation, active.generation);
        assert_eq!(inactive.demand, DemandStateV2::Inactive);
        assert!(coordinator.observe(VirtualCameraDemand::Inactive).is_none());
    }

    #[test]
    fn reopening_creates_a_fresh_generation() {
        let mut coordinator = DemandCoordinator::new();
        let first = coordinator.observe(VirtualCameraDemand::Active { consumer_count: 1 }).unwrap();
        coordinator.observe(VirtualCameraDemand::Inactive);
        let second =
            coordinator.observe(VirtualCameraDemand::Active { consumer_count: 1 }).unwrap();

        assert!(second.generation > first.generation);
    }
}
