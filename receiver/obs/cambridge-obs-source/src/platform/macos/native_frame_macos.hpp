#pragma once

#include "../interfaces/native_frame.hpp"

#include <CoreVideo/CoreVideo.h>

namespace cambridge {

class MacosNativeFrame final : public NativeFrame {
public:
    explicit MacosNativeFrame(CVPixelBufferRef pixel_buffer) : pixel_buffer_(pixel_buffer)
    {
        if (pixel_buffer_) {
            CVPixelBufferRetain(pixel_buffer_);
        }
    }

    ~MacosNativeFrame() override
    {
        if (pixel_buffer_) {
            CVPixelBufferRelease(pixel_buffer_);
        }
    }

    MacosNativeFrame(const MacosNativeFrame &) = delete;
    MacosNativeFrame &operator=(const MacosNativeFrame &) = delete;

    [[nodiscard]] CVPixelBufferRef pixel_buffer() const { return pixel_buffer_; }

private:
    CVPixelBufferRef pixel_buffer_ = nullptr;
};

} // namespace cambridge
