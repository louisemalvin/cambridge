package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.discovery.ReceiverDiscoveryContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverDiscoveryContractTest {
    @Test
    fun parsesACompatibleReceiverAdvertisement() {
        val endpoint = ReceiverDiscoveryContract.endpointFrom(
            serviceName = "Mobile Webcam",
            host = "192.168.1.20",
            port = 5001,
            attributes = mapOf(
                "version" to "2".encodeToByteArray(),
                "name" to "Office Receiver".encodeToByteArray(),
                "auth" to "required".encodeToByteArray(),
            ),
        )

        requireNotNull(endpoint)
        assertEquals("Office Receiver", endpoint.displayName)
        assertEquals("192.168.1.20", endpoint.host)
        assertEquals(5001, endpoint.controlPort)
        assertTrue(endpoint.authenticationRequired)
        assertEquals("Mobile Webcam", endpoint.receiverId)
    }

    @Test
    fun ignoresUnsupportedProtocolVersions() {
        val endpoint = ReceiverDiscoveryContract.endpointFrom(
            serviceName = "Receiver",
            host = "192.168.1.20",
            port = 5001,
            attributes = mapOf("version" to "1".encodeToByteArray()),
        )

        assertNull(endpoint)
    }

    @Test
    fun missingAuthenticationMetadataDefaultsToNoToken() {
        val endpoint = ReceiverDiscoveryContract.endpointFrom(
            serviceName = "Receiver",
            host = "192.168.1.20",
            port = 5001,
            attributes = mapOf("version" to "2".encodeToByteArray()),
        )

        requireNotNull(endpoint)
        assertFalse(endpoint.authenticationRequired)
        assertEquals("Receiver", endpoint.displayName)
    }
}
