package dev.mobilewebcam.sender.connection.control.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object DirectStreamContract {
    const val PROTOCOL_VERSION = 2
    const val CONTROL_HEADER_BYTES = 4
    const val MAXIMUM_CONTROL_MESSAGE_BYTES = 8_192
    const val RTP_PAYLOAD_TYPE = 96
    const val RTP_CLOCK_RATE_HZ = 90_000
    const val RTP_HEADER_BYTES = 12
    const val RTP_MTU_BYTES = 1_200
    const val MAXIMUM_ACCESS_UNIT_BYTES = 8 * 1024 * 1024
    const val MAXIMUM_ENCODED_QUEUE = 2
    const val DEFAULT_MEDIA_PORT_OFFSET = 1
    const val DEFAULT_CONTROL_PORT = 55_031
    const val DEFAULT_MEDIA_PORT = DEFAULT_CONTROL_PORT + DEFAULT_MEDIA_PORT_OFFSET
    const val FIRST_STREAM_GENERATION = 1L
    const val CONNECT_TIMEOUT_MILLIS = 2_000
    const val REQUEST_TIMEOUT_MILLIS = 2_000
    const val MINIMUM_DIMENSION = 16
    const val DIMENSION_ALIGNMENT = 2
    const val MAXIMUM_LONG_EDGE = 3_840
    const val MAXIMUM_SHORT_EDGE = 2_160
    const val DEFAULT_CODED_WIDTH = 2_560
    const val DEFAULT_CODED_HEIGHT = 1_440
    const val DEFAULT_PROFILE_ID = "2k30"
    const val MINIMUM_BITRATE_BPS = 100_000
    const val MAXIMUM_BITRATE_BPS = 100_000_000
    const val MINIMUM_PORT = 1
    const val MAXIMUM_PORT = 65_535
    const val SUPPORTED_FPS = 30
    const val KEYFRAME_INTERVAL_SECONDS = 1

    fun hello(
        sessionId: String,
        generation: Long,
        codedWidth: Int,
        codedHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        rotationDegrees: Int,
        fps: Int,
        bitrateBps: Int,
    ): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("type", "hello")
        put("sessionId", sessionId)
        put("generation", generation)
        put("codec", "h264")
        put("codedWidth", codedWidth)
        put("codedHeight", codedHeight)
        put("displayWidth", displayWidth)
        put("displayHeight", displayHeight)
        put("rotationDegrees", rotationDegrees)
        put("fps", fps)
        put("bitrateBps", bitrateBps)
    }

    fun stop(sessionId: String, generation: Long): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("type", "stop")
        put("sessionId", sessionId)
        put("generation", generation)
    }

    fun requestIdr(sessionId: String, generation: Long): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("type", "request_idr")
        put("sessionId", sessionId)
        put("generation", generation)
    }

    fun JsonObject.requireProtocolVersion(): Int = intField("protocolVersion")

    fun JsonObject.stringField(name: String): String = stringFieldOrNull(name)
        ?: error("Control field is missing or not a string: $name")

    fun JsonObject.stringFieldOrNull(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

    fun JsonObject.longField(name: String): Long = (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
        ?: error("Control field is missing or not an integer: $name")

    fun JsonObject.intField(name: String): Int = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: error("Control field is missing or not an integer: $name")

    fun JsonObjectBuilder.putIdentity(sessionId: String, generation: Long) {
        put("sessionId", sessionId)
        put("generation", generation)
    }
}
