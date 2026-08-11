#include "../interfaces/native_decoder_adapter.hpp"

#include "native_frame_macos.hpp"

extern "C" {
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
}

#import <CoreVideo/CoreVideo.h>
#import <IOSurface/IOSurface.h>

#include <memory>
#include <cerrno>

namespace cambridge {
namespace {

constexpr AVPixelFormat kHardwarePixelFormat = AV_PIX_FMT_VIDEOTOOLBOX;
constexpr std::uint32_t kUnsetDimension = 0;
constexpr int kVideoToolboxPixelBufferDataIndex = 3;
constexpr int kNoHardwareDeviceFlags = 0;
constexpr char kNativeDecoderName[] = "h264/VideoToolbox";

std::string ffmpeg_error(int error_code)
{
    char buffer[AV_ERROR_MAX_STRING_SIZE]{};
    av_strerror(error_code, buffer, sizeof(buffer));
    return buffer;
}

NativeSetupStatus setup_status_for_device_error(int error_code)
{
    if (error_code == AVERROR(ENOMEM)) {
        return NativeSetupStatus::Failed;
    }
    if (error_code == AVERROR(ENOSYS) || error_code == AVERROR(ENODEV)) {
        return NativeSetupStatus::Unsupported;
    }
    return NativeSetupStatus::Failed;
}

bool is_supported_pixel_format(OSType pixel_format)
{
    return pixel_format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
           pixel_format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;
}

class MacosNativeDecoderAdapter final : public NativeDecoderAdapter {
public:
    ~MacosNativeDecoderAdapter() override { reset(); }

    NativeSetupResult configure(AVCodecContext &codec_context,
                                const NativeDecoderConfig &config) override
    {
        reset();
        if (config.width == kUnsetDimension || config.height == kUnsetDimension) {
            return {NativeSetupStatus::Failed, "native decoder dimensions are empty"};
        }
        const int device_result = av_hwdevice_ctx_create(
            &hardware_device_, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, nullptr, nullptr,
            kNoHardwareDeviceFlags);
        if (device_result < 0) {
            return {setup_status_for_device_error(device_result),
                    "VideoToolbox device setup failed:" + ffmpeg_error(device_result)};
        }
        codec_context.hw_device_ctx = av_buffer_ref(hardware_device_);
        if (!codec_context.hw_device_ctx) {
            reset();
            return {NativeSetupStatus::Failed,
                    "could not retain the VideoToolbox device context"};
        }
        configured_width_ = config.width;
        configured_height_ = config.height;
        configured_ = true;
        return {NativeSetupStatus::Ready, {}};
    }

    AVPixelFormat choose_pixel_format(const AVPixelFormat *candidates) const override
    {
        if (!configured_ || !candidates) {
            return AV_PIX_FMT_NONE;
        }
        for (const AVPixelFormat *candidate = candidates; *candidate != AV_PIX_FMT_NONE; ++candidate) {
            if (*candidate == kHardwarePixelFormat) {
                return *candidate;
            }
        }
        return AV_PIX_FMT_NONE;
    }

    NativeFramePtr export_frame(const AVFrame &decoded, std::string &error) override
    {
        if (!configured_ || decoded.format != kHardwarePixelFormat) {
            error = "decoded frame is not a configured VideoToolbox hardware frame";
            return {};
        }
        const auto *pixel_buffer = reinterpret_cast<CVPixelBufferRef>(
            decoded.data[kVideoToolboxPixelBufferDataIndex]);
        if (!pixel_buffer) {
            error = "VideoToolbox frame does not contain a CVPixelBuffer";
            return {};
        }
        if (!is_supported_pixel_format(CVPixelBufferGetPixelFormatType(pixel_buffer))) {
            error = "VideoToolbox frame is not bi-planar 4:2:0";
            return {};
        }
        if (CVPixelBufferGetWidth(pixel_buffer) != configured_width_ ||
            CVPixelBufferGetHeight(pixel_buffer) != configured_height_) {
            error = "VideoToolbox frame dimensions do not match the configured session";
            return {};
        }
        if (!CVPixelBufferGetIOSurface(pixel_buffer)) {
            error = "VideoToolbox frame is not IOSurface-backed";
            return {};
        }
        return std::make_shared<MacosNativeFrame>(pixel_buffer);
    }

    [[nodiscard]] std::string_view decoder_name() const override { return kNativeDecoderName; }

    void reset() override
    {
        if (hardware_device_) {
            av_buffer_unref(&hardware_device_);
        }
        configured_width_ = kUnsetDimension;
        configured_height_ = kUnsetDimension;
        configured_ = false;
    }

private:
    AVBufferRef *hardware_device_ = nullptr;
    std::uint32_t configured_width_ = kUnsetDimension;
    std::uint32_t configured_height_ = kUnsetDimension;
    bool configured_ = false;
};

} // namespace

std::unique_ptr<NativeDecoderAdapter> create_native_decoder_adapter()
{
    return std::make_unique<MacosNativeDecoderAdapter>();
}

} // namespace cambridge
