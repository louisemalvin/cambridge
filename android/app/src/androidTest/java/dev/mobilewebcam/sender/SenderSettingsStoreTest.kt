package dev.mobilewebcam.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mobilewebcam.sender.media.streaming.session.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.platform.preferences.SenderSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

class SenderSettingsStoreTest {
    @Test
    fun updatedSettingsSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SenderSettingsStore(context)

        store.updateCodecPreference(CodecPreference.FORCE_H264)
        store.updateProfile(VideoProfiles.PROFILE_1440P30)

        try {
            val reloaded = SenderSettingsStore(context)
            assertEquals(CodecPreference.FORCE_H264, reloaded.state.value.codecPreference)
            assertEquals(VideoProfiles.PROFILE_1440P30, reloaded.state.value.profile)
        } finally {
            store.updateCodecPreference(CodecPreference.AUTO_PREFER_H265)
            store.updateProfile(VideoProfiles.default)
        }
    }
}
