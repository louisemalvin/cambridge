#pragma once

#include "frame.hpp"
#include "rtp.hpp"

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_drm.h>
#include <libswscale/swscale.h>
}

namespace direct_webcam {

struct DecoderConfig {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rotation_degrees = 0;
    std::uint32_t fps = 0;
    std::uint32_t maximum_queue_age_ms = 0;
    std::uint32_t maximum_live_frame_age_ms = 0;
    std::string drm_device;
    bool force_cpu = false;
};

class Decoder {
public:
    using FrameCallback = std::function<void(VideoFramePtr)>;
    using EventCallback = std::function<void(const std::string &)>;

    Decoder(FrameCallback on_frame, EventCallback on_event);
    ~Decoder();

    Decoder(const Decoder &) = delete;
    Decoder &operator=(const Decoder &) = delete;

    void start();
    void stop();
    void begin_session(std::uint64_t stream_generation, DecoderConfig config);
    void end_session();
    void request_cpu_fallback();
    bool submit(AccessUnit access_unit);

    [[nodiscard]] std::string decoder_name() const;
    [[nodiscard]] RenderMode render_mode() const;
    [[nodiscard]] std::uint64_t frames_decoded() const { return frames_decoded_.load(); }
    [[nodiscard]] std::uint64_t decode_failures() const { return decode_failures_.load(); }
    [[nodiscard]] std::uint64_t stale_frames() const { return stale_frames_.load(); }
    [[nodiscard]] std::uint64_t queue_drops() const { return queue_drops_.load(); }
    [[nodiscard]] std::uint64_t hardware_cpu_transfers() const { return hardware_cpu_transfers_.load(); }
    [[nodiscard]] std::size_t queue_occupancy() const;

private:
    static enum AVPixelFormat choose_hardware_format(AVCodecContext *context, const enum AVPixelFormat *formats);
    void run();
    bool configure_codec(const DecoderConfig &config);
    bool open_codec(const DecoderConfig &config, bool try_hardware);
    void close_codec();
    void flush_codec();
    void decode_access_unit(const AccessUnit &access_unit, std::uint64_t stream_generation,
                            const DecoderConfig &config);
    void publish_frame(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                       const DecoderConfig &config);
    void publish_nv12(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                      const DecoderConfig &config, RenderMode mode);
    void report(const std::string &event);

    FrameCallback on_frame_;
    EventCallback on_event_;
    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::deque<AccessUnit> queue_;
    DecoderConfig pending_config_;
    std::uint64_t pending_generation_ = 0;
    std::uint64_t active_generation_ = 0;
    bool session_active_ = false;
    bool reconfigure_requested_ = false;
    bool stopping_ = false;
    bool started_ = false;
    std::thread thread_;

    AVCodecContext *codec_context_ = nullptr;
    AVBufferRef *hardware_device_ = nullptr;
    SwsContext *scaler_ = nullptr;
    bool hardware_active_ = false;
    RenderMode current_render_mode_ = RenderMode::CpuNv12;
    std::string decoder_name_ = "uninitialized";

    std::atomic<std::uint64_t> frames_decoded_{0};
    std::atomic<std::uint64_t> decode_failures_{0};
    std::atomic<std::uint64_t> stale_frames_{0};
    std::atomic<std::uint64_t> queue_drops_{0};
    std::atomic<std::uint64_t> hardware_cpu_transfers_{0};
};

} // namespace direct_webcam
