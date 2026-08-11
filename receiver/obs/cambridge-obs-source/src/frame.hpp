#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

extern "C" {
#include <libavutil/frame.h>
}

namespace cambridge {

enum class FrameStorageKind {
    CpuNv12,
    Native,
};

enum class RenderMode {
    CpuNv12,
    Native,
    Placeholder,
};

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
    FrameStorageKind storage_kind = FrameStorageKind::CpuNv12;
    RenderMode render_mode = RenderMode::Placeholder;
    std::string pixel_format;
    std::string color_range;
    std::string color_space;
    std::shared_ptr<AVFrame> drm_frame;
    std::vector<std::uint8_t> nv12;
    std::uint32_t nv12_y_stride = 0;
    std::uint32_t nv12_uv_stride = 0;
};

using VideoFramePtr = std::shared_ptr<VideoFrame>;

} // namespace cambridge
