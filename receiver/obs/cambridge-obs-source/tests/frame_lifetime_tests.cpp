#include "../src/frame.hpp"
#include "../src/latest_frame_mailbox.hpp"

#include <cstdlib>
#include <memory>

namespace {

class TrackedNativeFrame final : public cambridge::NativeFrame {
public:
    explicit TrackedNativeFrame(std::size_t &destruction_count) : destruction_count_(destruction_count) {}

    ~TrackedNativeFrame() override { ++destruction_count_; }

private:
    std::size_t &destruction_count_;
};

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

cambridge::VideoFramePtr frame_with_native_storage(std::size_t &destruction_count)
{
    auto frame = std::make_shared<cambridge::VideoFrame>();
    frame->storage = cambridge::NativeFrameStorage{
        std::make_shared<TrackedNativeFrame>(destruction_count)};
    return frame;
}

void test_mailbox_replacement_releases_replaced_native_frame()
{
    std::size_t first_destructions = 0;
    std::size_t second_destructions = 0;
    cambridge::LatestFrameMailbox<cambridge::VideoFrame> mailbox;
    auto first = frame_with_native_storage(first_destructions);
    mailbox.publish(first);
    first.reset();
    require(first_destructions == 0);

    auto second = frame_with_native_storage(second_destructions);
    mailbox.publish(second);
    require(first_destructions == 1);
    second.reset();
    require(second_destructions == 0);

    mailbox.clear();
    require(second_destructions == 1);
}

void test_frame_storage_is_explicitly_typed()
{
    std::size_t destructions = 0;
    auto frame = frame_with_native_storage(destructions);
    require(std::holds_alternative<cambridge::NativeFrameStorage>(frame->storage));
    frame->storage = cambridge::CpuNv12Storage{};
    require(destructions == 1);
    require(std::holds_alternative<cambridge::CpuNv12Storage>(frame->storage));
}

} // namespace

int main()
{
    test_mailbox_replacement_releases_replaced_native_frame();
    test_frame_storage_is_explicitly_typed();
    return 0;
}
