package dev.mobilewebcam.sender.model

import kotlinx.coroutines.flow.StateFlow

data class SenderSettings(
    val codecPreference: CodecPreference,
    val profile: VideoProfile,
    val receiverEndpoint: ReceiverEndpoint? = null,
)

interface SenderSettingsRepository {
    val state: StateFlow<SenderSettings>

    fun updateCodecPreference(preference: CodecPreference)

    fun updateProfile(profile: VideoProfile)

    fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) = Unit
}
