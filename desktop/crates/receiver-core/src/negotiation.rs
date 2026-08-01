use receiver_protocol::{ReceiverCapabilities, VideoCodec, VideoProfile};

use crate::ReceiverError;

pub trait ReceiverCapabilityProvider: Send + Sync {
    fn capabilities(&self) -> ReceiverCapabilities;

    fn supports_profile(&self, codec: VideoCodec, profile: &VideoProfile) -> bool;
}

#[derive(Debug, Clone)]
pub struct StaticCapabilityProvider {
    capabilities: ReceiverCapabilities,
    supported_profiles: Vec<(VideoCodec, VideoProfile)>,
}

impl StaticCapabilityProvider {
    pub fn new(capabilities: ReceiverCapabilities) -> Self {
        Self { capabilities, supported_profiles: Vec::new() }
    }

    pub fn with_profiles(
        capabilities: ReceiverCapabilities,
        supported_profiles: Vec<(VideoCodec, VideoProfile)>,
    ) -> Self {
        Self { capabilities, supported_profiles }
    }
}

impl ReceiverCapabilityProvider for StaticCapabilityProvider {
    fn capabilities(&self) -> ReceiverCapabilities {
        self.capabilities.clone()
    }

    fn supports_profile(&self, codec: VideoCodec, profile: &VideoProfile) -> bool {
        self.capabilities.supports(codec)
            && (self.supported_profiles.is_empty()
                || self.supported_profiles.iter().any(|(supported_codec, supported_profile)| {
                    *supported_codec == codec && supported_profile == profile
                }))
    }
}

pub fn negotiate_codec(
    preferred_codecs: &[VideoCodec],
    provider: &dyn ReceiverCapabilityProvider,
    profile: &VideoProfile,
) -> Result<VideoCodec, ReceiverError> {
    if !profile.is_valid() {
        return Err(ReceiverError::InvalidConfiguration(
            "profile dimensions and FPS must be positive".to_owned(),
        ));
    }
    preferred_codecs.iter().copied().find(|codec| provider.supports_profile(*codec, profile)).ok_or(
        ReceiverError::NoCompatibleCodec {
            width: profile.width,
            height: profile.height,
            fps: profile.fps,
        },
    )
}
