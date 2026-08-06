package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.app.model.buildFailureDiagnostics
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDiagnosticsTest {
    @Test
    fun diagnosticsIncludeConnectionContextAndExceptionStack() {
        val cause = NoSuchMethodError("No static method ByteChannel")

        val details = buildFailureDiagnostics(
            receiverName = "Test desktop",
            profile = defaultProfile,
            failure = StreamFailure.ReceiverUnavailable("Desktop unavailable"),
            cause = cause,
        )

        assertTrue(details.contains("Test desktop"))
        assertTrue(details.contains("No static method ByteChannel"))
        assertTrue(details.contains("NoSuchMethodError"))
    }

    private companion object {
        val defaultProfile = dev.mobilewebcam.sender.session.VideoProfiles.default
    }
}
