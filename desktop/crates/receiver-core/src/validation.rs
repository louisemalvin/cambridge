use receiver_protocol::{PixelFormat, VideoProfile};

use crate::{OutputFormat, ReceiverCapabilities, ReceiverConfig, ReceiverError};

pub fn validate_config(config: &ReceiverConfig) -> Result<(), ReceiverError> {
    if config.control_port == 0 {
        return Err(ReceiverError::InvalidConfiguration(
            "control port must be non-zero".to_owned(),
        ));
    }
    if config.media_port != 0 && config.control_port == config.media_port {
        return Err(ReceiverError::InvalidConfiguration(
            "control and media ports must be different".to_owned(),
        ));
    }
    if config.latency.output_queue_frames == 0 {
        return Err(ReceiverError::InvalidConfiguration(
            "output queue must contain at least one frame".to_owned(),
        ));
    }
    if config.udp_timeout_ms == 0 {
        return Err(ReceiverError::InvalidConfiguration("UDP timeout must be non-zero".to_owned()));
    }
    if config.session_timeout_grace_ms < config.udp_timeout_ms {
        return Err(ReceiverError::InvalidConfiguration(
            "session timeout grace must be at least the UDP timeout".to_owned(),
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
            if profile.width <= 1920 {
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
