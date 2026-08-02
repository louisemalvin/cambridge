package dev.mobilewebcam.sender.config

import dev.mobilewebcam.sender.model.VideoProfile

object VideoProfiles {
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_KEYFRAME_INTERVAL_SECONDS = 1

    val all: List<VideoProfile> = listOf(
        VideoProfile(
            id = "1080p30",
            width = 1920,
            height = 1080,
            fps = DEFAULT_FPS,
            h264BitrateBps = 10_000_000,
            h265BitrateBps = 7_000_000,
            keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
        ),
        VideoProfile(
            id = "1440p30",
            width = 2560,
            height = 1440,
            fps = DEFAULT_FPS,
            h264BitrateBps = 18_000_000,
            h265BitrateBps = 12_000_000,
            keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
        ),
        VideoProfile(
            id = "4k30",
            width = 3840,
            height = 2160,
            fps = DEFAULT_FPS,
            h264BitrateBps = 32_000_000,
            h265BitrateBps = 20_000_000,
            keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
        ),
    )

    val default: VideoProfile = all.first { it.id == AppDefaults.defaultProfileId }
}
