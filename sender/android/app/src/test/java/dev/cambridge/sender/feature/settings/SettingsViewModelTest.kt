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
    fun settingsUseThePhoneOwnedDefaultMode() {
        val state = SettingsUiStateMapper.map(
            snapshot = snapshot(profile = VideoProfiles.PROFILE_2K30),
        )

        assertEquals(VideoProfiles.PROFILE_2K30, VideoProfiles.default)
        assertEquals("OFF", state.camera.stabilization.selectedMode.name)
    }

    @Test
    fun settingsExposeConnectionStatusAndCameraCapabilities() {
        val state = SettingsUiStateMapper.map(
            snapshot = snapshot(streamState = StreamState.Connecting),
        )

        assertTrue(state.connection is ConnectionUiState.Connecting)
        assertTrue(state.connectionStatus != null)
        assertTrue(state.camera.lensOptions.isEmpty())
        assertEquals(1, state.camera.stabilization.supportedModes.size)
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
