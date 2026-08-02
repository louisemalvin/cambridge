package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SenderScreenState
import org.junit.Assert.assertEquals
import org.junit.Test

class WebcamViewModelTest {
    @Test
    fun webcamScreenStateDefaultValues() {
        val state = SenderScreenState()
        assertEquals(ConnectionUiState.Waiting, state.connection)
        assertEquals(false, state.isScreenDimmed)
        assertEquals(false, state.isZoomTrayOpen)
    }
}
