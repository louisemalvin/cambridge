package dev.mobilewebcam.sender.session

import dev.mobilewebcam.sender.model.VideoProfile

object VideoProfiles {
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_KEYFRAME_INTERVAL_SECONDS = 1

    val PROFILE_720P30: VideoProfile = VideoProfile(
        id = "720p30",
        width = 1_280,
        height = 720,
        fps = DEFAULT_FPS,
        h264BitrateBps = 4_000_000,
        keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    )

    val PROFILE_2K30: VideoProfile = VideoProfile(
        id = "2k30",
        width = 2_560,
        height = 1_440,
        fps = DEFAULT_FPS,
        h264BitrateBps = 18_000_000,
        keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    )

    val normal: List<VideoProfile> = listOf(PROFILE_2K30)

    /** Includes the named AVD smoke profile for test-only intent injection. */
    val all: List<VideoProfile> = listOf(PROFILE_720P30) + normal

    val default: VideoProfile = PROFILE_2K30
}
