#pragma once

#include <cstdint>
#include <memory>
#include <mutex>

namespace direct_webcam {

template <typename Frame>
class LatestFrameMailbox {
public:
    using FramePtr = std::shared_ptr<Frame>;

    void publish(FramePtr frame)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        frame->frame_generation = ++generation_;
        if (pending_) {
            ++replaced_count_;
        }
        pending_ = std::move(frame);
    }

    FramePtr acquire()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return pending_;
    }

    void invalidate(std::uint64_t stream_generation)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (pending_ && pending_->stream_generation != stream_generation) {
            pending_.reset();
        }
    }

    void clear()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_.reset();
    }

    [[nodiscard]] std::size_t occupancy() const
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return pending_ ? 1U : 0U;
    }

    [[nodiscard]] std::uint64_t replaced_count() const
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return replaced_count_;
    }

private:
    mutable std::mutex mutex_;
    FramePtr pending_;
    std::uint64_t generation_ = 0;
    std::uint64_t replaced_count_ = 0;
};

} // namespace direct_webcam
