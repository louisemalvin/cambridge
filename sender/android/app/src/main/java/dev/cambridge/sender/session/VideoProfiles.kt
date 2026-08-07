package dev.cambridge.sender.session

import dev.cambridge.sender.model.VideoProfile

object VideoProfiles {
    private const val ALTERNATE_FPS = 15
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

    val PROFILE_1080P30: VideoProfile = VideoProfile(
        id = "1080p30",
        width = 1_920,
        height = 1_080,
        fps = DEFAULT_FPS,
        h264BitrateBps = 8_000_000,
        keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    )

    val PROFILE_1080P15: VideoProfile = VideoProfile(
        id = "1080p15",
        width = 1_920,
        height = 1_080,
        fps = ALTERNATE_FPS,
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

    val PROFILE_2K15: VideoProfile = VideoProfile(
        id = "2k15",
        width = 2_560,
        height = 1_440,
        fps = ALTERNATE_FPS,
        h264BitrateBps = 9_000_000,
        keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    )

    val normal: List<VideoProfile> = listOf(
        PROFILE_1080P30,
        PROFILE_1080P15,
        PROFILE_2K30,
        PROFILE_2K15,
    )

    /** Includes the named AVD smoke profile for test-only intent injection. */
    val all: List<VideoProfile> = listOf(PROFILE_720P30) + normal

    /** One representative profile per resolution for the setup screen. */
    val qualityProfiles: List<VideoProfile> = normal.distinctBy { profile ->
        profile.width to profile.height
    }

    val default: VideoProfile = PROFILE_2K30

    fun profilesForResolution(profile: VideoProfile): List<VideoProfile> = all.filter { candidate ->
        candidate.width == profile.width && candidate.height == profile.height
    }

    fun profileForResolution(width: Int, height: Int, fps: Int): VideoProfile? =
        all.firstOrNull { profile ->
            profile.width == width && profile.height == height && profile.fps == fps
        }
}
