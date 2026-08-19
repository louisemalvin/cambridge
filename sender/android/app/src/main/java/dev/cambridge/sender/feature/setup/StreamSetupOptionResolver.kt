package dev.cambridge.sender.feature.setup

import dev.cambridge.sender.R
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.StreamPresentationMapper
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.PhoneVideoModeCapability
import dev.cambridge.sender.session.VideoProfiles

internal object StreamSetupOptionResolver {
    fun resolutionOptions(
        selectedProfile: VideoProfile,
        capabilities: List<PhoneVideoModeCapability>,
    ): List<SelectOptionUi> {
        val profiles = if (VideoProfiles.qualityProfiles.any { profile ->
                sameResolution(profile, selectedProfile)
            }) {
            VideoProfiles.qualityProfiles
        } else {
            listOf(selectedProfile) + VideoProfiles.qualityProfiles
        }
        return profiles.map { profile ->
            val profileCapabilities = capabilitiesForResolution(profile, capabilities)
            val isSupported = profileCapabilities.any(PhoneVideoModeCapability::isSupported)
            SelectOptionUi(
                key = profile.id,
                label = StreamPresentationMapper.videoProfileLabel(profile),
                isSelected = sameResolution(profile, selectedProfile),
                isEnabled = isSupported,
                disabledReason = profileCapabilities
                    .takeUnless { isSupported }
                    ?.firstOrNull { capability -> capability.reason != null }
                    ?.reason
                    ?.let(UiText::Plain),
            )
        }
    }

    fun frameRateOptions(
        selectedProfile: VideoProfile,
        capabilities: List<PhoneVideoModeCapability>,
    ): List<SelectOptionUi> = VideoProfiles.profilesForResolution(selectedProfile)
        .distinctBy { profile -> profile.fps }
        .sortedBy { profile -> profile.fps }
        .map { profile ->
            val capability = capabilities.firstOrNull { it.mode.id == profile.id }
            SelectOptionUi(
                key = profile.fps.toString(),
                label = UiText.Resource(R.string.frame_rate_option, listOf(profile.fps)),
                isSelected = profile.fps == selectedProfile.fps,
                isEnabled = capability?.isSupported == true,
                disabledReason = capability
                    ?.takeIf { !it.isSupported }
                    ?.reason
                    ?.let(UiText::Plain),
            )
        }

    private fun capabilitiesForResolution(
        profile: VideoProfile,
        capabilities: List<PhoneVideoModeCapability>,
    ): List<PhoneVideoModeCapability> = capabilities.filter { capability ->
        sameResolution(capability.mode, profile)
    }

    private fun sameResolution(left: VideoProfile, right: VideoProfile): Boolean =
        left.width == right.width && left.height == right.height
}
