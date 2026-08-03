package dev.mobilewebcam.sender.feature.settings

import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun configuredSettingsAreMappedToSelectedOptions() {
        val state = SettingsUiStateMapper.map(
            snapshot = snapshot(
                codecPreference = CodecPreference.FORCE_H264,
                profile = VideoProfiles.PROFILE_1440P30,
            ),
            hasApprovedReceiver = true,
        )

        assertEquals(
            CodecPreference.FORCE_H264.name,
            state.codecOptions.single { it.isSelected }.key,
        )
        assertEquals(
            VideoProfiles.PROFILE_1440P30.id,
            state.profileOptions.single { it.isSelected }.key,
        )
    }

    @Test
    fun settingsExposeConnectionStatusAndUnsupportedCameraCapabilities() {
        val state = SettingsUiStateMapper.map(
            snapshot = snapshot(streamState = StreamState.CheckingReceiver),
            hasApprovedReceiver = false,
        )

        assertTrue(state.connection is ConnectionUiState.Connecting)
        assertTrue(state.connectionStatus != null)
        assertTrue(state.camera.lensOptions.isEmpty())
        assertFalse(state.camera.stabilization.isSupported)
    }

    private fun snapshot(
        codecPreference: CodecPreference = CodecPreference.AUTO_PREFER_H265,
        profile: dev.mobilewebcam.sender.model.VideoProfile = VideoProfiles.default,
        streamState: StreamState = StreamState.Idle,
    ) = StreamPresentationSnapshot(
        codecPreference = codecPreference,
        profile = profile,
        cameraInteraction = CameraInteractionState(),
        streamState = streamState,
        activeReceiverName = null,
        validationMessage = null,
    )
}
