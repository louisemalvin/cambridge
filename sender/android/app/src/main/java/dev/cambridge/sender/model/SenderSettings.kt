package dev.cambridge.sender.model

import dev.cambridge.sender.media.camera.CameraStabilizationMode
import kotlinx.coroutines.flow.StateFlow

data class StreamVideoConfiguration(
    val encoderName: String?,
    val profile: VideoProfile,
    val bitrateBps: Int,
    val streamOrientation: StreamOrientation,
)

data class SenderSettings(
    val videoConfiguration: StreamVideoConfiguration,
    val stabilizationMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
    val receiverEndpoint: ReceiverEndpoint? = null,
) {
    constructor(
        profile: VideoProfile,
        bitrateBps: Int = profile.defaultBitrateBps,
        streamOrientation: StreamOrientation = StreamOrientation.LANDSCAPE,
        stabilizationMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
        receiverEndpoint: ReceiverEndpoint? = null,
        selectedEncoderName: String? = null,
    ) : this(
        videoConfiguration = StreamVideoConfiguration(
            encoderName = selectedEncoderName,
            profile = profile,
            bitrateBps = bitrateBps,
            streamOrientation = streamOrientation,
        ),
        stabilizationMode = stabilizationMode,
        receiverEndpoint = receiverEndpoint,
    )

    val profile: VideoProfile
        get() = videoConfiguration.profile

    val bitrateBps: Int
        get() = videoConfiguration.bitrateBps

    val streamOrientation: StreamOrientation
        get() = videoConfiguration.streamOrientation

    val selectedEncoderName: String?
        get() = videoConfiguration.encoderName
}

interface SenderSettingsRepository {
    val state: StateFlow<SenderSettings>

    fun updateProfile(profile: VideoProfile)

    fun updateBitrate(bitrateBps: Int) = Unit

    fun updateEncoderName(encoderName: String) {
        updateVideoConfiguration(state.value.videoConfiguration.copy(encoderName = encoderName))
    }

    fun updateVideoConfiguration(configuration: StreamVideoConfiguration)

    fun updateStreamOrientation(orientation: StreamOrientation)

    fun updateStabilizationMode(mode: CameraStabilizationMode) = Unit

    fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) = Unit
}
