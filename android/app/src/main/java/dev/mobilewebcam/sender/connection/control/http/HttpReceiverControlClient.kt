package dev.mobilewebcam.sender.connection.control.http

import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.connection.control.ReceiverControlError
import dev.mobilewebcam.sender.connection.control.ReceiverControlException
import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.DecoderAcceleration
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverCodecCapability
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverHealth
import dev.mobilewebcam.sender.model.SrtTransportEndpoint
import dev.mobilewebcam.sender.model.VideoCodec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

class HttpReceiverControlClient(
    private val client: HttpClient = defaultClient(),
    private val bearerToken: String? = null,
) : ReceiverControlClient {
    override suspend fun healthV2(endpoint: ReceiverEndpoint): Result<ReceiverHealth> =
        executeRequest(endpoint) {
            val response = client.get(endpoint.v2Path("health"))
            val dto = response.requireSuccess().body<HealthResponseV2Dto>()
            checkVersion(dto.protocolVersion, CONTROL_V2_PROTOCOL_VERSION)
            ReceiverHealth(dto.status, dto.protocolVersion)
        }

    override suspend fun capabilitiesV2(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> =
        executeRequest(endpoint) {
            val response = client.get(endpoint.v2Path("capabilities")) {
                authorizeV2(endpoint)
            }
            val dto = response.requireSuccess().body<ReceiverCapabilitiesV2Dto>()
            checkVersion(dto.protocolVersion, CONTROL_V2_PROTOCOL_VERSION)
            ReceiverCapabilities(
                protocolVersion = dto.protocolVersion,
                codecs = dto.videoCodecs.map { codec ->
                    ReceiverCodecCapability(
                        codec = codec.toDomain(),
                        supported = true,
                        decoderAcceleration = DecoderAcceleration.UNKNOWN,
                    )
                },
                outputDevice = RECEIVER_OWNED_OUTPUT,
                pixelFormats = listOf(dto.output.pixelFormat.toDomain()),
                activeSession = dto.active,
            )
        }

    override suspend fun createSessionV2(
        endpoint: ReceiverEndpoint,
        request: PrepareSessionRequest,
    ): Result<NegotiatedSession> = executeRequest(endpoint) {
        val response = client.post(endpoint.v2Path("sessions")) {
            authorizeV2(endpoint)
            contentType(ContentType.Application.Json)
            setBody(
                CreateSessionRequestV2Dto(
                    protocolVersion = CONTROL_V2_PROTOCOL_VERSION,
                    preferredCodecs = request.preferredCodecs.map(VideoCodec::toDto),
                    profile = V2VideoProfileDto(
                        width = request.profile.width,
                        height = request.profile.height,
                        fps = request.profile.fps,
                    ),
                    bitrateByCodec = V2BitrateByCodecDto(
                        h264 = request.bitrateByCodec.getValue(VideoCodec.H264),
                        h265 = request.bitrateByCodec.getValue(VideoCodec.H265),
                    ),
                ),
            )
        }.requireSuccess()
        val dto = response.body<CreateSessionResponseV2Dto>()
        checkVersion(dto.protocolVersion, CONTROL_V2_PROTOCOL_VERSION)
        val transport = SrtTransportEndpoint(
            host = dto.transport.host,
            port = dto.transport.port,
            streamId = dto.transport.streamId,
            latencyMs = dto.transport.latencyMs,
            keyLengthBytes = dto.transport.keyLengthBytes,
            passphrase = dto.transport.passphrase,
        )
        NegotiatedSession(
            sessionId = dto.sessionId,
            endpoint = endpoint,
            selectedCodec = dto.video.codec.toDomain(),
            profile = request.profile.copy(
                width = dto.video.width,
                height = dto.video.height,
                fps = dto.video.fps,
                h264BitrateBps = if (dto.video.codec == ControlCodec.H264) {
                    dto.video.bitrateBps
                } else {
                    request.profile.h264BitrateBps
                },
                h265BitrateBps = if (dto.video.codec == ControlCodec.H265) {
                    dto.video.bitrateBps
                } else {
                    request.profile.h265BitrateBps
                },
            ),
            bitrateBps = dto.video.bitrateBps,
            mediaPort = transport.port,
            outputPixelFormat = dto.output.pixelFormat.toDomain(),
            warnings = emptyList(),
            srtEndpoint = transport,
            connectDeadlineMs = dto.connectDeadlineMs,
            reconnectGraceMs = dto.reconnectGraceMs,
        )
    }

    override suspend fun stopSessionV2(
        endpoint: ReceiverEndpoint,
        sessionId: String,
    ): Result<Unit> = executeRequest(endpoint) {
        client.delete(endpoint.v2Path("sessions/$sessionId")) {
            authorizeV2(endpoint)
        }.requireSuccess()
        Unit
    }

    override suspend fun sessionStateV2(
        endpoint: ReceiverEndpoint,
        sessionId: String,
    ): Result<Unit> = executeRequest(endpoint) {
        val response = client.get(endpoint.v2Path("sessions/$sessionId")) {
            authorizeV2(endpoint)
        }.requireSuccess()
        val dto = response.body<SessionStatusResponseV2Dto>()
        checkVersion(dto.protocolVersion, CONTROL_V2_PROTOCOL_VERSION)
        check(dto.sessionId == sessionId) { "Receiver returned a different session ID" }
        check(dto.state != SessionStateV2Dto.IDLE &&
            dto.state != SessionStateV2Dto.FAILED &&
            dto.state != SessionStateV2Dto.EXPIRED
        ) { "Receiver session is no longer active: ${dto.state}" }
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

    private fun checkVersion(version: Int, expected: Int = CONTROL_V2_PROTOCOL_VERSION) {
        if (version != expected) {
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

    private fun ReceiverEndpoint.v2Path(path: String): String =
        "$controlBaseUrl/v2/$path"

    private fun io.ktor.client.request.HttpRequestBuilder.authorizeV2(
        endpoint: ReceiverEndpoint,
    ) {
        (endpoint.controlToken ?: bearerToken)?.let { token ->
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    private companion object {
        const val HTTP_SUCCESS_STATUS_MIN = 200
        const val HTTP_SUCCESS_STATUS_MAX = 299
        const val RECEIVER_OWNED_OUTPUT = "receiver-owned-output"

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(ProtocolJson.instance)
            }
        }
    }
}
