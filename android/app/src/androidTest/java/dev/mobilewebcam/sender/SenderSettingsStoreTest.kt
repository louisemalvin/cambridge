package dev.mobilewebcam.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mobilewebcam.sender.platform.preferences.SenderSettingsStore
import dev.mobilewebcam.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class SenderSettingsStoreTest {
    @Test
    fun updatedSettingsSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SenderSettingsStore(context)

        store.updateProfile(VideoProfiles.PROFILE_2K30)

        try {
            val reloaded = SenderSettingsStore(context)
            assertEquals(VideoProfiles.PROFILE_2K30, reloaded.state.value.profile)
        } finally {
            store.updateProfile(VideoProfiles.default)
        }
    }
}
