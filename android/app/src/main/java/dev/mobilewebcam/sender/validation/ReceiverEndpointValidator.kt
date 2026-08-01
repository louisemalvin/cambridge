package dev.mobilewebcam.sender.validation

import dev.mobilewebcam.sender.model.ReceiverEndpoint

object ReceiverEndpointValidator {
    fun validate(host: String, controlPort: Int): Result<ReceiverEndpoint> {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty() || normalizedHost.any(Char::isWhitespace)) {
            return Result.failure(IllegalArgumentException("Enter a receiver IP address"))
        }
        if (controlPort !in 1..65_535) {
            return Result.failure(IllegalArgumentException("Control port must be between 1 and 65535"))
        }
        if (!isIpAddress(normalizedHost)) {
            return Result.failure(IllegalArgumentException("Receiver must be an IPv4 or IPv6 address"))
        }
        return Result.success(ReceiverEndpoint(normalizedHost, controlPort))
    }

    private fun isIpAddress(host: String): Boolean {
        val unwrapped = host.removePrefix("[").removeSuffix("]")
        val ipv4Parts = unwrapped.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { part ->
                part.toIntOrNull()?.let { value -> value in 0..255 } == true
            }) {
            return true
        }
        return unwrapped.contains(':') &&
            unwrapped.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
    }
}
