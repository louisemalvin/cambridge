#pragma once

#include "../../media_path.hpp"
#include "native_frame.hpp"

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/pixfmt.h>
}

namespace cambridge {

struct NativeDecoderConfig {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::string device;
};

class NativeDecoderAdapter {
public:
    virtual ~NativeDecoderAdapter() = default;

    virtual NativeSetupResult configure(AVCodecContext &codec_context,
                                         const NativeDecoderConfig &config) = 0;
    virtual AVPixelFormat choose_pixel_format(const AVPixelFormat *candidates) const = 0;
    virtual NativeFramePtr export_frame(const AVFrame &decoded, std::string &error) = 0;
    virtual std::string_view decoder_name() const = 0;
    virtual void reset() = 0;
};

std::unique_ptr<NativeDecoderAdapter> create_native_decoder_adapter();

} // namespace cambridge
