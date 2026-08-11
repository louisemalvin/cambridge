#pragma once

#include "../interfaces/native_frame.hpp"

#include <memory>

extern "C" {
#include <libavutil/frame.h>
}

namespace cambridge {

class LinuxNativeFrame final : public NativeFrame {
public:
    explicit LinuxNativeFrame(std::shared_ptr<AVFrame> frame) : frame_(std::move(frame)) {}

    [[nodiscard]] const AVFrame *drm_frame() const { return frame_.get(); }

private:
    std::shared_ptr<AVFrame> frame_;
};

} // namespace cambridge
