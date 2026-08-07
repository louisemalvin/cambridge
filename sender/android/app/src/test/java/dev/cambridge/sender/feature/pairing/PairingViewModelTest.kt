package dev.cambridge.sender.feature.pairing

import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingViewModelTest {
    @Test
    fun idleStateMapsToSearching() {
        val state = PairingUiStateMapper.map(
            PairingDomainSnapshot(StreamState.Idle, null),
        )

        assertEquals(PairingUiState.Searching(UiText.Plain("Choose stream settings before starting")), state)
    }

    @Test
    fun connectingStateMapsToConnecting() {
        val connecting = PairingUiStateMapper.map(
            PairingDomainSnapshot(StreamState.Connecting, null),
        )

        assertTrue(connecting is PairingUiState.Connecting)
    }

    @Test
    fun failureStateMapsToFailureMessage() {
        val state = PairingUiStateMapper.map(
            PairingDomainSnapshot(StreamState.Failed(StreamFailure.NetworkDisconnected), null),
        )

        assertEquals(PairingUiState.Failed(UiText.Plain("Connection lost. Open stream setup to try again")), state)
    }

}
