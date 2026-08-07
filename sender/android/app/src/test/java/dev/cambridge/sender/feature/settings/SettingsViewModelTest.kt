package dev.cambridge.sender.feature.settings

import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.StreamPresentationSnapshot
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.session.VideoProfiles
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
        profile: dev.cambridge.sender.model.VideoProfile = VideoProfiles.default,
        streamState: StreamState = StreamState.Idle,
    ) = StreamPresentationSnapshot(
        profile = profile,
        cameraInteraction = CameraInteractionState(),
        streamState = streamState,
        activeReceiverName = null,
        validationMessage = null,
    )
}
