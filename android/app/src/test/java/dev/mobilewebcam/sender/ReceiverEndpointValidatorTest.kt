package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.validation.ReceiverEndpointValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverEndpointValidatorTest {
    @Test
    fun acceptsIpv4AndNormalizesWhitespace() {
        val endpoint = ReceiverEndpointValidator.validate(" 192.168.1.20 ", 5001).getOrThrow()

        assertEquals("192.168.1.20", endpoint.host)
        assertEquals("http://192.168.1.20:5001", endpoint.controlBaseUrl)
    }

    @Test
    fun acceptsBracketedIpv6() {
        val endpoint = ReceiverEndpointValidator.validate("[fe80::1]", 5001).getOrThrow()

        assertEquals("http://[fe80::1]:5001", endpoint.controlBaseUrl)
    }

    @Test
    fun rejectsInvalidPortAndHost() {
        assertTrue(ReceiverEndpointValidator.validate("not-an-ip", 5001).isFailure)
        assertTrue(ReceiverEndpointValidator.validate("192.168.1.20", 0).isFailure)
    }
}
