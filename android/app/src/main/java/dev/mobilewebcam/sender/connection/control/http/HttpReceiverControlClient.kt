package dev.mobilewebcam.sender.connection.control.http

import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.connection.control.ReceiverControlError
import dev.mobilewebcam.sender.connection.control.ReceiverControlException
import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverHealth
import dev.mobilewebcam.sender.model.VideoCodec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

class HttpReceiverControlClient(
    private val client: HttpClient = defaultClient(),
) : ReceiverControlClient {
    override suspend fun health(endpoint: ReceiverEndpoint): Result<ReceiverHealth> =
        executeRequest(endpoint) {
            val response = client.get(endpoint.path("health")).requireSuccess()
            val dto = response.body<HealthResponseDto>()
            checkVersion(dto.protocolVersion)
            ReceiverHealth(dto.status, dto.protocolVersion)
        }

    override suspend fun capabilities(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> =
        executeRequest(endpoint) {
            val response = client.get(endpoint.path("capabilities")).requireSuccess()
            val dto = response.body<CapabilitiesResponseDto>()
            checkVersion(dto.protocolVersion)
            dto.toDomain()
        }

    override suspend fun prepareSession(
        endpoint: ReceiverEndpoint,
        request: PrepareSessionRequest,
    ): Result<NegotiatedSession> = executeRequest(endpoint) {
        val response = client.post(endpoint.path("sessions/prepare")) {
            contentType(ContentType.Application.Json)
            setBody(
                PrepareSessionRequestDto(
                    protocolVersion = CONTROL_PROTOCOL_VERSION,
                    preferredCodecs = request.preferredCodecs.map(VideoCodec::toDto),
                    profile = VideoProfileDto(
                        width = request.profile.width,
                        height = request.profile.height,
                        fps = request.profile.fps,
                    ),
                    bitrateByCodec = BitrateByCodecDto(
                        h264 = request.bitrateByCodec.getValue(VideoCodec.H264),
                        h265 = request.bitrateByCodec.getValue(VideoCodec.H265),
                    ),
                ),
            )
        }.requireSuccess()
        val dto = response.body<PrepareSessionResponseDto>()
        val profile = request.profile.copy(
            width = dto.profile.width,
            height = dto.profile.height,
            fps = dto.profile.fps,
        )
        NegotiatedSession(
            sessionId = dto.sessionId,
            endpoint = endpoint,
            selectedCodec = dto.selectedCodec.toDomain(),
            profile = profile,
            bitrateBps = dto.profile.bitrateBps,
            mediaPort = dto.media.port,
            outputPixelFormat = dto.output.pixelFormat.toDomain(),
            warnings = dto.warnings,
        )
    }

    override suspend fun stopSession(
        endpoint: ReceiverEndpoint,
        sessionId: String,
    ): Result<Unit> = executeRequest(endpoint) {
        client.delete(endpoint.path("sessions/$sessionId")).requireSuccess()
        Unit
    }

    override suspend fun sessionState(
        endpoint: ReceiverEndpoint,
        sessionId: String,
    ): Result<Unit> = executeRequest(endpoint) {
        val response = client.get(endpoint.path("sessions/$sessionId")).requireSuccess()
        val dto = response.body<SessionStateResponseDto>()
        check(dto.sessionId == sessionId) { "Receiver returned a different session ID" }
        check(dto.state != ControlSessionState.IDLE && dto.state != ControlSessionState.FAILED) {
            "Receiver session is no longer active: ${dto.state}"
        }
        Unit
    }

    private suspend fun <T> executeRequest(
        endpoint: ReceiverEndpoint,
        block: suspend () -> T,
    ): Result<T> = runCatching { block() }.recoverCatching { error ->
        if (error is ReceiverControlException) {
            throw error
        }
        throw ReceiverControlException(
            ReceiverControlError.Network(error.message ?: "Receiver request failed"),
            error,
        )
    }

    private fun checkVersion(version: Int) {
        if (version != CONTROL_PROTOCOL_VERSION) {
            throw ReceiverControlException(
                ReceiverControlError.Protocol("Unsupported protocol version: $version"),
            )
        }
    }

    private fun HttpResponse.requireSuccess(): HttpResponse {
        if (status.value !in HTTP_SUCCESS_STATUS_MIN..HTTP_SUCCESS_STATUS_MAX) {
            throw ReceiverControlException(
                ReceiverControlError.Rejected(status.value, status.description),
            )
        }
        return this
    }

    private fun ReceiverEndpoint.path(path: String): String =
        "$controlBaseUrl/v1/$path"

    private companion object {
        const val HTTP_SUCCESS_STATUS_MIN = 200
        const val HTTP_SUCCESS_STATUS_MAX = 299

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(ProtocolJson.instance)
            }
        }
    }
}
