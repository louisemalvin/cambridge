package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.ui.SenderUiState
import dev.mobilewebcam.sender.ui.buildFailureDiagnostics
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDiagnosticsTest {
    @Test
    fun diagnosticsIncludeConnectionContextAndExceptionStack() {
        val state = SenderUiState(
            receiverHost = "192.168.1.149",
            controlPort = "5001",
        )
        val cause = NoSuchMethodError("No static method ByteChannel")

        val details = buildFailureDiagnostics(
            state = state,
            failure = StreamFailure.ReceiverUnavailable("Health check failed"),
            cause = cause,
        )

        assertTrue(details.contains("192.168.1.149:5001"))
        assertTrue(details.contains("No static method ByteChannel"))
        assertTrue(details.contains("NoSuchMethodError"))
    }
}
