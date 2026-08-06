package dev.mobilewebcam.sender.model

data class EncoderCapability(
    val codec: VideoCodec,
    val profileId: String,
    val supported: Boolean,
    val acceleration: EncoderAcceleration,
    val encoderName: String?,
    val reason: String? = null,
)
