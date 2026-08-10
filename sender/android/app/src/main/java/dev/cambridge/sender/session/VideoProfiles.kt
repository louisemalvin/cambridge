package dev.cambridge.sender.session

import dev.cambridge.sender.model.VideoProfile

object VideoProfiles {
    const val DEFAULT_FPS = 30
    const val HIGH_FPS = 60
    const val MEGABIT = 1_000_000
    private const val DEFAULT_KEYFRAME_INTERVAL_SECONDS = 1
    private const val HIGH_FPS_BITRATE_MULTIPLIER = HIGH_FPS / DEFAULT_FPS

    val PROFILE_720P30: VideoProfile = VideoProfile(
        id = "720p30",
        width = 1_280,
        height = 720,
        fps = DEFAULT_FPS,
        minimumBitrateBps = 2 * MEGABIT,
        defaultBitrateBps = 4 * MEGABIT,
        maximumBitrateBps = 8 * MEGABIT,
        bitrateStepBps = MEGABIT,
        keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    )

    val PROFILE_1080P30: VideoProfile = VideoProfile(
        id = "1080p30",
        width = 1_920,
        height = 1_080,
        fps = DEFAULT_FPS,
        minimumBitrateBps = 4 * MEGABIT,
        defaultBitrateBps = 8 * MEGABIT,
        maximumBitrateBps = 16 * MEGABIT,
        bitrateStepBps = MEGABIT,
        keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    )

    val PROFILE_1080P60: VideoProfile = PROFILE_1080P30.copy(
        id = "1080p60",
        fps = HIGH_FPS,
        minimumBitrateBps = PROFILE_1080P30.minimumBitrateBps * HIGH_FPS_BITRATE_MULTIPLIER,
        defaultBitrateBps = PROFILE_1080P30.defaultBitrateBps * HIGH_FPS_BITRATE_MULTIPLIER,
        maximumBitrateBps = PROFILE_1080P30.maximumBitrateBps * HIGH_FPS_BITRATE_MULTIPLIER,
    )

    val PROFILE_2K30: VideoProfile = VideoProfile(
        id = "2k30",
        width = 2_560,
        height = 1_440,
        fps = DEFAULT_FPS,
        minimumBitrateBps = 9 * MEGABIT,
        defaultBitrateBps = 18 * MEGABIT,
        maximumBitrateBps = 36 * MEGABIT,
        bitrateStepBps = MEGABIT,
        keyframeIntervalSeconds = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    )

    val PROFILE_2K60: VideoProfile = PROFILE_2K30.copy(
        id = "2k60",
        fps = HIGH_FPS,
        minimumBitrateBps = PROFILE_2K30.minimumBitrateBps * HIGH_FPS_BITRATE_MULTIPLIER,
        defaultBitrateBps = PROFILE_2K30.defaultBitrateBps * HIGH_FPS_BITRATE_MULTIPLIER,
        maximumBitrateBps = PROFILE_2K30.maximumBitrateBps * HIGH_FPS_BITRATE_MULTIPLIER,
    )

    val normal: List<VideoProfile> = listOf(
        PROFILE_1080P30,
        PROFILE_1080P60,
        PROFILE_2K30,
        PROFILE_2K60,
    )

    /** Includes the named AVD smoke profile for test-only intent injection. */
    val all: List<VideoProfile> = listOf(PROFILE_720P30) + normal

    /** One representative mode per resolution for the setup screen. */
    val qualityProfiles: List<VideoProfile> = normal.distinctBy { profile ->
        profile.width to profile.height
    }

    val default: VideoProfile = PROFILE_2K30

    fun modeFor(width: Int, height: Int, fps: Int): VideoProfile? =
        all.firstOrNull { profile ->
            profile.width == width && profile.height == height && profile.fps == fps
        }

    fun modeId(width: Int, height: Int, fps: Int): String? = modeFor(width, height, fps)?.id

    fun profilesForResolution(profile: VideoProfile): List<VideoProfile> = all.filter { candidate ->
        candidate.width == profile.width && candidate.height == profile.height
    }

    fun profileForResolution(width: Int, height: Int, fps: Int): VideoProfile? = modeFor(width, height, fps)
}
