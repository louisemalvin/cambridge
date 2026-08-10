package dev.cambridge.sender.model

import dev.cambridge.sender.media.camera.CameraStabilizationMode
import kotlinx.coroutines.flow.StateFlow

data class SenderSettings(
    val profile: VideoProfile,
    val bitrateBps: Int = profile.defaultBitrateBps,
    val streamOrientation: StreamOrientation = StreamOrientation.LANDSCAPE,
    val stabilizationMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
    val receiverEndpoint: ReceiverEndpoint? = null,
)

interface SenderSettingsRepository {
    val state: StateFlow<SenderSettings>

    fun updateProfile(profile: VideoProfile)

    fun updateBitrate(bitrateBps: Int) = Unit

    fun updateStreamOrientation(orientation: StreamOrientation)

    fun updateStabilizationMode(mode: CameraStabilizationMode) = Unit

    fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) = Unit
}
