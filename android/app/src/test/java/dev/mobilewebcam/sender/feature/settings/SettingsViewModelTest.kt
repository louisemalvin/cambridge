package dev.mobilewebcam.sender.feature.settings

import dev.mobilewebcam.sender.app.model.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun settingsUiStateDefaults() {
        val state = SettingsUiState()
        assertEquals(false, state.isStreaming)
        assertEquals(null, state.receiverName)
        assertEquals(null, state.validationMessage)
    }
}
