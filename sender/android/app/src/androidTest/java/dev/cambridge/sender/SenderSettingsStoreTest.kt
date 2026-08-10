package dev.cambridge.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.platform.preferences.SenderSettingsStore
import dev.cambridge.sender.session.VideoProfiles
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

    @Test
    fun bitrateAndExplicitStabilizationPreferenceSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SenderSettingsStore(context)
        val profile = VideoProfiles.PROFILE_1080P30

        store.updateProfile(profile)
        store.updateBitrate(profile.minimumBitrateBps)
        store.updateStabilizationMode(CameraStabilizationMode.ELECTRONIC)

        try {
            val reloaded = SenderSettingsStore(context)
            assertEquals(profile.minimumBitrateBps, reloaded.state.value.bitrateBps)
            assertEquals(CameraStabilizationMode.ELECTRONIC, reloaded.state.value.stabilizationMode)
        } finally {
            store.updateProfile(VideoProfiles.default)
            store.updateBitrate(VideoProfiles.default.defaultBitrateBps)
            store.updateStabilizationMode(CameraStabilizationMode.OFF)
        }
    }

    @Test
    fun unknownPersistedStabilizationModeMigratesToOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("sender-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("stabilization-mode", "REMOVED_MODE")
            .commit()

        val store = SenderSettingsStore(context)

        assertEquals(CameraStabilizationMode.OFF, store.state.value.stabilizationMode)
        store.updateProfile(VideoProfiles.default)
        store.updateStabilizationMode(CameraStabilizationMode.OFF)
    }

    @Test
    fun selectedReceiverIdentitySurvivesStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SenderSettingsStore(context)
        val receiver = ReceiverEndpoint(
            host = "192.168.1.20",
            controlPort = CamBridgeStreamContract.DEFAULT_CONTROL_PORT,
            displayName = "Studio OBS",
            receiverId = "studio-obs",
        )
        store.updateReceiverEndpoint(receiver)

        try {
            val reloaded = SenderSettingsStore(context)
            assertEquals(receiver, reloaded.state.value.receiverEndpoint)
        } finally {
            store.updateReceiverEndpoint(null)
        }
    }
}
