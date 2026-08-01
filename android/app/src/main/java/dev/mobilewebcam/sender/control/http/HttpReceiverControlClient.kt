package dev.mobilewebcam.sender.control.http

import dev.mobilewebcam.sender.control.ReceiverControlClient
import dev.mobilewebcam.sender.control.ReceiverControlException
import dev.mobilewebcam.sender.control.ReceiverControlError
import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverHealth
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json

class HttpReceiverControlClient(
    private val client: HttpClient = defaultClient(),
) : ReceiverControlClient {
    override suspend fun health(endpoint: ReceiverEndpoint): Result<ReceiverHealth> =
        request(endpoint) {
            val response = client.get(endpoint.path("health"))
            val dto = response.body<HealthResponseDto>()
            checkVersion(dto.protocolVersion)
            ReceiverHealth(dto.status, dto.protocolVersion)
        }

    override suspend fun capabilities(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> =
        request(endpoint) {
            val response = client.get(endpoint.path("capabilities"))
            val dto = response.body<CapabilitiesResponseDto>()
            checkVersion(dto.protocolVersion)
            dto.toDomain()
        }

    override suspend fun prepareSession(
        endpoint: ReceiverEndpoint,
        sessionRequest: PrepareSessionRequest,
    ): Result<NegotiatedSession> = request(endpoint) {
        val response = client.post(endpoint.path("sessions/prepare")) {
            setBody(
                PrepareSessionRequestDto(
                    protocolVersion = CONTROL_PROTOCOL_VERSION,
                    preferredCodecs = sessionRequest.preferredCodecs.map(VideoCodec::toDto),
                    profile = VideoProfileDto(
                        width = sessionRequest.profile.width,
                        height = sessionRequest.profile.height,
                        fps = sessionRequest.profile.fps,
                    ),
                    bitrateByCodec = BitrateByCodecDto(
                        h264 = sessionRequest.bitrateByCodec.getValue(VideoCodec.H264),
                        h265 = sessionRequest.bitrateByCodec.getValue(VideoCodec.H265),
                    ),
                ),
            )
        }
        val dto = response.body<PrepareSessionResponseDto>()
        val profile = sessionRequest.profile.copy(
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
    ): Result<Unit> = request(endpoint) {
        val response = client.delete(endpoint.path("sessions/$sessionId"))
        if (response.status != HttpStatusCode.NoContent && response.status.value !in 200..299) {
            throw ReceiverControlException(
                ReceiverControlError.Rejected(response.status.value, response.status.description),
            )
        }
        Unit
    }

    private suspend fun <T> request(
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

    private fun ReceiverEndpoint.path(path: String): String =
        "$controlBaseUrl/v1/$path"

    private companion object {
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(ProtocolJson.instance)
            }
        }
    }
}
