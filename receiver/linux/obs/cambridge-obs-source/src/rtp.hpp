#pragma once

#include <cstddef>
#include <cstdint>
#include <atomic>
#include <functional>
#include <string>
#include <vector>

namespace cambridge {

struct RtpPacket {
    std::uint16_t sequence = 0;
    std::uint32_t timestamp = 0;
    std::uint32_t ssrc = 0;
    std::uint8_t payload_type = 0;
    bool marker = false;
    std::vector<std::uint8_t> payload;
};

struct AccessUnit {
    std::vector<std::uint8_t> annex_b;
    std::uint32_t rtp_timestamp = 0;
    std::uint16_t first_sequence = 0;
    std::uint16_t last_sequence = 0;
    std::uint64_t receive_time_ns = 0;
    std::size_t lost_packets = 0;
    bool contains_idr = false;
};

bool parse_rtp_packet(const std::uint8_t *data, std::size_t size, RtpPacket &packet, std::string &error);

class RtpH264Assembler {
public:
    using AccessUnitCallback = std::function<void(AccessUnit)>;
    using LossCallback = std::function<void(std::size_t)>;

    RtpH264Assembler(std::size_t maximum_access_unit_bytes, std::uint32_t reorder_deadline_ms,
                     AccessUnitCallback on_access_unit, LossCallback on_loss);

    void reset();
    void push(const RtpPacket &packet, std::uint64_t receive_time_ns);

    [[nodiscard]] std::uint64_t reorder_occupancy() const { return reorder_occupancy_.load(); }
    [[nodiscard]] std::uint64_t reorder_peak() const { return reorder_peak_.load(); }
    [[nodiscard]] std::uint64_t reorder_deadline_drops() const { return reorder_deadline_drops_.load(); }

private:
    void discard_current();
    void process_packet(const RtpPacket &packet, std::uint64_t receive_time_ns);
    void flush_reorder(std::uint64_t receive_time_ns);
    void append_nal(const std::uint8_t *data, std::size_t size, std::uint16_t sequence);
    void finish(std::uint64_t receive_time_ns);

    std::size_t maximum_access_unit_bytes_;
    std::uint32_t reorder_deadline_ms_;
    AccessUnitCallback on_access_unit_;
    LossCallback on_loss_;
    AccessUnit current_;
    std::uint32_t current_timestamp_ = 0;
    std::uint16_t expected_sequence_ = 0;
    bool have_sequence_ = false;
    bool active_ = false;
    bool corrupted_ = false;
    bool fu_active_ = false;
    std::uint8_t fu_nal_header_ = 0;
    struct PendingPacket {
        RtpPacket packet;
        std::uint64_t receive_time_ns = 0;
    };
    std::vector<PendingPacket> reorder_buffer_;
    std::uint64_t gap_started_ns_ = 0;
    std::atomic<std::uint64_t> reorder_occupancy_{0};
    std::atomic<std::uint64_t> reorder_peak_{0};
    std::atomic<std::uint64_t> reorder_deadline_drops_{0};

    void observe_reorder_occupancy();
};

class RtpH264Packetizer {
public:
    using SendPacketCallback = std::function<bool(const std::uint8_t *, std::size_t)>;

    RtpH264Packetizer(std::size_t mtu_bytes, std::uint32_t payload_type, std::uint32_t clock_rate,
                      SendPacketCallback send_packet);

    bool send_access_unit(const std::vector<std::uint8_t> &annex_b, std::uint64_t timestamp_us,
                          std::uint32_t &sequence, std::uint32_t ssrc);

private:
    bool send_nal(const std::uint8_t *nal, std::size_t size, bool is_last_nal, std::uint32_t timestamp,
                  std::uint32_t &sequence, std::uint32_t ssrc);
    bool send_datagram(const std::uint8_t *payload, std::size_t payload_size, bool marker,
                       std::uint32_t timestamp, std::uint32_t &sequence, std::uint32_t ssrc);

    std::size_t mtu_bytes_;
    std::uint32_t payload_type_;
    std::uint32_t clock_rate_;
    SendPacketCallback send_packet_;
};

} // namespace cambridge
