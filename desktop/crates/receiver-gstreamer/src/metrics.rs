use std::time::Instant;

#[derive(Debug, Default)]
pub struct Metrics {
    started_at: Option<Instant>,
    first_frame_at: Option<Instant>,
    received_bytes: u64,
    timeout_count: u64,
    decoder: Option<String>,
}

impl Metrics {
    pub fn reset(&mut self) {
        *self = Self::default();
        self.started_at = Some(Instant::now());
    }

    pub fn record_network_bytes(&mut self, bytes: usize) {
        self.received_bytes = self.received_bytes.saturating_add(bytes as u64);
    }

    pub fn record_first_frame(&mut self, decoder: Option<String>) {
        if self.first_frame_at.is_none() {
            self.first_frame_at = Some(Instant::now());
            self.decoder = decoder;
        }
    }

    pub fn set_decoder(&mut self, decoder: String) {
        self.decoder = Some(decoder);
    }

    pub fn has_first_frame(&self) -> bool {
        self.first_frame_at.is_some()
    }

    pub fn record_timeout(&mut self) {
        self.timeout_count = self.timeout_count.saturating_add(1);
    }

    pub fn received_bitrate_bps(&self) -> u32 {
        let Some(started_at) = self.started_at else {
            return 0;
        };
        let elapsed_nanos = started_at.elapsed().as_nanos().max(1);
        let bits_per_second = u128::from(self.received_bytes)
            .saturating_mul(8)
            .saturating_mul(1_000_000_000)
            .checked_div(elapsed_nanos)
            .unwrap_or(0)
            .min(u128::from(u32::MAX));
        u32::try_from(bits_per_second).unwrap_or(u32::MAX)
    }

    pub const fn timeout_count(&self) -> u64 {
        self.timeout_count
    }

    pub fn decoder(&self) -> Option<String> {
        self.decoder.clone()
    }
}
