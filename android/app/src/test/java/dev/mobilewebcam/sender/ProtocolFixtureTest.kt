package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.control.http.CapabilitiesResponseDto
import dev.mobilewebcam.sender.control.http.HealthResponseDto
import dev.mobilewebcam.sender.control.http.PrepareSessionRequestDto
import dev.mobilewebcam.sender.control.http.PrepareSessionResponseDto
import dev.mobilewebcam.sender.control.http.ProtocolJson
import dev.mobilewebcam.sender.control.http.SessionStateResponseDto
import java.io.File
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolFixtureTest {
    @Test
    fun sharedJsonFixturesDecodeIntoKotlinDtos() {
        val health = decode<HealthResponseDto>("health-response.json")
        val capabilities = decode<CapabilitiesResponseDto>("capabilities-response.json")
        val h264Request = decode<PrepareSessionRequestDto>("prepare-h264-request.json")
        val h265Request = decode<PrepareSessionRequestDto>("prepare-h265-request.json")
        val h264Response = decode<PrepareSessionResponseDto>("prepare-h264-response.json")
        val h265Response = decode<PrepareSessionResponseDto>("prepare-h265-response.json")
        val state = decode<SessionStateResponseDto>("session-state-response.json")

        assertEquals(1, health.protocolVersion)
        assertEquals(2, capabilities.videoCodecs.size)
        assertEquals(1, h264Request.preferredCodecs.size)
        assertEquals(2, h265Request.preferredCodecs.size)
        assertEquals("d3ebda88-5e25-4a47-99e4-44029adf49ef", h264Response.sessionId)
        assertEquals("h265", h265Response.selectedCodec.name.lowercase())
        assertEquals("receiving", state.state.name.lowercase())
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
