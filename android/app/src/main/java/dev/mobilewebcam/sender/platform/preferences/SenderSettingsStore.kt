package dev.mobilewebcam.sender.platform.preferences

import android.content.Context
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.session.VideoProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SenderSettingsStore(
    context: Context,
) : SenderSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val settingsFlow = MutableStateFlow(load())

    override val state: StateFlow<SenderSettings> = settingsFlow.asStateFlow()

    @Synchronized
    override fun updateProfile(profile: VideoProfile) {
        persist(settingsFlow.value.copy(profile = profile))
    }

    @Synchronized
    override fun updateStreamOrientation(orientation: StreamOrientation) {
        persist(settingsFlow.value.copy(streamOrientation = orientation))
    }

    @Synchronized
    override fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) {
        persist(settingsFlow.value.copy(receiverEndpoint = endpoint))
    }

    private fun load(): SenderSettings {
        val profile = preferences.getString(PROFILE_KEY, null)
            ?.let { stored -> VideoProfiles.normal.firstOrNull { it.id == stored } }
            ?: VideoProfiles.default
        return SenderSettings(
            profile = profile,
            streamOrientation = preferences.getString(ORIENTATION_KEY, null)
                ?.let { stored -> runCatching { StreamOrientation.valueOf(stored) }.getOrNull() }
                ?: StreamOrientation.LANDSCAPE,
            receiverEndpoint = loadReceiverEndpoint(),
        )
    }

    private fun persist(settings: SenderSettings) {
        val editor = preferences.edit()
            .putString(PROFILE_KEY, settings.profile.id)
            .putString(ORIENTATION_KEY, settings.streamOrientation.name)
        val endpoint = settings.receiverEndpoint
        if (endpoint == null) {
            editor.remove(RECEIVER_HOST_KEY)
                .remove(RECEIVER_PORT_KEY)
                .remove(RECEIVER_NAME_KEY)
        } else {
            editor.putString(RECEIVER_HOST_KEY, endpoint.host)
                .putInt(RECEIVER_PORT_KEY, endpoint.controlPort)
                .putString(RECEIVER_NAME_KEY, endpoint.displayName)
        }
        editor.commit()
        settingsFlow.value = settings
    }

    private fun loadReceiverEndpoint(): ReceiverEndpoint? {
        val host = preferences.getString(RECEIVER_HOST_KEY, null) ?: return null
        val port = preferences.getInt(RECEIVER_PORT_KEY, INVALID_PORT)
        val displayName = preferences.getString(RECEIVER_NAME_KEY, null)
            ?: DEFAULT_RECEIVER_NAME
        return ReceiverEndpoint(
            host = host,
            controlPort = port,
            displayName = displayName,
        ).takeIf(ReceiverEndpoint::isValid)
    }

    private companion object {
        const val PREFERENCES_NAME = "sender-settings"
        const val PROFILE_KEY = "profile"
        const val ORIENTATION_KEY = "stream-orientation"
        const val RECEIVER_HOST_KEY = "receiver-host"
        const val RECEIVER_PORT_KEY = "receiver-port"
        const val RECEIVER_NAME_KEY = "receiver-name"
        const val INVALID_PORT = -1
        const val DEFAULT_RECEIVER_NAME = "Receiver"
    }
}
