package dev.mobilewebcam.sender.model

data class ReceiverEndpoint(
    val host: String,
    val controlPort: Int,
    val displayName: String = DEFAULT_DISPLAY_NAME,
    val controlToken: String? = null,
    val receiverId: String? = null,
    val authenticationRequired: Boolean = false,
) {
    val controlBaseUrl: String
        get() {
            val hostPart = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "http://$hostPart:$controlPort"
        }

    fun isValid(): Boolean = host.isNotBlank() &&
        controlPort in MIN_VALID_NETWORK_PORT..MAX_VALID_NETWORK_PORT &&
        displayName.isNotBlank()

    private companion object {
        const val DEFAULT_DISPLAY_NAME = "Receiver"
        const val MIN_VALID_NETWORK_PORT = 1
        const val MAX_VALID_NETWORK_PORT = 65_535
    }
}
