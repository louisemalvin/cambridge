package dev.cambridge.sender.session

import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.StreamVideoConfiguration
import dev.cambridge.sender.model.VideoProfile

data class ResolvedVideoConfiguration(
    val selectedEncoder: EncoderCapability,
    val eligibleEncoders: List<EncoderCapability>,
    val capabilities: List<PhoneVideoModeCapability>,
    val configuration: StreamVideoConfiguration,
)

object VideoConfigurationResolver {
    fun resolve(
        current: StreamVideoConfiguration,
        modes: List<VideoProfile>,
        cameraSupportedModeIds: Set<String>,
        encoders: List<EncoderCapability>,
    ): ResolvedVideoConfiguration? {
        val eligibleEncoders = EncoderCatalog.eligible(encoders, modes)
        if (eligibleEncoders.isEmpty()) return null

        val defaultEncoder = EncoderCatalog.default(eligibleEncoders) ?: return null
        val savedEncoder = eligibleEncoders.firstOrNull {
            it.implementationName == current.encoderName
        }
        val selectedEncoder = listOfNotNull(savedEncoder, defaultEncoder)
            .plus(eligibleEncoders)
            .distinctBy(EncoderCapability::implementationName)
            .firstOrNull { encoder ->
                val capabilities = PhoneVideoCapabilities.resolve(
                    modes = modes,
                    cameraSupportedModeIds = cameraSupportedModeIds,
                    selectedEncoder = encoder,
                )
                capabilities.any(PhoneVideoModeCapability::isSupported) || encoder == defaultEncoder
            } ?: return null
        val capabilities = PhoneVideoCapabilities.resolve(
            modes = modes,
            cameraSupportedModeIds = cameraSupportedModeIds,
            selectedEncoder = selectedEncoder,
        )
        val selectedMode = capabilities.firstOrNull {
            it.mode.id == current.profile.id && it.isSupported
        } ?: capabilities.firstOrNull(PhoneVideoModeCapability::isSupported)
        val selectedProfile = selectedMode?.mode ?: current.profile
        val selectedCapability = selectedMode ?: capabilities.firstOrNull {
            it.mode.id == selectedProfile.id
        }
        val normalizedBitrate = selectedCapability?.let { capability ->
            val minimum = capability.encoderMinimumBitrateBps ?: return@let null
            val maximum = capability.encoderMaximumBitrateBps ?: return@let null
            selectedProfile.clampToStep(current.bitrateBps, minimum, maximum)
        } ?: current.bitrateBps
        val resolvedConfiguration = current.copy(
            encoderName = selectedEncoder.implementationName,
            profile = selectedProfile,
            bitrateBps = normalizedBitrate,
        )
        return ResolvedVideoConfiguration(
            selectedEncoder = selectedEncoder,
            eligibleEncoders = eligibleEncoders,
            capabilities = capabilities,
            configuration = resolvedConfiguration,
        )
    }
}
