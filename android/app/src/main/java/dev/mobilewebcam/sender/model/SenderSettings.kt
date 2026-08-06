package dev.mobilewebcam.sender.model

import kotlinx.coroutines.flow.StateFlow

data class SenderSettings(
    val profile: VideoProfile,
    val receiverEndpoint: ReceiverEndpoint? = null,
)

interface SenderSettingsRepository {
    val state: StateFlow<SenderSettings>

    fun updateProfile(profile: VideoProfile)

    fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) = Unit
}
