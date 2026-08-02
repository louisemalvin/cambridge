package dev.mobilewebcam.sender.streaming.rootencoder

import dev.mobilewebcam.sender.model.StreamConfiguration

internal data class RootEncoderVideoConfiguration(
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val fps: Int,
    val keyframeIntervalSeconds: Int,
    val rotationDegrees: Int,
)

internal fun StreamConfiguration.toRootEncoderVideo(): RootEncoderVideoConfiguration =
    RootEncoderVideoConfiguration(
        width = profile.width,
        height = profile.height,
        bitrateBps = bitrateBps,
        fps = profile.fps,
        keyframeIntervalSeconds = keyframeIntervalSeconds,
        // Keep encoded dimensions equal to the negotiated profile. Display
        // orientation is applied to the preview surface instead.
        rotationDegrees = ENCODED_OUTPUT_ROTATION_DEGREES,
    )

private const val ENCODED_OUTPUT_ROTATION_DEGREES = 0
