#pragma once

#include "protocol_contract.generated.hpp"

#include <cstddef>
#include <cstdint>
#include <string>

namespace cambridge {

struct DiagnosticsSnapshot {
    std::string module = "cambridge-obs-plugin";
    std::string version;
    std::string git_commit;
    std::uint32_t protocol_version = contract::kProtocolVersion;
    std::string state = "listening";
    std::uint32_t coded_width = 0;
    std::uint32_t coded_height = 0;
    std::uint32_t display_width = 0;
    std::uint32_t display_height = 0;
    std::uint32_t rotation_degrees = 0;
    std::string decoder;
    std::string render;
    std::size_t mailbox_occupancy = 0;
    std::size_t mailbox_maximum = contract::kMailboxCapacity;
    std::uint64_t frames_replaced = 0;
    std::uint64_t frames_decoded = 0;
    std::uint64_t frames_rendered = 0;
    std::uint64_t hardware_cpu_transfers = 0;
    std::string requested_decoder_mode;
    std::string session_media_path;
    bool media_path_locked = false;
    std::string native_setup_status;
    std::string native_setup_reason;
    std::uint64_t media_path_failures = 0;
    std::string last_media_path_failure_code;
    std::string last_media_path_failure_detail;
    std::uint64_t native_import_failures = 0;
    std::uint64_t native_pool_exhaustions = 0;
    std::uint64_t cpu_frame_copies = 0;
    std::uint64_t gpu_copies = 0;
    std::uint64_t dma_buf_import_failures = 0;
    std::uint64_t access_units_delivered = 0;
    std::uint64_t access_unit_bytes_delivered = 0;
    std::uint64_t transport_errors = 0;
    std::uint64_t decode_failures = 0;
    std::size_t decoder_queue_occupancy = 0;
    std::uint64_t last_decoded_frame_age_ms = 0;
    std::uint64_t max_receive_to_decode_ms = 0;
    std::uint64_t max_receive_to_publish_ms = 0;
    std::uint64_t max_receive_to_render_ms = 0;
    std::uint16_t configured_control_port = 0;
    std::uint16_t configured_media_rtp_port = 0;
    std::uint16_t configured_media_rtcp_port = 0;
};

bool write_diagnostics(const DiagnosticsSnapshot &snapshot, const std::string &path,
                      std::string &error);

} // namespace cambridge
