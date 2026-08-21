#pragma once

#include "frame.hpp"

#include <cstdint>

namespace cambridge {

enum class LiveFramePresentation {
    Placeholder,
    CurrentFrame,
    RecoveryFrame,
};

inline constexpr std::uint64_t kNanosecondsPerMillisecond = 1'000'000ULL;

inline std::uint64_t live_frame_recovery_grace_ns(std::uint32_t maximum_live_frame_age_ms)
{
    return static_cast<std::uint64_t>(maximum_live_frame_age_ms) * kNanosecondsPerMillisecond;
}

inline LiveFramePresentation classify_live_frame(const VideoFramePtr &frame,
                                                 std::uint64_t active_stream_generation,
                                                 std::uint64_t now_ns,
                                                 std::uint64_t recovery_grace_ns)
{
    if (!frame || frame->stream_generation != active_stream_generation ||
        frame->publish_time_ns == 0 || frame->stale_deadline_ns == 0) {
        return LiveFramePresentation::Placeholder;
    }
    if (now_ns <= frame->stale_deadline_ns) {
        return LiveFramePresentation::CurrentFrame;
    }
    if (recovery_grace_ns > 0 &&
        now_ns - frame->stale_deadline_ns <= recovery_grace_ns) {
        return LiveFramePresentation::RecoveryFrame;
    }
    return LiveFramePresentation::Placeholder;
}

} // namespace cambridge
