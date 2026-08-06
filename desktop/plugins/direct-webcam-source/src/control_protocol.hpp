#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace direct_webcam {

struct HelloMessage {
    std::string session_id;
    std::uint64_t generation = 0;
    std::uint32_t coded_width = 0;
    std::uint32_t coded_height = 0;
    std::uint32_t display_width = 0;
    std::uint32_t display_height = 0;
    std::uint32_t rotation_degrees = 0;
    std::uint32_t fps = 0;
    std::uint32_t bitrate_bps = 0;
    std::string codec;
};

struct ControlMessage {
    std::string type;
    std::string session_id;
    std::uint64_t generation = 0;
    HelloMessage hello;
};

struct StatusMetrics {
    std::uint64_t frames_decoded = 0;
    std::uint64_t frames_replaced = 0;
    std::uint64_t packets_received = 0;
    std::uint64_t bytes_received = 0;
    std::uint64_t packets_lost = 0;
    std::uint64_t malformed_packets = 0;
    std::uint64_t invalid_source_packets = 0;
    std::uint64_t decode_failures = 0;
    std::uint64_t decoder_queue_drops = 0;
    std::uint64_t decoder_queue_occupancy = 0;
    std::uint64_t decoder_queue_maximum = 0;
    std::uint64_t reorder_occupancy = 0;
    std::uint64_t reorder_maximum = 0;
    std::uint64_t reorder_peak = 0;
    std::uint64_t reorder_deadline_drops = 0;
    std::uint64_t stale_frames = 0;
    std::uint64_t mailbox_occupancy = 0;
    std::uint64_t mailbox_maximum = 0;
    std::uint64_t import_failures = 0;
    std::uint64_t cpu_uploads = 0;
    std::uint64_t gpu_copies = 0;
    std::uint64_t hardware_cpu_transfers = 0;
    std::uint64_t max_receive_to_decode_ms = 0;
    std::uint64_t max_receive_to_publish_ms = 0;
    std::uint64_t max_receive_to_render_ms = 0;
    std::uint64_t max_live_frame_age_ms = 0;
    std::uint64_t frames_rendered = 0;
    std::uint32_t coded_width = 0;
    std::uint32_t coded_height = 0;
    std::uint32_t display_width = 0;
    std::uint32_t display_height = 0;
    std::uint32_t rotation_degrees = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t fps = 0;
    std::uint32_t bitrate_bps = 0;
    std::string decoder;
    std::string render_mode;
};

bool decode_control_message(const std::string &json, ControlMessage &message, std::string &error);
std::string encode_accepted_message(const std::string &session_id, std::uint64_t generation,
                                    std::uint32_t media_port, std::uint32_t maximum_long_edge,
                                    std::uint32_t maximum_short_edge);
std::string encode_request_idr_message(const std::string &session_id, std::uint64_t generation);
std::string encode_error_message(const std::string &reason);
std::string encode_status_message(const std::string &session_id, std::uint64_t generation,
                                  const std::string &state, const StatusMetrics &metrics);
std::vector<std::uint8_t> frame_control_message(const std::string &json);

} // namespace direct_webcam
