package dev.mobilewebcam.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mobilewebcam.sender.model.StreamOrientation
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
        store.updateStreamOrientation(StreamOrientation.LANDSCAPE_REVERSED)

        try {
            val reloaded = SenderSettingsStore(context)
            assertEquals(VideoProfiles.PROFILE_2K30, reloaded.state.value.profile)
            assertEquals(StreamOrientation.LANDSCAPE_REVERSED, reloaded.state.value.streamOrientation)
        } finally {
            store.updateProfile(VideoProfiles.default)
            store.updateStreamOrientation(StreamOrientation.LANDSCAPE)
        }
    }
}
