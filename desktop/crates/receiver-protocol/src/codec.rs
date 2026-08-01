use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum VideoCodec {
    H264,
    H265,
}

impl VideoCodec {
    pub const ALL: [Self; 2] = [Self::H264, Self::H265];

    #[must_use]
    pub const fn protocol_id(self) -> &'static str {
        match self {
            Self::H264 => "h264",
            Self::H265 => "h265",
        }
    }
}

impl fmt::Display for VideoCodec {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.protocol_id())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DecoderAcceleration {
    Hardware,
    Software,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PixelFormat {
    #[serde(rename = "yuy2")]
    Yuy2,
    #[serde(rename = "nv12")]
    Nv12,
    #[serde(rename = "i420")]
    I420,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Transport {
    #[serde(rename = "mpegts-udp")]
    MpegTsUdp,
}

impl Transport {
    #[must_use]
    pub const fn protocol_id(self) -> &'static str {
        "mpegts-udp"
    }
}
