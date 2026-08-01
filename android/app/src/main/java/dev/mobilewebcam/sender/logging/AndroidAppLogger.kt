package dev.mobilewebcam.sender.logging

import android.util.Log

object AndroidAppLogger : AppLogger {
    private const val TAG = "MobileWebcam"

    override fun debug(message: String, fields: Map<String, Any?>) {
        Log.d(TAG, format(message, fields))
    }

    override fun info(message: String, fields: Map<String, Any?>) {
        Log.i(TAG, format(message, fields))
    }

    override fun warn(message: String, cause: Throwable?, fields: Map<String, Any?>) {
        Log.w(TAG, format(message, fields), cause)
    }

    override fun error(message: String, cause: Throwable?, fields: Map<String, Any?>) {
        Log.e(TAG, format(message, fields), cause)
    }

    private fun format(message: String, fields: Map<String, Any?>): String =
        if (fields.isEmpty()) message else "$message ${fields.entries.joinToString()}"
}
