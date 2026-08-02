package dev.mobilewebcam.sender.model

data class ReceiverCodecCapability(
    val codec: VideoCodec,
    val supported: Boolean,
    val decoderAcceleration: DecoderAcceleration,
)

enum class OutputPixelFormat {
    YUY2,
    NV12,
    I420,
}

data class ReceiverCapabilities(
    val protocolVersion: Int,
    val codecs: List<ReceiverCodecCapability>,
    val outputDevice: String,
    val pixelFormats: List<OutputPixelFormat>,
    val activeSession: Boolean,
) {
    fun supports(codec: VideoCodec): Boolean =
        codecs.any { it.codec == codec && it.supported }
}
