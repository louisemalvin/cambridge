#pragma once

#include "control_server.hpp"
#include "decoder.hpp"
#include "discovery_advertiser.hpp"
#include "latest_frame_mailbox.hpp"
#include "media_receiver.hpp"
#include "protocol_contract.hpp"
#include "renderer.hpp"

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

extern "C" {
#include <obs/obs-module.h>
}

namespace cambridge {

struct SourceConfig {
    std::uint16_t control_port = static_cast<std::uint16_t>(contract::kDefaultControlPort);
    std::uint16_t media_port = static_cast<std::uint16_t>(contract::kDefaultMediaPort);
    std::uint32_t maximum_long_edge = contract::kDefaultMaximumLongEdge;
    std::uint32_t maximum_short_edge = contract::kDefaultMaximumShortEdge;
    std::uint32_t reorder_deadline_ms = contract::kDefaultReorderDeadlineMs;
    std::uint32_t maximum_decoder_queue_age_ms = contract::kDefaultMaximumDecoderQueueAgeMs;
    std::uint32_t maximum_live_frame_age_ms = contract::kDefaultMaximumLiveFrameAgeMs;
    std::size_t receive_buffer_bytes = contract::kDefaultReceiveBufferBytes;
    std::string drm_device = contract::kDefaultDrmDevice;
    std::string decoder_mode = contract::kDefaultDecoderMode;
    std::string diagnostics_path = contract::kDefaultDiagnosticsPath;
    bool transparent_placeholder = false;
};

class CamBridgeSource {
public:
    CamBridgeSource(SourceConfig config, obs_source_t *source);
    ~CamBridgeSource();

    CamBridgeSource(const CamBridgeSource &) = delete;
    CamBridgeSource &operator=(const CamBridgeSource &) = delete;

    bool start(std::string &error);
    void stop();
    void update(obs_data_t *settings);
    void render(gs_effect_t *effect);
    void tick(float seconds);
    void write_diagnostics();

    [[nodiscard]] std::uint32_t width() const;
    [[nodiscard]] std::uint32_t height() const;

private:
    [[nodiscard]] SourceConfig configuration() const;
    bool on_hello(const HelloMessage &hello, const std::string &peer_address, std::string &error);
    std::string on_probe(const std::string &request_id) const;
    void on_control_message(const ControlMessage &message);
    void on_control_disconnect();
    void on_access_unit(AccessUnit access_unit);
    void on_packet_loss(std::size_t lost);
    void on_invalid_packet(const std::string &reason);
    void on_decoder_frame(VideoFramePtr frame);
    void on_decoder_event(const std::string &event);
    void on_renderer_hardware_fallback();
    void end_session();
    void report(const std::string &event) const;

    SourceConfig config_;
    obs_source_t *source_ = nullptr;
    mutable std::mutex configuration_mutex_;
    mutable std::mutex session_mutex_;
    std::string session_id_;
    std::string peer_address_;
    std::uint64_t stream_generation_ = 0;
    std::uint32_t active_width_ = 0;
    std::uint32_t active_height_ = 0;
    std::uint32_t active_display_width_ = 0;
    std::uint32_t active_display_height_ = 0;
    std::uint32_t active_rotation_degrees_ = 0;
    std::uint32_t active_fps_ = 0;
    std::uint32_t active_bitrate_bps_ = 0;
    bool session_active_ = false;
    bool started_ = false;
    bool stale_state_ = false;

    LatestFrameMailbox<VideoFrame> mailbox_;
    std::unique_ptr<ControlServer> control_server_;
    std::unique_ptr<DiscoveryAdvertiser> discovery_advertiser_;
    std::unique_ptr<MediaReceiver> media_receiver_;
    std::unique_ptr<Decoder> decoder_;
    Renderer renderer_;
    std::atomic<std::uint64_t> malformed_events_{0};
    std::atomic<std::uint64_t> packet_loss_events_{0};
    std::atomic<std::uint64_t> stale_transitions_{0};
    std::atomic<bool> first_frame_reported_{false};
    std::atomic<std::uint64_t> max_receive_to_decode_ns_{0};
    std::atomic<std::uint64_t> max_receive_to_publish_ns_{0};
    std::atomic<std::uint64_t> max_receive_to_render_ns_{0};
    std::atomic<std::uint64_t> frames_rendered_{0};
    std::atomic<std::uint64_t> last_rendered_frame_generation_{0};
};

SourceConfig source_config_from_settings(obs_data_t *settings);
void source_get_defaults(obs_data_t *settings);
obs_properties_t *source_get_properties(void *data);
void *source_create(obs_data_t *settings, obs_source_t *source);
void source_destroy(void *data);
std::uint32_t source_get_width(void *data);
std::uint32_t source_get_height(void *data);
void source_update(void *data, obs_data_t *settings);
void source_video_render(void *data, gs_effect_t *effect);
void source_video_tick(void *data, float seconds);

} // namespace cambridge
