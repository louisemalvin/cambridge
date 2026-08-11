#include "../interfaces/native_decoder_adapter.hpp"

#include "native_frame_linux.hpp"

extern "C" {
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_drm.h>
}

#include <memory>

namespace cambridge {
namespace {

constexpr AVPixelFormat kHardwarePixelFormat = AV_PIX_FMT_VAAPI;
constexpr std::uint32_t kUnsetDimension = 0;
constexpr char kNativeDecoderName[] = "h264/VAAPI";

std::string ffmpeg_error(int error_code)
{
    char buffer[AV_ERROR_MAX_STRING_SIZE]{};
    av_strerror(error_code, buffer, sizeof(buffer));
    return buffer;
}

std::shared_ptr<AVFrame> owned_frame(AVFrame *frame)
{
    return std::shared_ptr<AVFrame>(frame, [](AVFrame *value) {
        AVFrame *mutable_value = value;
        av_frame_free(&mutable_value);
    });
}

NativeSetupStatus setup_status_for_device_error(int error_code)
{
    return error_code == AVERROR(ENOMEM) ? NativeSetupStatus::Failed : NativeSetupStatus::Unsupported;
}

class LinuxNativeDecoderAdapter final : public NativeDecoderAdapter {
public:
    ~LinuxNativeDecoderAdapter() override { reset(); }

    NativeSetupResult configure(AVCodecContext &codec_context,
                                const NativeDecoderConfig &config) override
    {
        reset();
        if (config.width == kUnsetDimension || config.height == kUnsetDimension) {
            return {NativeSetupStatus::Failed, "native decoder dimensions are empty"};
        }
        const int device_result = av_hwdevice_ctx_create(
            &hardware_device_, AV_HWDEVICE_TYPE_VAAPI, config.device.c_str(), nullptr, 0);
        if (device_result < 0) {
            return {setup_status_for_device_error(device_result),
                    "VAAPI device setup failed:" + ffmpeg_error(device_result)};
        }
        codec_context.hw_device_ctx = av_buffer_ref(hardware_device_);
        if (!codec_context.hw_device_ctx) {
            reset();
            return {NativeSetupStatus::Failed, "could not retain the VAAPI device context"};
        }
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
            error = "decoded frame is not a configured VAAPI hardware frame";
            return {};
        }
        AVFrame *drm = av_frame_alloc();
        if (!drm) {
            error = "could not allocate a DRM PRIME frame";
            return {};
        }
        drm->format = AV_PIX_FMT_DRM_PRIME;
        const int map_result = av_hwframe_map(drm, const_cast<AVFrame *>(&decoded), AV_HWFRAME_MAP_READ);
        if (map_result < 0) {
            av_frame_free(&drm);
            error = "DRM PRIME export failed:" + ffmpeg_error(map_result);
            return {};
        }
        return std::make_shared<LinuxNativeFrame>(owned_frame(drm));
    }

    [[nodiscard]] std::string_view decoder_name() const override { return kNativeDecoderName; }

    void reset() override
    {
        if (hardware_device_) {
            av_buffer_unref(&hardware_device_);
        }
        configured_ = false;
    }

private:
    AVBufferRef *hardware_device_ = nullptr;
    bool configured_ = false;
};

} // namespace

std::unique_ptr<NativeDecoderAdapter> create_native_decoder_adapter()
{
    return std::make_unique<LinuxNativeDecoderAdapter>();
}

} // namespace cambridge
