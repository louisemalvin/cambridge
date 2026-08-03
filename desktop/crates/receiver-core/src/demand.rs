#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VirtualCameraDemand {
    Inactive,
    Active { consumer_count: u32 },
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
}
