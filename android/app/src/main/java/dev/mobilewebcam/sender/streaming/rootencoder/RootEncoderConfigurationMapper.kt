package dev.mobilewebcam.sender.streaming.rootencoder

import dev.mobilewebcam.sender.model.StreamConfiguration

internal data class RootEncoderVideoConfiguration(
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val fps: Int,
    val keyframeIntervalSeconds: Int,
)

internal fun StreamConfiguration.toRootEncoderVideo(): RootEncoderVideoConfiguration =
    RootEncoderVideoConfiguration(
        width = profile.width,
        height = profile.height,
        bitrateBps = bitrateBps,
        fps = profile.fps,
        keyframeIntervalSeconds = keyframeIntervalSeconds,
    )
