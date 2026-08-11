#pragma once

#include "rtp.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace cambridge {

struct MediaReceiverConfig {
    std::uint16_t media_port = 0;
    std::uint32_t reorder_deadline_ms = 0;
    std::size_t receive_buffer_bytes = 0;
    std::size_t maximum_datagram_bytes = 0;
    std::size_t maximum_access_unit_bytes = 0;
    std::uint8_t payload_type = 0;
};

class MediaReceiver {
public:
    using AccessUnitCallback = std::function<void(AccessUnit)>;
    using LossCallback = std::function<void(std::size_t)>;
    using InvalidPacketCallback = std::function<void(const std::string &)>;

    MediaReceiver(MediaReceiverConfig config, AccessUnitCallback on_access_unit, LossCallback on_loss,
                  InvalidPacketCallback on_invalid_packet);
    ~MediaReceiver();

    MediaReceiver(const MediaReceiver &) = delete;
    MediaReceiver &operator=(const MediaReceiver &) = delete;

    bool start(std::string &error);
    void stop();
    void begin_session(std::uint64_t stream_generation, const std::string &peer_address);
    void end_session();

    [[nodiscard]] std::uint64_t packets_received() const { return packets_received_.load(); }
    [[nodiscard]] std::uint64_t bytes_received() const { return bytes_received_.load(); }
    [[nodiscard]] std::uint64_t packets_lost() const { return packets_lost_.load(); }
    [[nodiscard]] std::uint64_t malformed_packets() const { return malformed_packets_.load(); }
    [[nodiscard]] std::uint64_t invalid_source_packets() const { return invalid_source_packets_.load(); }
    [[nodiscard]] std::uint64_t reorder_occupancy() const { return reorder_occupancy_.load(); }
    [[nodiscard]] std::uint64_t reorder_peak() const { return reorder_peak_.load(); }
    [[nodiscard]] std::uint64_t reorder_deadline_drops() const { return reorder_deadline_drops_.load(); }

private:
    void run();
    bool accepts_source(const std::string &source) const;

    MediaReceiverConfig config_;
    AccessUnitCallback on_access_unit_;
    LossCallback on_loss_;
    InvalidPacketCallback on_invalid_packet_;
    mutable std::mutex session_mutex_;
    bool session_active_ = false;
    std::uint64_t stream_generation_ = 0;
    std::string peer_address_;
    std::atomic<int> socket_fd_{-1};
    std::atomic<bool> stopping_{false};
    std::thread thread_;
    std::atomic<std::uint64_t> packets_received_{0};
    std::atomic<std::uint64_t> bytes_received_{0};
    std::atomic<std::uint64_t> packets_lost_{0};
    std::atomic<std::uint64_t> malformed_packets_{0};
    std::atomic<std::uint64_t> invalid_source_packets_{0};
    std::atomic<std::uint64_t> session_epoch_{0};
    std::atomic<std::uint64_t> reorder_occupancy_{0};
    std::atomic<std::uint64_t> reorder_peak_{0};
    std::atomic<std::uint64_t> reorder_deadline_drops_{0};
};

} // namespace cambridge
