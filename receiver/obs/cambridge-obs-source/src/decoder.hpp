#pragma once

#include "frame.hpp"
#include "media_path.hpp"
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

namespace cambridge {

struct DecoderConfig {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rotation_degrees = 0;
    std::uint32_t fps = 0;
    std::uint32_t maximum_queue_age_ms = 0;
    std::uint32_t maximum_live_frame_age_ms = 0;
    std::string drm_device;
};

class Decoder {
public:
    using FrameCallback = std::function<void(VideoFramePtr)>;
    using EventCallback = std::function<void(const std::string &)>;

    Decoder(FrameCallback on_frame, EventCallback on_event, MediaPathFailureCallback on_failure);
    ~Decoder();

    Decoder(const Decoder &) = delete;
    Decoder &operator=(const Decoder &) = delete;

    void start();
    void stop();
    NativeSetupResult prepare_native_session(std::uint64_t stream_generation, DecoderConfig config);
    bool prepare_software_session(std::uint64_t stream_generation, DecoderConfig config, std::string &error);
    void activate_prepared_session(SessionMediaPath selected_path);
    void discard_prepared_session();
    void end_session();
    bool submit(AccessUnit access_unit);

    [[nodiscard]] bool prepared_session_ready() const;
    [[nodiscard]] std::string decoder_name() const;
    [[nodiscard]] RenderMode render_mode() const;
    [[nodiscard]] std::uint64_t frames_decoded() const { return frames_decoded_.load(); }
    [[nodiscard]] std::uint64_t decode_failures() const { return decode_failures_.load(); }
    [[nodiscard]] std::uint64_t stale_frames() const { return stale_frames_.load(); }
    [[nodiscard]] std::uint64_t queue_drops() const { return queue_drops_.load(); }
    [[nodiscard]] std::size_t queue_occupancy() const;

private:
    static enum AVPixelFormat choose_hardware_format(AVCodecContext *context,
                                                      const enum AVPixelFormat *formats);
    void run();
    bool open_codec(const DecoderConfig &config, bool native_requested, std::string &error,
                    NativeSetupStatus &native_status);
    void close_codec();
    void flush_codec();
    void decode_access_unit(const AccessUnit &access_unit, std::uint64_t stream_generation,
                            const DecoderConfig &config);
    void publish_frame(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                       const DecoderConfig &config);
    void publish_nv12(AVFrame *decoded, const AccessUnit &access_unit, std::uint64_t stream_generation,
                      const DecoderConfig &config);
    void fail(std::uint64_t stream_generation, MediaPathFailureCode code, const std::string &detail);
    [[nodiscard]] bool generation_active(std::uint64_t stream_generation) const;
    void report(const std::string &event);

    FrameCallback on_frame_;
    EventCallback on_event_;
    MediaPathFailureCallback on_failure_;
    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::deque<AccessUnit> queue_;
    DecoderConfig prepared_config_;
    DecoderConfig active_config_;
    std::uint64_t prepared_generation_ = 0;
    std::uint64_t active_generation_ = 0;
    std::uint64_t failure_reported_generation_ = 0;
    SessionMediaPath active_path_ = SessionMediaPath::Unselected;
    bool prepared_ = false;
    bool session_active_ = false;
    bool stopping_ = false;
    bool started_ = false;
    std::thread thread_;

    std::mutex codec_mutex_;
    AVCodecContext *codec_context_ = nullptr;
    AVBufferRef *hardware_device_ = nullptr;
    SwsContext *scaler_ = nullptr;
    bool native_setup_requested_ = false;
    bool hardware_active_ = false;
    RenderMode current_render_mode_ = RenderMode::Placeholder;
    std::string decoder_name_ = "uninitialized";

    std::atomic<std::uint64_t> frames_decoded_{0};
    std::atomic<std::uint64_t> decode_failures_{0};
    std::atomic<std::uint64_t> stale_frames_{0};
    std::atomic<std::uint64_t> queue_drops_{0};
};

} // namespace cambridge
