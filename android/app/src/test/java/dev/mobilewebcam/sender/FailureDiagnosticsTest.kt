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
            activeReceiverName = "Test desktop",
        )
        val cause = NoSuchMethodError("No static method ByteChannel")

        val details = buildFailureDiagnostics(
            state = state,
            failure = StreamFailure.ReceiverUnavailable("Health check failed"),
            cause = cause,
        )

        assertTrue(details.contains("Test desktop"))
        assertTrue(details.contains("No static method ByteChannel"))
        assertTrue(details.contains("NoSuchMethodError"))
    }
}
