#pragma once

#include "platform/interfaces/native_frame.hpp"
#include "media_path.hpp"

#include <cstdint>
#include <memory>
#include <string>
#include <variant>
#include <vector>

namespace cambridge {

enum class RenderMode {
    CpuNv12,
    Native,
    Placeholder,
};

struct CpuNv12Storage {
    std::vector<std::uint8_t> bytes;
    std::uint32_t y_stride = 0;
    std::uint32_t uv_stride = 0;
};

struct NativeFrameStorage {
    NativeFramePtr frame;
};

using FrameStorage = std::variant<CpuNv12Storage, NativeFrameStorage>;

inline FrameStorageKind frame_storage_kind(const FrameStorage &storage)
{
    return std::holds_alternative<NativeFrameStorage>(storage) ? FrameStorageKind::Native
                                                               : FrameStorageKind::CpuNv12;
}

struct VideoFrame {
    std::uint64_t stream_generation = 0;
    std::uint64_t frame_generation = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rotation_degrees = 0;
    std::uint32_t rtp_timestamp = 0;
    std::uint64_t receive_time_ns = 0;
    std::uint64_t complete_time_ns = 0;
    std::uint64_t decode_time_ns = 0;
    std::uint64_t publish_time_ns = 0;
    std::uint64_t stale_deadline_ns = 0;
    RenderMode render_mode = RenderMode::Placeholder;
    std::string pixel_format;
    std::string color_range;
    std::string color_space;
    FrameStorage storage = CpuNv12Storage{};
};

using VideoFramePtr = std::shared_ptr<VideoFrame>;

} // namespace cambridge
