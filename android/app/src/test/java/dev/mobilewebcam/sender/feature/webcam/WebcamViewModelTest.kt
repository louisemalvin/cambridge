package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.app.model.ConnectionUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class WebcamViewModelTest {
    @Test
    fun webcamUiStateDefaultValues() {
        val state = WebcamUiState()
        assertEquals(ConnectionUiState.Waiting, state.connection)
        assertEquals(false, state.isScreenDimmed)
        assertEquals(false, state.isZoomTrayOpen)
    }
}
