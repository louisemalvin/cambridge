package dev.mobilewebcam.sender.platform.preferences

import android.content.Context
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.VideoProfile
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
    override fun updateCodecPreference(preference: CodecPreference) {
        persist(settingsFlow.value.copy(codecPreference = preference))
    }

    @Synchronized
    override fun updateProfile(profile: VideoProfile) {
        persist(settingsFlow.value.copy(profile = profile))
    }

    private fun load(): SenderSettings {
        val codecPreference = preferences.getString(CODEC_PREFERENCE_KEY, null)
            ?.let { stored -> CodecPreference.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_CODEC_PREFERENCE
        val profile = preferences.getString(PROFILE_KEY, null)
            ?.let { stored -> VideoProfiles.all.firstOrNull { it.id == stored } }
            ?: VideoProfiles.default
        return SenderSettings(codecPreference = codecPreference, profile = profile)
    }

    private fun persist(settings: SenderSettings) {
        preferences.edit()
            .putString(CODEC_PREFERENCE_KEY, settings.codecPreference.name)
            .putString(PROFILE_KEY, settings.profile.id)
            .commit()
        settingsFlow.value = settings
    }

    private companion object {
        const val PREFERENCES_NAME = "sender-settings"
        const val CODEC_PREFERENCE_KEY = "codec-preference"
        const val PROFILE_KEY = "profile"
        val DEFAULT_CODEC_PREFERENCE = CodecPreference.AUTO_PREFER_H265
    }
}
