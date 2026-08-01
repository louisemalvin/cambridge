package dev.mobilewebcam.sender.model

data class EncoderCapability(
    val codec: VideoCodec,
    val profileId: String,
    val supported: Boolean,
    val acceleration: EncoderAcceleration,
    val encoderName: String?,
    val reason: String? = null,
) {
    fun supportsAutomatically(): Boolean =
        supported && !(acceleration == EncoderAcceleration.SOFTWARE && profileId != "1080p30")
}

data class SenderCapabilities(
    val encoders: List<EncoderCapability>,
) {
    fun supports(
        codec: VideoCodec,
        profileId: String,
        allowKnownSoftware: Boolean,
    ): Boolean = encoders.any {
        it.codec == codec &&
            it.profileId == profileId &&
            it.supported &&
            (allowKnownSoftware || it.acceleration != EncoderAcceleration.SOFTWARE)
    }
}
