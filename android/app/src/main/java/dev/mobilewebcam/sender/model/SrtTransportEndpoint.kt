package dev.mobilewebcam.sender.model

data class SrtTransportEndpoint(
    val host: String,
    val port: Int,
    val streamId: String,
    val latencyMs: Int,
    val keyLengthBytes: Int,
    val passphrase: String,
) {
    init {
        require(host.isNotBlank()) { "SRT host is required" }
        require(port in MINIMUM_PORT..MAXIMUM_PORT) { "SRT port is invalid" }
        require(streamId.isNotBlank()) { "SRT stream ID is required" }
        require(latencyMs in MINIMUM_LATENCY_MS..MAXIMUM_ROOT_ENCODER_LATENCY_MS) {
            "SRT latency is outside the supported range"
        }
        require(keyLengthBytes == SRT_AES_256_KEY_LENGTH_BYTES) {
            "SRT key length must be AES-256"
        }
        require(passphrase.length in MINIMUM_PASSPHRASE_LENGTH..MAXIMUM_PASSPHRASE_LENGTH) {
            "SRT passphrase length is invalid"
        }
    }

    fun redactedDescription(): String = "srt://$host:$port"

    fun toRootEncoderUri(): String {
        return "srt://$host:$port?streamid=$streamId&latency=$latencyMs" +
            "&passphrase=$passphrase&pbkeylen=$ROOT_ENCODER_AES_256_BITS"
    }

    fun rootEncoderLatencyMs(): Int = latencyMs

    companion object {
        const val MINIMUM_PORT = 1
        const val MAXIMUM_PORT = 65_535
        private const val MINIMUM_LATENCY_MS = 1
        const val MINIMUM_PASSPHRASE_LENGTH = 10
        const val MAXIMUM_PASSPHRASE_LENGTH = 79
        const val SRT_AES_256_KEY_LENGTH_BYTES = 32
        const val ROOT_ENCODER_AES_256_BITS = 256
        private const val MAXIMUM_ROOT_ENCODER_LATENCY_MS = Int.MAX_VALUE
    }
}
