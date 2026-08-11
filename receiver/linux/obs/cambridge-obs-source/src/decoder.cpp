#include "decoder.hpp"

#include "protocol_contract.generated.hpp"

extern "C" {
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
}

#include <algorithm>
#include <chrono>
#include <cerrno>
#include <cstring>
#include <limits>
#include <pthread.h>

namespace cambridge {
namespace {

constexpr AVCodecID kCodecId = AV_CODEC_ID_H264;
constexpr AVPixelFormat kHardwarePixelFormat = AV_PIX_FMT_VAAPI;
constexpr AVPixelFormat kSoftwarePixelFormat = AV_PIX_FMT_YUV420P;
constexpr AVPixelFormat kOutputPixelFormat = AV_PIX_FMT_NV12;
constexpr int kDecoderThreadCount = 1;
constexpr std::uint64_t kNanosecondsPerMillisecond = 1'000'000ULL;
constexpr std::uint64_t kNanosecondsPerSecond = 1'000'000'000ULL;
constexpr std::uint64_t kMicrosecondsPerSecond = 1'000'000ULL;
constexpr std::uint32_t kRtpClockRate = contract::kRtpClockRateHz;
constexpr std::uint32_t kDefaultH264Level = 51;

std::uint64_t monotonic_time_ns()
{
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<std::uint64_t>(time.tv_sec) * kNanosecondsPerSecond +
           static_cast<std::uint64_t>(time.tv_nsec);
}

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

} // namespace

Decoder::Decoder(FrameCallback on_frame, EventCallback on_event)
    : on_frame_(std::move(on_frame)), on_event_(std::move(on_event))
{
}

Decoder::~Decoder()
{
    stop();
}

void Decoder::start()
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (started_) {
        return;
    }
    stopping_ = false;
    started_ = true;
    thread_ = std::thread(&Decoder::run, this);
}

void Decoder::stop()
{
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopping_ = true;
        session_active_ = false;
        queue_.clear();
    }
    condition_.notify_all();
    if (thread_.joinable()) {
        thread_.join();
    }
    std::lock_guard<std::mutex> lock(mutex_);
    close_codec();
    started_ = false;
}

void Decoder::begin_session(std::uint64_t stream_generation, DecoderConfig config)
{
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_generation_ = stream_generation;
        pending_config_ = std::move(config);
        session_active_ = true;
        reconfigure_requested_ = true;
        queue_.clear();
    }
    condition_.notify_all();
}

void Decoder::end_session()
{
    std::lock_guard<std::mutex> lock(mutex_);
    session_active_ = false;
    queue_.clear();
    reconfigure_requested_ = false;
}

void Decoder::request_cpu_fallback()
{
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!session_active_ || pending_config_.force_cpu) {
            return;
        }
        pending_config_.force_cpu = true;
        session_active_ = false;
        reconfigure_requested_ = true;
        queue_.clear();
    }
    condition_.notify_all();
    report("cpu_fallback_requested_after_dma_buf_import_failure");
}

bool Decoder::submit(AccessUnit access_unit)
{
    if (access_unit.annex_b.empty() || access_unit.annex_b.size() > contract::kMaximumAccessUnitBytes) {
        queue_drops_.fetch_add(1);
        return false;
    }
    bool accepted = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (session_active_ && !stopping_) {
            if (queue_.size() >= contract::kMaximumInFlightAccessUnits) {
                queue_.pop_front();
                queue_drops_.fetch_add(1);
            }
            queue_.push_back(std::move(access_unit));
            accepted = true;
        }
    }
    condition_.notify_one();
    return accepted;
}

std::string Decoder::decoder_name() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return decoder_name_;
}

RenderMode Decoder::render_mode() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return current_render_mode_;
}

std::size_t Decoder::queue_occupancy() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return queue_.size();
}

enum AVPixelFormat Decoder::choose_hardware_format(AVCodecContext *context, const enum AVPixelFormat *formats)
{
    const auto decoder = static_cast<Decoder *>(context->opaque);
    for (const enum AVPixelFormat *format = formats; *format != AV_PIX_FMT_NONE; ++format) {
        if (decoder && decoder->hardware_active_ && *format == kHardwarePixelFormat) {
            return *format;
        }
    }
    for (const enum AVPixelFormat *format = formats; *format != AV_PIX_FMT_NONE; ++format) {
        if (*format == kSoftwarePixelFormat) {
            return *format;
        }
    }
    return formats[0];
}

bool Decoder::configure_codec(const DecoderConfig &config)
{
    if (open_codec(config, !config.force_cpu)) {
        return true;
    }
    if (!config.force_cpu) {
        report("hardware_decode_unavailable_using_cpu_fallback");
        return open_codec(config, false);
    }
    return false;
}

bool Decoder::open_codec(const DecoderConfig &config, bool try_hardware)
{
    close_codec();
    const AVCodec *codec = avcodec_find_decoder(kCodecId);
    if (!codec) {
        report("libavcodec_h264_decoder_missing");
        return false;
    }
    codec_context_ = avcodec_alloc_context3(codec);
    if (!codec_context_) {
        return false;
    }
    codec_context_->width = static_cast<int>(config.width);
    codec_context_->height = static_cast<int>(config.height);
    codec_context_->thread_count = kDecoderThreadCount;
    codec_context_->opaque = this;
    codec_context_->get_format = &Decoder::choose_hardware_format;
    hardware_active_ = false;
    if (try_hardware) {
        const int device_result = av_hwdevice_ctx_create(
            &hardware_device_, AV_HWDEVICE_TYPE_VAAPI, config.drm_device.c_str(), nullptr, 0);
        if (device_result < 0) {
            report("vaapi_device_open_failed:" + ffmpeg_error(device_result));
            hardware_device_ = nullptr;
        } else {
            codec_context_->hw_device_ctx = av_buffer_ref(hardware_device_);
            hardware_active_ = codec_context_->hw_device_ctx != nullptr;
        }
    }
    const int open_result = avcodec_open2(codec_context_, codec, nullptr);
    if (open_result < 0) {
        report("h264_decoder_open_failed:" + ffmpeg_error(open_result));
        close_codec();
        return false;
    }
    decoder_name_ = hardware_active_ ? "h264/VAAPI" : "h264/software";
    current_render_mode_ = hardware_active_ ? RenderMode::HardwareDmaBuf : RenderMode::CpuNv12;
    report("decoder_ready:" + decoder_name_ + ":level=" + std::to_string(kDefaultH264Level));
    return true;
}

void Decoder::close_codec()
{
    if (scaler_) {
        sws_freeContext(scaler_);
        scaler_ = nullptr;
    }
    if (codec_context_) {
        avcodec_free_context(&codec_context_);
    }
    if (hardware_device_) {
        av_buffer_unref(&hardware_device_);
    }
    hardware_active_ = false;
    decoder_name_ = "uninitialized";
    current_render_mode_ = RenderMode::CpuNv12;
}

void Decoder::flush_codec()
{
    if (codec_context_) {
        avcodec_flush_buffers(codec_context_);
    }
}

void Decoder::decode_access_unit(const AccessUnit &access_unit, std::uint64_t stream_generation,
                                 const DecoderConfig &config)
{
    const std::uint64_t now = monotonic_time_ns();
    const std::uint64_t maximum_age = static_cast<std::uint64_t>(config.maximum_queue_age_ms) *
                                      kNanosecondsPerMillisecond;
    if (now > access_unit.receive_time_ns && now - access_unit.receive_time_ns > maximum_age) {
        stale_frames_.fetch_add(1);
        flush_codec();
        return;
    }
    if (!codec_context_) {
        return;
    }
    const auto packet_pts = static_cast<int64_t>((static_cast<std::uint64_t>(access_unit.rtp_timestamp) *
                                                   kMicrosecondsPerSecond) /
                                                  kRtpClockRate);
    auto send_packet = [this, packet_pts](const std::uint8_t *data, std::size_t size) {
        if (!data || size == 0 || size > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
            return AVERROR(EINVAL);
        }
        AVPacket *packet = av_packet_alloc();
        if (!packet) {
            return AVERROR(ENOMEM);
        }
        const int allocation_result = av_new_packet(packet, static_cast<int>(size));
        if (allocation_result < 0) {
            av_packet_free(&packet);
            return allocation_result;
        }
        std::memcpy(packet->data, data, size);
        packet->pts = packet_pts;
        packet->dts = packet_pts;
        const int send_result = avcodec_send_packet(codec_context_, packet);
        av_packet_free(&packet);
        return send_result;
    };

    const int send_result = send_packet(access_unit.annex_b.data(), access_unit.annex_b.size());
    if (send_result < 0) {
        decode_failures_.fetch_add(1);
        report("decoder_send_failed:" + ffmpeg_error(send_result));
        flush_codec();
        return;
    }

    while (true) {
        AVFrame *decoded = av_frame_alloc();
        if (!decoded) {
            break;
        }
        const int receive_result = avcodec_receive_frame(codec_context_, decoded);
        if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
            av_frame_free(&decoded);
            break;
        }
        if (receive_result < 0) {
            av_frame_free(&decoded);
            decode_failures_.fetch_add(1);
            report("decoder_receive_failed:" + ffmpeg_error(receive_result));
            flush_codec();
            break;
        }
        publish_frame(decoded, access_unit, stream_generation, config);
        av_frame_free(&decoded);
    }
}

void Decoder::publish_frame(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                            const DecoderConfig &config)
{
    const std::uint64_t decode_time = monotonic_time_ns();
    if (hardware_active_ && decoded->format == kHardwarePixelFormat) {
        AVFrame *drm = av_frame_alloc();
        if (drm) {
            drm->format = AV_PIX_FMT_DRM_PRIME;
            const int map_result = av_hwframe_map(drm, decoded, AV_HWFRAME_MAP_READ);
            if (map_result >= 0) {
                auto frame = std::make_shared<VideoFrame>();
                frame->stream_generation = stream_generation;
                frame->width = static_cast<std::uint32_t>(decoded->width);
                frame->height = static_cast<std::uint32_t>(decoded->height);
                frame->rotation_degrees = config.rotation_degrees;
                frame->rtp_timestamp = access_unit.rtp_timestamp;
                frame->receive_time_ns = access_unit.receive_time_ns;
                frame->decode_time_ns = decode_time;
                frame->complete_time_ns = decode_time;
                frame->publish_time_ns = decode_time;
                frame->stale_deadline_ns = decode_time +
                                           static_cast<std::uint64_t>(config.maximum_live_frame_age_ms) *
                                               kNanosecondsPerMillisecond;
                frame->render_mode = RenderMode::HardwareDmaBuf;
                frame->pixel_format = "drm-prime";
                frame->color_range = decoded->color_range == AVCOL_RANGE_JPEG ? "full" : "limited";
                frame->color_space = decoded->colorspace == AVCOL_SPC_BT709 ? "bt709" : "unspecified";
                frame->drm_frame = owned_frame(drm);
                on_frame_(std::move(frame));
                frames_decoded_.fetch_add(1);
                current_render_mode_ = RenderMode::HardwareDmaBuf;
                return;
            }
            av_frame_free(&drm);
            report("drm_prime_export_failed_using_cpu_transfer:" + ffmpeg_error(map_result));
        }
        AVFrame *transferred = av_frame_alloc();
        if (transferred && av_hwframe_transfer_data(transferred, decoded, 0) >= 0) {
            hardware_cpu_transfers_.fetch_add(1);
            publish_nv12(transferred, access_unit, stream_generation, config, RenderMode::HardwareCpuTransfer);
            av_frame_free(&transferred);
            return;
        }
        av_frame_free(&transferred);
        decode_failures_.fetch_add(1);
        return;
    }
    publish_nv12(decoded, access_unit, stream_generation, config, RenderMode::CpuNv12);
}

void Decoder::publish_nv12(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                           const DecoderConfig &config, RenderMode mode)
{
    scaler_ = sws_getCachedContext(scaler_, decoded->width, decoded->height,
                                   static_cast<AVPixelFormat>(decoded->format), decoded->width, decoded->height,
                                   kOutputPixelFormat, SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
    if (!scaler_) {
        decode_failures_.fetch_add(1);
        report("nv12_scaler_unavailable");
        return;
    }
    const int buffer_size = av_image_get_buffer_size(kOutputPixelFormat, decoded->width, decoded->height, 1);
    if (buffer_size <= 0) {
        return;
    }
    auto frame = std::make_shared<VideoFrame>();
    frame->nv12.resize(static_cast<std::size_t>(buffer_size));
    std::uint8_t *destination_data[4]{};
    int destination_linesize[4]{};
    if (av_image_fill_arrays(destination_data, destination_linesize, frame->nv12.data(), kOutputPixelFormat,
                             decoded->width, decoded->height, 1) < 0) {
        return;
    }
    sws_scale(scaler_, decoded->data, decoded->linesize, 0, decoded->height, destination_data, destination_linesize);
    const std::uint64_t now = monotonic_time_ns();
    frame->stream_generation = stream_generation;
    frame->width = static_cast<std::uint32_t>(decoded->width);
    frame->height = static_cast<std::uint32_t>(decoded->height);
    frame->rotation_degrees = config.rotation_degrees;
    frame->rtp_timestamp = access_unit.rtp_timestamp;
    frame->receive_time_ns = access_unit.receive_time_ns;
    frame->decode_time_ns = now;
    frame->complete_time_ns = now;
    frame->publish_time_ns = now;
    frame->stale_deadline_ns = now +
                               static_cast<std::uint64_t>(config.maximum_live_frame_age_ms) *
                                   kNanosecondsPerMillisecond;
    frame->render_mode = mode;
    frame->pixel_format = "nv12";
    frame->color_range = decoded->color_range == AVCOL_RANGE_JPEG ? "full" : "limited";
    frame->color_space = decoded->colorspace == AVCOL_SPC_BT709 ? "bt709" : "unspecified";
    frame->nv12_y_stride = static_cast<std::uint32_t>(destination_linesize[0]);
    frame->nv12_uv_stride = static_cast<std::uint32_t>(destination_linesize[1]);
    on_frame_(std::move(frame));
    frames_decoded_.fetch_add(1);
    current_render_mode_ = mode;
}

void Decoder::report(const std::string &event)
{
    if (on_event_) {
        on_event_(event);
    }
}

void Decoder::run()
{
    pthread_setname_np(pthread_self(), "cambridge-decode");
    while (true) {
        AccessUnit access_unit;
        DecoderConfig config;
        std::uint64_t generation = 0;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            condition_.wait(lock, [this] {
                return stopping_ || reconfigure_requested_ || (session_active_ && !queue_.empty());
            });
            if (stopping_) {
                break;
            }
            if (reconfigure_requested_) {
                generation = pending_generation_;
                config = pending_config_;
                reconfigure_requested_ = false;
                active_generation_ = generation;
                session_active_ = true;
                lock.unlock();
                if (!configure_codec(config)) {
                    report("decoder_setup_failed");
                    std::lock_guard<std::mutex> relock(mutex_);
                    session_active_ = false;
                    continue;
                }
                continue;
            }
            if (!session_active_ || queue_.empty()) {
                continue;
            }
            access_unit = std::move(queue_.front());
            queue_.pop_front();
            generation = active_generation_;
            config = pending_config_;
        }
        decode_access_unit(access_unit, generation, config);
    }
    std::lock_guard<std::mutex> lock(mutex_);
    close_codec();
}

} // namespace cambridge
