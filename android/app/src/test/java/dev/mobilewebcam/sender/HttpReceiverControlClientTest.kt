package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.control.http.HttpReceiverControlClient
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpReceiverControlClientTest {
    @Test
    fun prepareSessionSendsJsonBodyWithContentType() = runTest {
        val engine = MockEngine { request ->
            val body = request.body as? TextContent
            assertNotNull("Expected ContentNegotiation to create a text JSON body", body)
            assertEquals(ContentType.Application.Json, body!!.contentType)
            val json = Json.parseToJsonElement(body.text).jsonObject
            assertEquals(1, json.getValue("protocolVersion").jsonPrimitive.int)
            assertEquals(
                "h265",
                json.getValue("preferredCodecs").jsonArray.single().jsonPrimitive.toString().trim('"'),
            )
            assertEquals(1920, json.getValue("profile").jsonObject.getValue("width").jsonPrimitive.int)
            respond(
                content = prepareResponse,
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }

        try {
            val result = HttpReceiverControlClient(client).prepareSession(
                endpoint = ReceiverEndpoint("127.0.0.1", 5001),
                request = PrepareSessionRequest(
                    preferredCodecs = listOf(VideoCodec.H265),
                    profile = profile,
                    bitrateByCodec = mapOf(
                        VideoCodec.H264 to 10_000_000,
                        VideoCodec.H265 to 7_000_000,
                    ),
                ),
            )

            assertTrue(
                result.exceptionOrNull()?.stackTraceToString() ?: "No failure details",
                result.isSuccess,
            )
            assertEquals("test-session", result.getOrThrow().sessionId)
            assertEquals(VideoCodec.H265, result.getOrThrow().selectedCodec)
        } finally {
            client.close()
        }
    }

    private companion object {
        val profile = VideoProfile(
            id = "1080p30",
            width = 1920,
            height = 1080,
            fps = 30,
            h264BitrateBps = 10_000_000,
            h265BitrateBps = 7_000_000,
            keyframeIntervalSeconds = 1,
        )

        const val prepareResponse = """
            {
              "sessionId": "test-session",
              "selectedCodec": "h265",
              "media": { "transport": "mpegts-udp", "port": 5000 },
              "profile": {
                "width": 1920,
                "height": 1080,
                "fps": 30,
                "bitrateBps": 7000000
              },
              "output": { "pixelFormat": "nv12" },
              "warnings": []
            }
        """
    }
}
