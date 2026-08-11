#pragma once

#include "../interfaces/native_frame.hpp"

#include <CoreVideo/CoreVideo.h>

namespace cambridge {

enum class MacosColorMatrix {
    Bt709,
    Bt601,
    Unspecified,
};

enum class MacosColorRange {
    Full,
    Limited,
    Unspecified,
};

class MacosNativeFrame final : public NativeFrame {
public:
    MacosNativeFrame(CVPixelBufferRef pixel_buffer, MacosColorMatrix color_matrix,
                     MacosColorRange color_range)
        : pixel_buffer_(pixel_buffer), color_matrix_(color_matrix), color_range_(color_range)
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
    [[nodiscard]] MacosColorMatrix color_matrix() const { return color_matrix_; }
    [[nodiscard]] MacosColorRange color_range() const { return color_range_; }

private:
    CVPixelBufferRef pixel_buffer_ = nullptr;
    MacosColorMatrix color_matrix_ = MacosColorMatrix::Unspecified;
    MacosColorRange color_range_ = MacosColorRange::Unspecified;
};

} // namespace cambridge
