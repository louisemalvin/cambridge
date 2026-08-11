#include "../src/frame.hpp"
#include "../src/latest_frame_mailbox.hpp"

#include <cstdlib>
#include <memory>

namespace {

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

cambridge::VideoFramePtr frame_for_generation(std::uint64_t generation)
{
    auto frame = std::make_shared<cambridge::VideoFrame>();
    frame->stream_generation = generation;
    return frame;
}

void test_publish_replaces_the_pending_frame()
{
    cambridge::LatestFrameMailbox<cambridge::VideoFrame> mailbox;
    auto first = frame_for_generation(7);
    auto replacement = frame_for_generation(7);

    mailbox.publish(first);
    require(mailbox.acquire() == first);
    require(mailbox.occupancy() == 1U);

    mailbox.publish(replacement);
    require(mailbox.acquire() == replacement);
    require(mailbox.replaced_count() == 1U);
}

void test_clear_removes_the_pending_frame()
{
    cambridge::LatestFrameMailbox<cambridge::VideoFrame> mailbox;
    mailbox.publish(frame_for_generation(11));
    mailbox.clear();

    require(!mailbox.acquire());
    require(mailbox.occupancy() == 0U);
}

void test_invalidate_keeps_matching_generation_and_clears_old_generation()
{
    cambridge::LatestFrameMailbox<cambridge::VideoFrame> mailbox;
    mailbox.publish(frame_for_generation(13));
    mailbox.invalidate(13);
    require(static_cast<bool>(mailbox.acquire()));

    mailbox.invalidate(14);
    require(!mailbox.acquire());

    mailbox.publish(frame_for_generation(15));
    mailbox.invalidate(14);
    require(!mailbox.acquire());
}

} // namespace

int main()
{
    test_publish_replaces_the_pending_frame();
    test_clear_removes_the_pending_frame();
    test_invalidate_keeps_matching_generation_and_clears_old_generation();
    return 0;
}
