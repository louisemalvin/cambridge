#include "../src/live_frame_policy.hpp"

#include <cstdlib>
#include <memory>

namespace {

constexpr std::uint64_t kStreamGeneration = 7;
constexpr std::uint64_t kPublishTimeNs = 1'000;
constexpr std::uint64_t kStaleDeadlineNs = 2'000;
constexpr std::uint32_t kMaximumLiveFrameAgeMs = 250;

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

cambridge::VideoFramePtr make_frame()
{
    auto frame = std::make_shared<cambridge::VideoFrame>();
    frame->stream_generation = kStreamGeneration;
    frame->publish_time_ns = kPublishTimeNs;
    frame->stale_deadline_ns = kStaleDeadlineNs;
    return frame;
}

void test_current_frame_is_presented_before_deadline()
{
    const auto frame = make_frame();
    const auto decision = cambridge::classify_live_frame(
        frame, kStreamGeneration, kStaleDeadlineNs, cambridge::live_frame_recovery_grace_ns(
                                                            kMaximumLiveFrameAgeMs));
    require(decision == cambridge::LiveFramePresentation::CurrentFrame);
}

void test_stale_frame_is_held_during_recovery_grace()
{
    const auto frame = make_frame();
    const auto grace_ns = cambridge::live_frame_recovery_grace_ns(kMaximumLiveFrameAgeMs);
    const auto decision = cambridge::classify_live_frame(
        frame, kStreamGeneration, kStaleDeadlineNs + grace_ns, grace_ns);
    require(decision == cambridge::LiveFramePresentation::RecoveryFrame);
}

void test_stale_frame_becomes_placeholder_after_recovery_grace()
{
    const auto frame = make_frame();
    const auto grace_ns = cambridge::live_frame_recovery_grace_ns(kMaximumLiveFrameAgeMs);
    const auto decision = cambridge::classify_live_frame(
        frame, kStreamGeneration, kStaleDeadlineNs + grace_ns + 1, grace_ns);
    require(decision == cambridge::LiveFramePresentation::Placeholder);
}

void test_invalid_or_old_generation_uses_placeholder()
{
    const auto frame = make_frame();
    const auto grace_ns = cambridge::live_frame_recovery_grace_ns(kMaximumLiveFrameAgeMs);
    require(cambridge::classify_live_frame(
                nullptr, kStreamGeneration, kPublishTimeNs, grace_ns) ==
            cambridge::LiveFramePresentation::Placeholder);
    require(cambridge::classify_live_frame(
                frame, kStreamGeneration + 1, kPublishTimeNs, grace_ns) ==
            cambridge::LiveFramePresentation::Placeholder);
}

} // namespace

int main()
{
    test_current_frame_is_presented_before_deadline();
    test_stale_frame_is_held_during_recovery_grace();
    test_stale_frame_becomes_placeholder_after_recovery_grace();
    test_invalid_or_old_generation_uses_placeholder();
    return 0;
}
