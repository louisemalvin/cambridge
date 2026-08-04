package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.control.http.CreateSessionRequestV2Dto
import dev.mobilewebcam.sender.connection.control.http.CreateSessionResponseV2Dto
import dev.mobilewebcam.sender.connection.control.http.HealthResponseV2Dto
import dev.mobilewebcam.sender.connection.control.http.ProtocolJson
import dev.mobilewebcam.sender.connection.control.http.ReceiverCapabilitiesV2Dto
import dev.mobilewebcam.sender.connection.control.http.SessionStatusResponseV2Dto
import java.io.File
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolFixtureTest {
    @Test
    fun sharedV2JsonFixturesDecodeIntoKotlinDtosAndValidateSrtCredentials() {
        val health = decode<HealthResponseV2Dto>("health-v2-response.json")
        val capabilities = decode<ReceiverCapabilitiesV2Dto>("capabilities-v2-response.json")
        val request = decode<CreateSessionRequestV2Dto>("create-session-h264-v2-request.json")
        val response = decode<CreateSessionResponseV2Dto>("create-session-h264-v2-response.json")
        val status = decode<SessionStatusResponseV2Dto>("session-state-v2-response.json")

        response.transport.validate()
        assertEquals(2, health.protocolVersion)
        assertEquals(2, capabilities.protocolVersion)
        assertEquals(2, request.protocolVersion)
        assertEquals("h264", response.video.codec.name.lowercase())
        assertEquals("receiving", status.state.name.lowercase())
        assertEquals(32, response.transport.keyLengthBytes)
    }

    private inline fun <reified T> decode(name: String): T {
        val candidates = listOf(
            File("../protocol/examples/$name"),
            File("../../protocol/examples/$name"),
            File("protocol/examples/$name"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Shared protocol fixture not found: $name")
        return ProtocolJson.instance.decodeFromString(file.readText())
    }
}
