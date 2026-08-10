package dev.cambridge.sender.model

data class EncoderCapability(
    val codec: VideoCodec,
    val profileId: String,
    val supported: Boolean,
    val acceleration: EncoderAcceleration,
    val encoderName: String?,
    val reason: String? = null,
    val minimumBitrateBps: Int? = null,
    val maximumBitrateBps: Int? = null,
)
