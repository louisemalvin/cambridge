package dev.mobilewebcam.sender.connection.discovery

import dev.mobilewebcam.sender.connection.control.http.CONTROL_V2_PROTOCOL_VERSION
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredReceiver(
    val serviceName: String,
    val endpoint: ReceiverEndpoint,
)

sealed interface ReceiverDiscoveryState {
    data object Idle : ReceiverDiscoveryState
    data object Searching : ReceiverDiscoveryState
    data class Available(val receivers: List<DiscoveredReceiver>) : ReceiverDiscoveryState
    data class Failed(val reason: String) : ReceiverDiscoveryState
}

interface ReceiverDiscovery {
    val state: StateFlow<ReceiverDiscoveryState>

    fun start()

    fun stop()
}

object ReceiverDiscoveryContract {
    const val SERVICE_TYPE = "_mobile-webcam._tcp"
    const val PROTOCOL_VERSION = CONTROL_V2_PROTOCOL_VERSION
    const val TXT_PROTOCOL_VERSION = "version"
    const val TXT_DISPLAY_NAME = "name"
    const val TXT_AUTHENTICATION = "auth"
    const val AUTHENTICATION_REQUIRED = "required"

    fun endpointFrom(
        serviceName: String,
        host: String?,
        port: Int,
        attributes: Map<String, ByteArray>,
    ): ReceiverEndpoint? {
        val protocolVersion = attributes.value(TXT_PROTOCOL_VERSION)
            ?.decodeToString()
            ?.toIntOrNull()
        if (protocolVersion != PROTOCOL_VERSION) return null

        val normalizedHost = host?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val displayName = attributes.value(TXT_DISPLAY_NAME)
            ?.decodeToString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: serviceName
        val authenticationRequired = attributes.value(TXT_AUTHENTICATION)
            ?.decodeToString()
            ?.equals(AUTHENTICATION_REQUIRED, ignoreCase = true)
            ?: false

        return ReceiverEndpoint(
            host = normalizedHost,
            controlPort = port,
            displayName = displayName,
            receiverId = serviceName,
            authenticationRequired = authenticationRequired,
        ).takeIf(ReceiverEndpoint::isValid)
    }

    private fun Map<String, ByteArray>.value(key: String): ByteArray? =
        entries.firstOrNull { (candidate, _) -> candidate.equals(key, ignoreCase = true) }?.value
}
