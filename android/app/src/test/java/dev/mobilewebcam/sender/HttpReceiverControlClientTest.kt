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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpReceiverControlClientTest {
    @Test
    fun v2SessionUsesTheEndpointBearerTokenAndReturnsTypedSrtTransport() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v2/sessions", request.url.encodedPath)
            assertEquals("Bearer receiver-token", request.headers[HttpHeaders.Authorization])
            val body = request.body as? TextContent
            assertNotNull("Expected ContentNegotiation to create a text JSON body", body)
            assertEquals(ContentType.Application.Json, body!!.contentType)
            respond(
                content = createV2Response,
                status = HttpStatusCode.Created,
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
            val result = HttpReceiverControlClient(client).createSessionV2(
                endpoint = ReceiverEndpoint(
                    host = "127.0.0.1",
                    controlPort = 5001,
                    controlToken = "receiver-token",
                ),
                request = PrepareSessionRequest(
                    preferredCodecs = listOf(VideoCodec.H264),
                    profile = profile,
                    bitrateByCodec = mapOf(
                        VideoCodec.H264 to 10_000_000,
                        VideoCodec.H265 to 7_000_000,
                    ),
                ),
            )

            assertTrue(result.isSuccess)
            val session = result.getOrThrow()
            assertEquals("srt-stream-1", session.srtEndpoint?.streamId)
            assertEquals(32, session.srtEndpoint?.keyLengthBytes)
            assertEquals(120, session.srtEndpoint?.latencyMs)
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

        const val createV2Response = """
            {
              "protocolVersion": 2,
              "sessionId": "test-session-v2",
              "connectDeadlineMs": 10000,
              "reconnectGraceMs": 30000,
              "video": {
                "codec": "h264",
                "container": "mpegts",
                "width": 1920,
                "height": 1080,
                "fps": 30,
                "bitrateBps": 10000000
              },
              "transport": {
                "kind": "srt",
                "mode": "caller",
                "host": "127.0.0.1",
                "port": 5000,
                "streamId": "srt-stream-1",
                "latencyMs": 120,
                "keyLengthBytes": 32,
                "passphrase": "test-passphrase-0123456789"
              },
              "output": { "pixelFormat": "yuy2" }
            }
        """
    }
}
