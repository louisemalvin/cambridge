package dev.mobilewebcam.sender.model

import kotlinx.coroutines.flow.StateFlow

data class SenderSettings(
    val profile: VideoProfile,
    val streamOrientation: StreamOrientation = StreamOrientation.LANDSCAPE,
    val receiverEndpoint: ReceiverEndpoint? = null,
)

interface SenderSettingsRepository {
    val state: StateFlow<SenderSettings>

    fun updateProfile(profile: VideoProfile)

    fun updateStreamOrientation(orientation: StreamOrientation)

    fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) = Unit
}
