package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.feature.webcam.buildFailureDiagnostics
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDiagnosticsTest {
    @Test
    fun diagnosticsIncludeConnectionContextAndExceptionStack() {
        val cause = NoSuchMethodError("No static method ByteChannel")

        val details = buildFailureDiagnostics(
            receiverName = "Test desktop",
            profile = defaultProfile,
            codecPreference = dev.mobilewebcam.sender.model.CodecPreference.AUTO_PREFER_H265,
            failure = StreamFailure.ReceiverUnavailable("Health check failed"),
            cause = cause,
        )

        assertTrue(details.contains("Test desktop"))
        assertTrue(details.contains("No static method ByteChannel"))
        assertTrue(details.contains("NoSuchMethodError"))
    }

    private companion object {
        val defaultProfile = dev.mobilewebcam.sender.config.VideoProfiles.default
    }
}
