package dev.cambridge.sender.connection.control.cambridge

import dev.cambridge.discovery.ReceiverDiscoveryAddressFamily
import dev.cambridge.sender.model.ReceiverCapabilities
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CamBridgeStreamContract {
    const val PROTOCOL_VERSION = 7
    const val CONTROL_HEADER_BYTES = 4
    const val MAXIMUM_CONTROL_MESSAGE_BYTES = 8_192
    const val RTP_PAYLOAD_TYPE = 96
    const val RTX_PAYLOAD_TYPE = 97
    const val RTP_CLOCK_RATE_HZ = 90_000
    const val RTP_MTU_BYTES = 1_200
    const val TWCC_EXTENSION_ID = 1
    const val MAXIMUM_ACCESS_UNIT_BYTES = 8_388_608
    const val DEFAULT_CONTROL_PORT = 55_031
    const val DEFAULT_MEDIA_RTP_PORT = 55_032
    const val DEFAULT_MEDIA_RTCP_PORT = 55_033
    const val DEFAULT_SENDER_RTCP_PORT = 55_033
    const val FIRST_STREAM_GENERATION = 1L
    const val CONNECT_TIMEOUT_MILLIS = 2_000
    const val REQUEST_TIMEOUT_MILLIS = 2_000
    const val MINIMUM_DIMENSION = 16
    const val DIMENSION_ALIGNMENT = 2
    const val MAXIMUM_LONG_EDGE = 3_840
    const val MAXIMUM_SHORT_EDGE = 2_160
    const val MINIMUM_FPS = 1
    const val MAXIMUM_FPS = 120
    const val MINIMUM_BITRATE_BPS = 100_000
    const val MAXIMUM_BITRATE_BPS = 100_000_000
    const val MINIMUM_PORT = 1
    const val MAXIMUM_PORT = 65_535
    const val KEYFRAME_INTERVAL_SECONDS = 1
    const val CODEC_H264 = "h264"
    const val MESSAGE_PROBE = "probe"
    const val MESSAGE_CAPABILITIES = "capabilities"
    const val MESSAGE_HELLO = "hello"
    const val MESSAGE_ACCEPTED = "accepted"
    const val MESSAGE_STOP = "stop"
    const val MESSAGE_ERROR = "error"
    const val DISCOVERY_SERVICE_TYPE = "_cambridge._tcp"
    const val DISCOVERY_VERSION = 1
    const val DISCOVERY_ADDRESS_KEY_PREFIX = "address"
    val DISCOVERY_ADDRESS_FAMILY = ReceiverDiscoveryAddressFamily.IPV4
    const val MAXIMUM_DISCOVERY_ADDRESS_COUNT = 16

    fun hello(
        sessionId: String,
        generation: Long,
        profileId: String,
        codedWidth: Int,
        codedHeight: Int,
        rotationDegrees: Int,
        fps: Int,
        targetBitrateBps: Int,
        senderRtcpPort: Int,
    ): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("type", MESSAGE_HELLO)
        put("sessionId", sessionId)
        put("generation", generation)
        put("profileId", profileId)
        put("codec", CODEC_H264)
        put("codedWidth", codedWidth)
        put("codedHeight", codedHeight)
        put("rotationDegrees", rotationDegrees)
        put("fps", fps)
        put("targetBitrateBps", targetBitrateBps)
        put("senderRtcpPort", senderRtcpPort)
    }

    fun stop(sessionId: String, generation: Long): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("type", MESSAGE_STOP)
        put("sessionId", sessionId)
        put("generation", generation)
    }

    fun probe(requestId: String): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("type", MESSAGE_PROBE)
        put("requestId", requestId)
    }

    fun JsonObject.requireProtocolVersion(): Int = intField("protocolVersion").also { version ->
        check(version == PROTOCOL_VERSION) { "Unsupported CamBridge protocol version: $version" }
    }

    fun JsonObject.stringField(name: String): String = stringFieldOrNull(name)
        ?: error("Control field is missing or not a string: $name")

    fun JsonObject.stringFieldOrNull(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

    fun JsonObject.longField(name: String): Long = (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
        ?: error("Control field is missing or not an integer: $name")

    fun JsonObject.intField(name: String): Int = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: error("Control field is missing or not an integer: $name")

    fun JsonObject.requireCapabilities(requestId: String): ReceiverCapabilities {
        requireProtocolVersion()
        check(stringField("type") == MESSAGE_CAPABILITIES) {
            "Control response is not a capabilities message"
        }
        check(stringField("requestId") == requestId) {
            "Control response request ID does not match the probe"
        }
        return ReceiverCapabilities(
            receiverId = stringField("receiverId"),
            displayName = stringField("displayName"),
            maxLongEdge = intField("maxLongEdge"),
            maxShortEdge = intField("maxShortEdge"),
        )
    }

    fun JsonObjectBuilder.putIdentity(sessionId: String, generation: Long) {
        put("sessionId", sessionId)
        put("generation", generation)
    }
}
