package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.model.SrtTransportEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SrtTransportEndpointTest {
    @Test
    fun rootEncoderUsesMillisecondsForTheNegotiatedLatency() {
        val endpoint = SrtTransportEndpoint(
            host = "127.0.0.1",
            port = 5_000,
            streamId = "srt-test",
            latencyMs = 120,
            keyLengthBytes = 32,
            passphrase = "test-passphrase-0123456789",
        )

        assertEquals(120, endpoint.rootEncoderLatencyMs())
        assertEquals(
            "srt://127.0.0.1:5000?streamid=srt-test&latency=120" +
                "&passphrase=test-passphrase-0123456789&pbkeylen=256",
            endpoint.toRootEncoderUri(),
        )
    }
}
