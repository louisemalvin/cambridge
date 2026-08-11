#include "decoder.hpp"

#include "platform/posix/posix_compat.hpp"
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

namespace cambridge {
namespace {

constexpr AVCodecID kCodecId = AV_CODEC_ID_H264;
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

} // namespace

Decoder::Decoder(FrameCallback on_frame, EventCallback on_event, MediaPathFailureCallback on_failure)
    : on_frame_(std::move(on_frame)), on_event_(std::move(on_event)), on_failure_(std::move(on_failure))
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
        prepared_ = false;
        active_generation_ = 0;
        prepared_generation_ = 0;
        active_path_ = SessionMediaPath::Unselected;
        queue_.clear();
    }
    condition_.notify_all();
    if (thread_.joinable()) {
        thread_.join();
    }
    std::lock_guard<std::mutex> codec_lock(codec_mutex_);
    close_codec();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        started_ = false;
    }
}

NativeSetupResult Decoder::prepare_native_session(std::uint64_t stream_generation, DecoderConfig config)
{
    {
        std::lock_guard<std::mutex> lock(mutex_);
        prepared_ = false;
        session_active_ = false;
        active_generation_ = 0;
        active_path_ = SessionMediaPath::Unselected;
        queue_.clear();
    }

    std::string error;
    NativeSetupStatus status = NativeSetupStatus::Failed;
    bool opened = false;
    {
        std::lock_guard<std::mutex> codec_lock(codec_mutex_);
        opened = open_codec(config, true, error, status);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (opened) {
            prepared_config_ = std::move(config);
            prepared_generation_ = stream_generation;
            prepared_ = true;
        } else {
            prepared_generation_ = 0;
        }
    }
    if (!opened) {
        return {status, error.empty() ? "native decoder setup failed" : std::move(error)};
    }
    return {NativeSetupStatus::Ready, {}};
}

bool Decoder::prepare_software_session(std::uint64_t stream_generation, DecoderConfig config,
                                       std::string &error)
{
    {
        std::lock_guard<std::mutex> lock(mutex_);
        prepared_ = false;
        session_active_ = false;
        active_generation_ = 0;
        active_path_ = SessionMediaPath::Unselected;
        queue_.clear();
    }

    NativeSetupStatus ignored_status = NativeSetupStatus::Failed;
    bool opened = false;
    {
        std::lock_guard<std::mutex> codec_lock(codec_mutex_);
        opened = open_codec(config, false, error, ignored_status);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (opened) {
            prepared_config_ = std::move(config);
            prepared_generation_ = stream_generation;
            prepared_ = true;
        } else {
            prepared_generation_ = 0;
        }
    }
    return opened;
}

void Decoder::activate_prepared_session(SessionMediaPath selected_path)
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (!prepared_) {
        return;
    }
    active_config_ = prepared_config_;
    active_generation_ = prepared_generation_;
    active_path_ = selected_path;
    failure_reported_generation_ = 0;
    session_active_ = true;
    prepared_ = false;
    current_render_mode_ = selected_path == SessionMediaPath::Native ? RenderMode::Native
                                                                       : RenderMode::CpuNv12;
    condition_.notify_all();
}

void Decoder::discard_prepared_session()
{
    {
        std::lock_guard<std::mutex> lock(mutex_);
        prepared_ = false;
        prepared_generation_ = 0;
        queue_.clear();
    }
    std::lock_guard<std::mutex> codec_lock(codec_mutex_);
    close_codec();
}

void Decoder::end_session()
{
    {
        std::lock_guard<std::mutex> lock(mutex_);
        session_active_ = false;
        prepared_ = false;
        active_generation_ = 0;
        prepared_generation_ = 0;
        active_path_ = SessionMediaPath::Unselected;
        failure_reported_generation_ = 0;
        queue_.clear();
    }
    std::lock_guard<std::mutex> codec_lock(codec_mutex_);
    close_codec();
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

bool Decoder::prepared_session_ready() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return prepared_;
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

enum AVPixelFormat Decoder::choose_pixel_format(AVCodecContext *context,
                                                 const enum AVPixelFormat *formats)
{
    const auto decoder = static_cast<Decoder *>(context->opaque);
    if (decoder && decoder->native_setup_requested_ && decoder->native_adapter_) {
        return decoder->native_adapter_->choose_pixel_format(formats);
    }
    for (const enum AVPixelFormat *format = formats; *format != AV_PIX_FMT_NONE; ++format) {
        if (*format == kSoftwarePixelFormat) {
            return *format;
        }
    }
    return formats[0];
}

bool Decoder::open_codec(const DecoderConfig &config, bool native_requested, std::string &error,
                         NativeSetupStatus &native_status)
{
    close_codec();
    const AVCodec *codec = avcodec_find_decoder(kCodecId);
    if (!codec) {
        error = "libavcodec H.264 decoder is unavailable";
        native_status = native_requested ? NativeSetupStatus::Unsupported : NativeSetupStatus::Failed;
        return false;
    }
    codec_context_ = avcodec_alloc_context3(codec);
    if (!codec_context_) {
        error = "could not allocate the H.264 decoder context";
        native_status = NativeSetupStatus::Failed;
        return false;
    }
    codec_context_->width = static_cast<int>(config.width);
    codec_context_->height = static_cast<int>(config.height);
    codec_context_->thread_count = kDecoderThreadCount;
    codec_context_->opaque = this;
    codec_context_->get_format = &Decoder::choose_pixel_format;
    native_setup_requested_ = native_requested;
    if (native_requested) {
        native_adapter_ = create_native_decoder_adapter();
        if (!native_adapter_) {
            error = "native decoder adapter is unavailable";
            native_status = NativeSetupStatus::Unsupported;
            close_codec();
            return false;
        }
        const NativeSetupResult setup = native_adapter_->configure(
            *codec_context_, NativeDecoderConfig{config.width, config.height, config.drm_device});
        if (setup.status != NativeSetupStatus::Ready) {
            error = setup.reason;
            native_status = setup.status;
            close_codec();
            return false;
        }
    }
    const int open_result = avcodec_open2(codec_context_, codec, nullptr);
    if (open_result < 0) {
        error = "H.264 decoder open failed:" + ffmpeg_error(open_result);
        if (native_requested &&
            (open_result == AVERROR(EINVAL) || open_result == AVERROR(ENODEV) ||
             open_result == AVERROR(ENOSYS))) {
            native_status = NativeSetupStatus::Unsupported;
        } else {
            native_status = NativeSetupStatus::Failed;
        }
        close_codec();
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        decoder_name_ = native_requested && native_adapter_ ? std::string(native_adapter_->decoder_name())
                                                             : "h264/software";
        current_render_mode_ = native_requested ? RenderMode::Native : RenderMode::CpuNv12;
    }
    report("decoder_ready:" + decoder_name() + ":level=" + std::to_string(kDefaultH264Level));
    native_status = NativeSetupStatus::Ready;
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
    if (native_adapter_) {
        native_adapter_->reset();
        native_adapter_.reset();
    }
    native_setup_requested_ = false;
    std::lock_guard<std::mutex> lock(mutex_);
    decoder_name_ = "uninitialized";
    current_render_mode_ = RenderMode::Placeholder;
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
    if (!generation_active(stream_generation)) {
        return;
    }
    const std::uint64_t now = monotonic_time_ns();
    const std::uint64_t maximum_age = static_cast<std::uint64_t>(config.maximum_queue_age_ms) *
                                      kNanosecondsPerMillisecond;
    if (now > access_unit.receive_time_ns && now - access_unit.receive_time_ns > maximum_age) {
        stale_frames_.fetch_add(1);
        flush_codec();
        return;
    }
    if (!codec_context_) {
        fail(stream_generation, MediaPathFailureCode::Decode, "decoder context is unavailable");
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
        fail(stream_generation, MediaPathFailureCode::Decode,
             "decoder_send_failed:" + ffmpeg_error(send_result));
        return;
    }

    while (true) {
        if (!generation_active(stream_generation)) {
            return;
        }
        AVFrame *decoded = av_frame_alloc();
        if (!decoded) {
            decode_failures_.fetch_add(1);
            fail(stream_generation, MediaPathFailureCode::Decode, "could not allocate a decoded frame");
            return;
        }
        const int receive_result = avcodec_receive_frame(codec_context_, decoded);
        if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
            av_frame_free(&decoded);
            break;
        }
        if (receive_result < 0) {
            av_frame_free(&decoded);
            decode_failures_.fetch_add(1);
            fail(stream_generation, MediaPathFailureCode::Decode,
                 "decoder_receive_failed:" + ffmpeg_error(receive_result));
            return;
        }
        publish_frame(decoded, access_unit, stream_generation, config);
        av_frame_free(&decoded);
    }
}

void Decoder::publish_frame(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                            const DecoderConfig &config)
{
    const std::uint64_t decode_time = monotonic_time_ns();
    if (!generation_active(stream_generation)) {
        return;
    }
    if (native_setup_requested_) {
        if (!native_adapter_) {
            decode_failures_.fetch_add(1);
            fail(stream_generation, MediaPathFailureCode::NativeExport,
                 "native decoder adapter is unavailable");
            return;
        }
        std::string export_error;
        NativeFramePtr native_frame = native_adapter_->export_frame(*decoded, export_error);
        if (!native_frame) {
            decode_failures_.fetch_add(1);
            fail(stream_generation, MediaPathFailureCode::NativeExport,
                 export_error.empty() ? "native frame export failed" : export_error);
            return;
        }
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
        frame->render_mode = RenderMode::Native;
        frame->pixel_format = "drm-prime";
        frame->color_range = decoded->color_range == AVCOL_RANGE_JPEG ? "full" : "limited";
        frame->color_space = decoded->colorspace == AVCOL_SPC_BT709 ? "bt709" : "unspecified";
        frame->storage = NativeFrameStorage{std::move(native_frame)};
        on_frame_(std::move(frame));
        frames_decoded_.fetch_add(1);
        return;
    }
    publish_nv12(decoded, access_unit, stream_generation, config);
}

void Decoder::publish_nv12(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                           const DecoderConfig &config)
{
    scaler_ = sws_getCachedContext(scaler_, decoded->width, decoded->height,
                                   static_cast<AVPixelFormat>(decoded->format), decoded->width, decoded->height,
                                   kOutputPixelFormat, SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
    if (!scaler_) {
        decode_failures_.fetch_add(1);
        fail(stream_generation, MediaPathFailureCode::Decode, "NV12 scaler is unavailable");
        return;
    }
    const int buffer_size = av_image_get_buffer_size(kOutputPixelFormat, decoded->width, decoded->height, 1);
    if (buffer_size <= 0) {
        decode_failures_.fetch_add(1);
        fail(stream_generation, MediaPathFailureCode::Decode, "could not size the NV12 output");
        return;
    }
    auto frame = std::make_shared<VideoFrame>();
    CpuNv12Storage storage;
    storage.bytes.resize(static_cast<std::size_t>(buffer_size));
    std::uint8_t *destination_data[4]{};
    int destination_linesize[4]{};
    if (av_image_fill_arrays(destination_data, destination_linesize, storage.bytes.data(), kOutputPixelFormat,
                             decoded->width, decoded->height, 1) < 0) {
        decode_failures_.fetch_add(1);
        fail(stream_generation, MediaPathFailureCode::Decode, "could not prepare the NV12 output");
        return;
    }
    if (sws_scale(scaler_, decoded->data, decoded->linesize, 0, decoded->height, destination_data,
                  destination_linesize) <= 0) {
        decode_failures_.fetch_add(1);
        fail(stream_generation, MediaPathFailureCode::Decode, "NV12 conversion failed");
        return;
    }
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
    frame->render_mode = RenderMode::CpuNv12;
    frame->pixel_format = "nv12";
    frame->color_range = decoded->color_range == AVCOL_RANGE_JPEG ? "full" : "limited";
    frame->color_space = decoded->colorspace == AVCOL_SPC_BT709 ? "bt709" : "unspecified";
    storage.y_stride = static_cast<std::uint32_t>(destination_linesize[0]);
    storage.uv_stride = static_cast<std::uint32_t>(destination_linesize[1]);
    frame->storage = std::move(storage);
    on_frame_(std::move(frame));
    frames_decoded_.fetch_add(1);
}

void Decoder::fail(std::uint64_t stream_generation, MediaPathFailureCode code, const std::string &detail)
{
    bool should_post = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (session_active_ && active_generation_ == stream_generation &&
            failure_reported_generation_ != stream_generation) {
            failure_reported_generation_ = stream_generation;
            session_active_ = false;
            queue_.clear();
            should_post = true;
        }
    }
    if (should_post && on_failure_) {
        on_failure_(stream_generation, code, detail);
    }
}

bool Decoder::generation_active(std::uint64_t stream_generation) const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return session_active_ && active_generation_ == stream_generation;
}

void Decoder::report(const std::string &event)
{
    if (on_event_) {
        on_event_(event);
    }
}

void Decoder::run()
{
    posix::set_current_thread_name("cambridge-decode");
    while (true) {
        AccessUnit access_unit;
        DecoderConfig config;
        std::uint64_t generation = 0;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            condition_.wait(lock, [this] { return stopping_ || (session_active_ && !queue_.empty()); });
            if (stopping_) {
                break;
            }
            if (!session_active_ || queue_.empty()) {
                continue;
            }
            access_unit = std::move(queue_.front());
            queue_.pop_front();
            generation = active_generation_;
            config = active_config_;
        }
        std::lock_guard<std::mutex> codec_lock(codec_mutex_);
        decode_access_unit(access_unit, generation, config);
    }
}

} // namespace cambridge
