package dev.mobilewebcam.sender.model

data class ReceiverEndpoint(
    val host: String,
    val controlPort: Int,
) {
    val controlBaseUrl: String
        get() {
            val hostPart = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "http://$hostPart:$controlPort"
        }
}
