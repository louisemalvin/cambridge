#pragma once

#include <memory>

namespace cambridge {

class NativeFrame {
public:
    virtual ~NativeFrame() = default;

    NativeFrame(const NativeFrame &) = delete;
    NativeFrame &operator=(const NativeFrame &) = delete;

protected:
    NativeFrame() = default;
};

using NativeFramePtr = std::shared_ptr<const NativeFrame>;

} // namespace cambridge
