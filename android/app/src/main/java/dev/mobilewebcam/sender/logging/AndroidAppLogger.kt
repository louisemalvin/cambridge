package dev.mobilewebcam.sender.logging

import android.util.Log
import dev.mobilewebcam.sender.BuildConfig
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object AndroidAppLogger : AppLogger {
    private const val TAG = "MobileWebcam"
    private const val DIAGNOSTIC_SCHEMA = "mobile-webcam-diagnostics-v1"
    private const val DIAGNOSTIC_SOURCE = "android"

    override fun debug(message: String, fields: Map<String, Any?>) {
        Log.d(TAG, format("debug", message, fields))
    }

    override fun info(message: String, fields: Map<String, Any?>) {
        Log.i(TAG, format("info", message, fields))
    }

    override fun warn(message: String, cause: Throwable?, fields: Map<String, Any?>) {
        Log.w(TAG, format("warn", message, fields, cause), cause)
    }

    override fun error(message: String, cause: Throwable?, fields: Map<String, Any?>) {
        Log.e(TAG, format("error", message, fields, cause), cause)
    }

    override fun event(name: String, fields: Map<String, Any?>) {
        Log.i(TAG, formatEvent(name, fields))
    }

    private fun format(
        level: String,
        message: String,
        fields: Map<String, Any?>,
        cause: Throwable? = null,
    ): String = buildJsonObject {
        putBase(level, message)
        putFields(fields)
        cause?.let {
            put("causeType", JsonPrimitive(it::class.qualifiedName ?: it::class.simpleName.orEmpty()))
            put("causeMessage", JsonPrimitive(it.message.orEmpty()))
        }
    }.toString()

    private fun formatEvent(name: String, fields: Map<String, Any?>): String = buildJsonObject {
        putBase("info", name)
        put("event", JsonPrimitive(name))
        putFields(fields)
    }.toString()

    private fun JsonObjectBuilder.putBase(level: String, message: String) {
        put("schema", JsonPrimitive(DIAGNOSTIC_SCHEMA))
        put("source", JsonPrimitive(DIAGNOSTIC_SOURCE))
        put("level", JsonPrimitive(level))
        put("message", JsonPrimitive(message))
        put("timestampMs", JsonPrimitive(System.currentTimeMillis()))
        put("monotonicNs", JsonPrimitive(System.nanoTime()))
        put("appVersion", JsonPrimitive(BuildConfig.VERSION_NAME))
        put("buildType", JsonPrimitive(BuildConfig.BUILD_TYPE))
        put("applicationId", JsonPrimitive(BuildConfig.APPLICATION_ID))
    }

    private fun JsonObjectBuilder.putFields(fields: Map<String, Any?>) {
        fields.forEach { (key, value) -> put(key, value.toJsonElement()) }
    }

    private fun Any?.toJsonElement() = when (this) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
