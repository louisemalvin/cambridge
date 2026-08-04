use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoProfile {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
}

impl VideoProfile {
    #[must_use]
    pub const fn is_valid(&self) -> bool {
        self.width > 0 && self.height > 0 && self.fps > 0
    }
}
