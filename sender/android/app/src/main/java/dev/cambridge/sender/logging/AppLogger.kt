package dev.cambridge.sender.logging

interface AppLogger {
    fun debug(message: String, fields: Map<String, Any?> = emptyMap())
    fun info(message: String, fields: Map<String, Any?> = emptyMap())
    fun warn(message: String, cause: Throwable? = null, fields: Map<String, Any?> = emptyMap())
    fun error(message: String, cause: Throwable? = null, fields: Map<String, Any?> = emptyMap())

    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        info(name, fields + ("event" to name))
    }
}
