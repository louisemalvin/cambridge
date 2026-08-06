package dev.mobilewebcam.sender.feature.settings

import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun settingsUseTheFixedNormal2kProfile() {
        val state = SettingsUiStateMapper.map(
            snapshot = snapshot(profile = VideoProfiles.PROFILE_2K30),
            hasConfiguredReceiver = true,
        )

        assertEquals(VideoProfiles.PROFILE_2K30, VideoProfiles.default)
        assertTrue(state.hasConfiguredReceiver)
    }

    @Test
    fun settingsExposeConnectionStatusAndCameraCapabilities() {
        val state = SettingsUiStateMapper.map(
            snapshot = snapshot(streamState = StreamState.Connecting),
            hasConfiguredReceiver = false,
        )

        assertTrue(state.connection is ConnectionUiState.Connecting)
        assertTrue(state.connectionStatus != null)
        assertTrue(state.camera.lensOptions.isEmpty())
        assertFalse(state.camera.stabilization.isSupported)
    }

    private fun snapshot(
        profile: dev.mobilewebcam.sender.model.VideoProfile = VideoProfiles.default,
        streamState: StreamState = StreamState.Idle,
    ) = StreamPresentationSnapshot(
        profile = profile,
        cameraInteraction = CameraInteractionState(),
        streamState = streamState,
        activeReceiverName = null,
        validationMessage = null,
    )
}
