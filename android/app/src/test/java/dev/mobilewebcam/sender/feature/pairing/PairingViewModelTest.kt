package dev.mobilewebcam.sender.feature.pairing

import dev.mobilewebcam.sender.app.model.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingViewModelTest {
    @Test
    fun searchingStateHasMessage() {
        val state = PairingUiState.Searching(UiText.Plain("Searching..."))
        assertEquals(UiText.Plain("Searching..."), state.message)
    }

    @Test
    fun awaitingApprovalStateMapsToApproval() {
        val state = PairingUiState.AwaitingApproval(UiText.Plain("Desktop PC"))
        assertEquals(UiText.Plain("Desktop PC"), state.receiverName)
    }
}
