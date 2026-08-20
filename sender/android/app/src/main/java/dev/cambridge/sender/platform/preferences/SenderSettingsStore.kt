package dev.cambridge.sender.platform.preferences

import android.content.Context
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.model.SenderSettings
import dev.cambridge.sender.model.SenderSettingsRepository
import dev.cambridge.sender.model.StreamVideoConfiguration
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.VideoProfiles
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
        persist(
            settingsFlow.value.withVideoConfiguration {
                copy(profile = profile, bitrateBps = profile.defaultBitrateBps)
            },
        )
    }

    @Synchronized
    override fun updateBitrate(bitrateBps: Int) {
        val current = settingsFlow.value
        val validBitrate = current.profile.clampToStep(
            valueBps = bitrateBps,
            encoderMinimumBps = current.profile.minimumBitrateBps,
            encoderMaximumBps = current.profile.maximumBitrateBps,
        ) ?: return
        persist(current.withVideoConfiguration { copy(bitrateBps = validBitrate) })
    }

    @Synchronized
    override fun updateEncoderName(encoderName: String) {
        persist(settingsFlow.value.withVideoConfiguration { copy(encoderName = encoderName) })
    }

    @Synchronized
    override fun updateVideoConfiguration(configuration: StreamVideoConfiguration) {
        persist(settingsFlow.value.copy(videoConfiguration = configuration))
    }

    @Synchronized
    override fun updateStreamOrientation(orientation: StreamOrientation) {
        persist(settingsFlow.value.withVideoConfiguration { copy(streamOrientation = orientation) })
    }

    @Synchronized
    override fun updateStabilizationMode(mode: CameraStabilizationMode) {
        persist(settingsFlow.value.copy(stabilizationMode = mode))
    }

    @Synchronized
    override fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) {
        persist(settingsFlow.value.copy(receiverEndpoint = endpoint))
    }

    private fun load(): SenderSettings {
        val profile = preferences.getString(PROFILE_KEY, null)
            ?.let { stored -> VideoProfiles.all.firstOrNull { it.id == stored } }
            ?: VideoProfiles.default
        val bitrate = preferences.getInt(BITRATE_KEY, profile.defaultBitrateBps).let { value ->
            profile.clampToStep(
                valueBps = value,
                encoderMinimumBps = profile.minimumBitrateBps,
                encoderMaximumBps = profile.maximumBitrateBps,
            ) ?: profile.defaultBitrateBps
        }
        return SenderSettings(
            videoConfiguration = StreamVideoConfiguration(
                encoderName = preferences.getString(ENCODER_NAME_KEY, null),
                profile = profile,
                bitrateBps = bitrate,
                streamOrientation = preferences.getString(ORIENTATION_KEY, null)
                    ?.let { stored -> runCatching { StreamOrientation.valueOf(stored) }.getOrNull() }
                    ?: StreamOrientation.LANDSCAPE,
            ),
            stabilizationMode = preferences.getString(STABILIZATION_MODE_KEY, null)
                ?.let { stored -> runCatching { CameraStabilizationMode.valueOf(stored) }.getOrNull() }
                ?: CameraStabilizationMode.OFF,
            receiverEndpoint = loadReceiverEndpoint(),
        )
    }

    private fun persist(settings: SenderSettings) {
        val editor = preferences.edit()
            .putString(PROFILE_KEY, settings.profile.id)
            .putInt(BITRATE_KEY, settings.bitrateBps)
            .putString(ORIENTATION_KEY, settings.streamOrientation.name)
            .putString(STABILIZATION_MODE_KEY, settings.stabilizationMode.name)
        settings.selectedEncoderName?.let { encoderName ->
            editor.putString(ENCODER_NAME_KEY, encoderName)
        } ?: editor.remove(ENCODER_NAME_KEY)
        val endpoint = settings.receiverEndpoint
        if (endpoint == null) {
            editor.remove(RECEIVER_HOST_KEY)
                .remove(RECEIVER_PORT_KEY)
                .remove(RECEIVER_NAME_KEY)
                .remove(RECEIVER_ID_KEY)
        } else {
            editor.putString(RECEIVER_HOST_KEY, endpoint.host)
                .putInt(RECEIVER_PORT_KEY, endpoint.controlPort)
                .putString(RECEIVER_NAME_KEY, endpoint.displayName)
            endpoint.receiverId?.let { receiverId ->
                editor.putString(RECEIVER_ID_KEY, receiverId)
            } ?: editor.remove(RECEIVER_ID_KEY)
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
            receiverId = preferences.getString(RECEIVER_ID_KEY, null),
        ).takeIf(ReceiverEndpoint::isValid)
    }

    private inline fun SenderSettings.withVideoConfiguration(
        transform: StreamVideoConfiguration.() -> StreamVideoConfiguration,
    ): SenderSettings = copy(videoConfiguration = videoConfiguration.transform())

    private companion object {
        const val PREFERENCES_NAME = "sender-settings"
        const val PROFILE_KEY = "profile"
        const val BITRATE_KEY = "bitrate-bps"
        const val ENCODER_NAME_KEY = "encoder-name"
        const val ORIENTATION_KEY = "stream-orientation"
        const val STABILIZATION_MODE_KEY = "stabilization-mode"
        const val RECEIVER_HOST_KEY = "receiver-host"
        const val RECEIVER_PORT_KEY = "receiver-port"
        const val RECEIVER_NAME_KEY = "receiver-name"
        const val RECEIVER_ID_KEY = "receiver-id"
        const val INVALID_PORT = -1
        const val DEFAULT_RECEIVER_NAME = "Receiver"
    }
}
