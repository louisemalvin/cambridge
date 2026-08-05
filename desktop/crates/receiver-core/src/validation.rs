use receiver_protocol::{PixelFormat, VideoProfile};

use crate::{OutputFormat, ReceiverCapabilities, ReceiverConfig, ReceiverError};

const YUY2_PREFERRED_MAX_WIDTH: u32 = 1_920;
const INVALID_PORT: u16 = 0;

pub fn validate_config(config: &ReceiverConfig) -> Result<(), ReceiverError> {
    if config.control_port == INVALID_PORT {
        return Err(ReceiverError::InvalidConfiguration(
            "control port must be non-zero".to_owned(),
        ));
    }
    if config.latency.output_queue_frames == 0 {
        return Err(ReceiverError::InvalidConfiguration(
            "output queue must contain at least one frame".to_owned(),
        ));
    }
    if config.srt.listen_port == INVALID_PORT {
        return Err(ReceiverError::InvalidConfiguration(
            "SRT listener port must be non-zero".to_owned(),
        ));
    }
    if config.control_port == config.srt.listen_port {
        return Err(ReceiverError::InvalidConfiguration(
            "control and SRT listener ports must be different".to_owned(),
        ));
    }
    if config.srt.latency_ms == 0 {
        return Err(ReceiverError::InvalidConfiguration("SRT latency must be non-zero".to_owned()));
    }
    if config.srt.inactivity_timeout_ms == 0 {
        return Err(ReceiverError::InvalidConfiguration(
            "SRT inactivity timeout must be non-zero".to_owned(),
        ));
    }
    if config.srt.reconnect_grace_ms < config.srt.connect_deadline_ms {
        return Err(ReceiverError::InvalidConfiguration(
            "SRT reconnect grace must be at least the connect deadline".to_owned(),
        ));
    }
    if !config.output_profile.is_valid() {
        return Err(ReceiverError::InvalidConfiguration(
            "output profile dimensions and FPS must be positive".to_owned(),
        ));
    }
    if config.h264_bitrate_bps == 0 || config.h265_bitrate_bps == 0 {
        return Err(ReceiverError::InvalidConfiguration(
            "receiver codec bitrates must be non-zero".to_owned(),
        ));
    }
    if config.advertised_host.as_deref().is_some_and(|host| host.trim().is_empty()) {
        return Err(ReceiverError::InvalidConfiguration(
            "advertised host override must be non-empty".to_owned(),
        ));
    }
    Ok(())
}

pub fn select_output_format(
    requested: OutputFormat,
    profile: &VideoProfile,
    capabilities: &ReceiverCapabilities,
) -> Result<PixelFormat, ReceiverError> {
    let supported = &capabilities.output.pixel_formats;
    let preferred = match requested {
        OutputFormat::Auto => {
            if profile.width <= YUY2_PREFERRED_MAX_WIDTH {
                [PixelFormat::Yuy2, PixelFormat::Nv12, PixelFormat::I420]
            } else {
                [PixelFormat::Nv12, PixelFormat::Yuy2, PixelFormat::I420]
            }
        }
        OutputFormat::Yuy2 => [PixelFormat::Yuy2, PixelFormat::Nv12, PixelFormat::I420],
        OutputFormat::Nv12 => [PixelFormat::Nv12, PixelFormat::Yuy2, PixelFormat::I420],
        OutputFormat::I420 => [PixelFormat::I420, PixelFormat::Yuy2, PixelFormat::Nv12],
    };
    let selected = preferred.into_iter().find(|format| supported.contains(format));
    selected.ok_or_else(|| {
        ReceiverError::InvalidConfiguration(format!(
            "no output pixel format is supported for requested {requested:?}"
        ))
    })
}
