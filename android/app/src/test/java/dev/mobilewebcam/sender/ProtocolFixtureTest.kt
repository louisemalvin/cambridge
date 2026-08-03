package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.control.http.CapabilitiesResponseDto
import dev.mobilewebcam.sender.connection.control.http.HealthResponseDto
import dev.mobilewebcam.sender.connection.control.http.PrepareSessionRequestDto
import dev.mobilewebcam.sender.connection.control.http.PrepareSessionResponseDto
import dev.mobilewebcam.sender.connection.control.http.ProtocolJson
import dev.mobilewebcam.sender.connection.control.http.SessionStateResponseDto
import dev.mobilewebcam.sender.connection.discovery.StartStreamRequestDto
import dev.mobilewebcam.sender.connection.discovery.StartStreamResponseDto
import dev.mobilewebcam.sender.connection.discovery.StartStreamStatusDto
import dev.mobilewebcam.sender.connection.discovery.DescribeSenderRequestDto
import dev.mobilewebcam.sender.connection.discovery.SenderAdvertisementDto
import dev.mobilewebcam.sender.connection.discovery.SenderAvailabilityDto
import dev.mobilewebcam.sender.connection.discovery.SenderControlActionDto
import dev.mobilewebcam.sender.connection.discovery.StopStreamRequestDto
import dev.mobilewebcam.sender.connection.discovery.StopStreamResponseDto
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val describeSender = decode<DescribeSenderRequestDto>("sender-describe-request.json")
        val senderRequest = decode<StartStreamRequestDto>("sender-start-request.json")
        val senderResponse = decode<StartStreamResponseDto>("sender-start-response.json")
        val stopRequest = decode<StopStreamRequestDto>("sender-stop-request.json")
        val stopResponse = decode<StopStreamResponseDto>("sender-stop-stopped-response.json")

        assertEquals(1, health.protocolVersion)
        assertEquals(2, capabilities.videoCodecs.size)
        assertEquals(1, h264Request.preferredCodecs.size)
        assertEquals(2, h265Request.preferredCodecs.size)
        assertEquals("d3ebda88-5e25-4a47-99e4-44029adf49ef", h264Response.sessionId)
        assertEquals("h265", h265Response.selectedCodec.name.lowercase())
        assertEquals("receiving", state.state.name.lowercase())
        assertEquals("describe", describeSender.action.name.lowercase())
        assertEquals(5001, senderRequest.receiverControlPort)
        assertEquals(2, senderRequest.protocolVersion)
        assertEquals(senderRequest.streamId, senderResponse.streamId)
        assertEquals(senderRequest.streamId, stopRequest.streamId)
        assertEquals("accepted", senderResponse.status.name.lowercase())
        assertEquals("stopped", stopResponse.status.name.lowercase())
    }

    @Test
    fun senderResponsesAlwaysEncodeTheRequiredProtocolVersion() {
        val advertisement = SenderAdvertisementDto(
            protocolVersion = 2,
            action = SenderControlActionDto.DESCRIBE_RESULT,
            senderId = "phone-1",
            displayName = "Android phone",
            controlPort = 53_555,
            availability = SenderAvailabilityDto.STANDBY,
        )
        val response = StartStreamResponseDto(
            protocolVersion = 2,
            action = SenderControlActionDto.START_RESULT,
            streamId = "d3ebda88-5e25-4a47-99e4-44029adf49ef",
            senderId = "phone-1",
            status = StartStreamStatusDto.APPROVAL_REQUIRED,
        )

        assertEquals(2, encodedProtocolVersion(advertisement))
        assertEquals(2, encodedProtocolVersion(response))
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

    private inline fun <reified T> encodedProtocolVersion(value: T): Int =
        ProtocolJson.instance.parseToJsonElement(ProtocolJson.instance.encodeToString(value))
            .jsonObject.getValue("protocolVersion").jsonPrimitive.int
}
