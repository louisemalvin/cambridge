package dev.cambridge.sender.model

data class EncoderModeCapability(
    val modeId: String,
    val sizeAndRateSupported: Boolean,
    val minimumBitrateBps: Int?,
    val maximumBitrateBps: Int?,
    val reason: String? = null,
) {
    init {
        require(modeId.isNotBlank()) { "Encoder mode ID must not be blank" }
        require(
            minimumBitrateBps == null || maximumBitrateBps == null ||
                minimumBitrateBps <= maximumBitrateBps,
        ) { "Encoder bitrate range must be ordered" }
    }
}

data class EncoderCapability(
    val codec: VideoCodec,
    val implementationName: String,
    val acceleration: EncoderAcceleration,
    val surfaceInputSupported: Boolean,
    val modes: List<EncoderModeCapability>,
) {
    init {
        require(implementationName.isNotBlank()) { "Encoder implementation name must not be blank" }
    }

    fun modeFor(modeId: String): EncoderModeCapability? = modes.firstOrNull { it.modeId == modeId }
}
