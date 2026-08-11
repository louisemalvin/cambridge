#include "rtp.hpp"

#include "protocol_contract.generated.hpp"

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>

namespace cambridge {
namespace {

constexpr std::uint8_t kRtpVersion = 2;
constexpr std::uint8_t kRtpVersionMask = 0xc0;
constexpr std::uint8_t kRtpPaddingMask = 0x20;
constexpr std::uint8_t kRtpExtensionMask = 0x10;
constexpr std::uint8_t kRtpCsrcCountMask = 0x0f;
constexpr std::uint8_t kRtpMarkerMask = 0x80;
constexpr std::uint8_t kRtpPayloadTypeMask = 0x7f;
constexpr std::size_t kRtpFixedHeaderBytes = contract::kRtpHeaderBytes;
constexpr std::size_t kRtpExtensionHeaderBytes = 4;
constexpr std::size_t kH264NalHeaderBytes = 1;
constexpr std::size_t kFuPayloadHeaderBytes = 2;
constexpr std::size_t kH264StartCodeBytes = 4;
constexpr std::uint8_t kFuStartMask = 0x80;
constexpr std::uint8_t kFuEndMask = 0x40;
constexpr std::uint8_t kFuTypeMask = 0x1f;
constexpr std::uint8_t kFuIndicatorTypeMask = 0x1f;
constexpr std::uint8_t kFuIndicatorNriMask = 0xe0;
constexpr std::uint8_t kFuIndicatorForbiddenBit = 0x80;
constexpr std::uint8_t kFuANalType = 28;
constexpr std::uint8_t kH264IdrNalType = 5;
constexpr std::size_t kSequenceHalfRange = 0x8000;
constexpr std::uint64_t kMicrosecondsPerSecond = 1'000'000;
constexpr std::uint64_t kNanosecondsPerMillisecond = 1'000'000;

std::uint16_t read_u16(const std::uint8_t *data)
{
    return static_cast<std::uint16_t>((static_cast<std::uint16_t>(data[0]) << 8U) | data[1]);
}

std::uint32_t read_u32(const std::uint8_t *data)
{
    return (static_cast<std::uint32_t>(data[0]) << 24U) |
           (static_cast<std::uint32_t>(data[1]) << 16U) |
           (static_cast<std::uint32_t>(data[2]) << 8U) |
           static_cast<std::uint32_t>(data[3]);
}

void write_u16(std::uint8_t *data, std::uint16_t value)
{
    data[0] = static_cast<std::uint8_t>(value >> 8U);
    data[1] = static_cast<std::uint8_t>(value);
}

void write_u32(std::uint8_t *data, std::uint32_t value)
{
    data[0] = static_cast<std::uint8_t>(value >> 24U);
    data[1] = static_cast<std::uint8_t>(value >> 16U);
    data[2] = static_cast<std::uint8_t>(value >> 8U);
    data[3] = static_cast<std::uint8_t>(value);
}

bool is_sequence_ahead(std::uint16_t sequence, std::uint16_t expected)
{
    const std::uint16_t distance = static_cast<std::uint16_t>(sequence - expected);
    return distance != 0 && distance < kSequenceHalfRange;
}

std::size_t find_start_code(const std::vector<std::uint8_t> &data, std::size_t offset,
                            std::size_t &start_code_bytes)
{
    for (std::size_t index = offset; index + 3U <= data.size(); ++index) {
        if (data[index] != 0 || data[index + 1U] != 0) {
            continue;
        }
        if (data[index + 2U] == 1U) {
            start_code_bytes = 3;
            return index;
        }
        if (index + 4U <= data.size() && data[index + 2U] == 0 && data[index + 3U] == 1U) {
            start_code_bytes = 4;
            return index;
        }
    }
    return std::numeric_limits<std::size_t>::max();
}

} // namespace

bool parse_rtp_packet(const std::uint8_t *data, std::size_t size, RtpPacket &packet, std::string &error)
{
    if (!data || size < kRtpFixedHeaderBytes) {
        error = "RTP datagram is shorter than the fixed header";
        return false;
    }
    if ((data[0] & kRtpVersionMask) != static_cast<std::uint8_t>(kRtpVersion << 6U)) {
        error = "unsupported RTP version";
        return false;
    }

    const std::size_t csrc_bytes = static_cast<std::size_t>(data[0] & kRtpCsrcCountMask) * sizeof(std::uint32_t);
    std::size_t payload_offset = kRtpFixedHeaderBytes + csrc_bytes;
    if (payload_offset > size) {
        error = "RTP CSRC list exceeds datagram";
        return false;
    }
    if ((data[0] & kRtpExtensionMask) != 0U) {
        if (payload_offset + kRtpExtensionHeaderBytes > size) {
            error = "RTP extension header is truncated";
            return false;
        }
        const std::size_t extension_bytes =
            static_cast<std::size_t>(read_u16(data + payload_offset + sizeof(std::uint16_t))) * sizeof(std::uint32_t);
        payload_offset += kRtpExtensionHeaderBytes + extension_bytes;
        if (payload_offset > size) {
            error = "RTP extension exceeds datagram";
            return false;
        }
    }

    std::size_t payload_size = size - payload_offset;
    if ((data[0] & kRtpPaddingMask) != 0U) {
        if (payload_size == 0) {
            error = "RTP padding flag has no padding byte";
            return false;
        }
        const std::size_t padding_bytes = data[size - 1U];
        if (padding_bytes == 0 || padding_bytes > payload_size) {
            error = "RTP padding length is invalid";
            return false;
        }
        payload_size -= padding_bytes;
    }
    if (payload_size == 0) {
        error = "RTP payload is empty";
        return false;
    }

    packet.sequence = read_u16(data + 2U);
    packet.timestamp = read_u32(data + 4U);
    packet.ssrc = read_u32(data + 8U);
    packet.payload_type = static_cast<std::uint8_t>(data[1] & kRtpPayloadTypeMask);
    packet.marker = (data[1] & kRtpMarkerMask) != 0U;
    packet.payload.assign(data + payload_offset, data + payload_offset + payload_size);
    return true;
}

RtpH264Assembler::RtpH264Assembler(std::size_t maximum_access_unit_bytes, std::uint32_t reorder_deadline_ms,
                                   AccessUnitCallback on_access_unit, LossCallback on_loss)
    : maximum_access_unit_bytes_(maximum_access_unit_bytes), reorder_deadline_ms_(reorder_deadline_ms),
      on_access_unit_(std::move(on_access_unit)),
      on_loss_(std::move(on_loss))
{
    reorder_buffer_.reserve(contract::kMaximumReorderPackets);
}

void RtpH264Assembler::reset()
{
    discard_current();
    have_sequence_ = false;
    reorder_buffer_.clear();
    gap_started_ns_ = 0;
    reorder_occupancy_.store(0);
}

void RtpH264Assembler::observe_reorder_occupancy()
{
    const auto occupancy = static_cast<std::uint64_t>(reorder_buffer_.size());
    reorder_occupancy_.store(occupancy);
    auto peak = reorder_peak_.load();
    while (occupancy > peak && !reorder_peak_.compare_exchange_weak(peak, occupancy)) {
    }
}

void RtpH264Assembler::discard_current()
{
    current_ = AccessUnit{};
    current_timestamp_ = 0;
    active_ = false;
    corrupted_ = false;
    fu_active_ = false;
    fu_nal_header_ = 0;
}

void RtpH264Assembler::append_nal(const std::uint8_t *data, std::size_t size, std::uint16_t sequence)
{
    if (size == 0 || current_.annex_b.size() + kH264StartCodeBytes + size > maximum_access_unit_bytes_) {
        corrupted_ = true;
        return;
    }
    current_.annex_b.insert(current_.annex_b.end(), kH264StartCodeBytes, 0U);
    current_.annex_b.back() = 1U;
    current_.annex_b.insert(current_.annex_b.end(), data, data + size);
    if (current_.annex_b.size() == kH264StartCodeBytes + size) {
        current_.first_sequence = sequence;
    }
    current_.last_sequence = sequence;
    if ((data[0] & kFuTypeMask) == kH264IdrNalType) {
        current_.contains_idr = true;
    }
}

void RtpH264Assembler::finish(std::uint64_t receive_time_ns)
{
    if (active_ && !corrupted_ && !fu_active_ && !current_.annex_b.empty()) {
        current_.receive_time_ns = receive_time_ns;
        on_access_unit_(std::move(current_));
    }
    discard_current();
}

void RtpH264Assembler::process_packet(const RtpPacket &packet, std::uint64_t receive_time_ns)
{
    expected_sequence_ = static_cast<std::uint16_t>(packet.sequence + 1U);
    have_sequence_ = true;

    if (!active_ || packet.timestamp != current_timestamp_) {
        if (active_) {
            discard_current();
        }
        active_ = true;
        current_timestamp_ = packet.timestamp;
        current_.rtp_timestamp = packet.timestamp;
        current_.first_sequence = packet.sequence;
    }

    if (packet.payload.empty()) {
        corrupted_ = true;
        return;
    }
    const std::uint8_t nal_type = packet.payload[0] & kFuTypeMask;
    if (nal_type >= 1U && nal_type <= 23U) {
        if (fu_active_) {
            corrupted_ = true;
        }
        append_nal(packet.payload.data(), packet.payload.size(), packet.sequence);
    } else if (nal_type == kFuANalType) {
        if (packet.payload.size() < kFuPayloadHeaderBytes) {
            corrupted_ = true;
        } else {
            const std::uint8_t indicator = packet.payload[0];
            const std::uint8_t fu_header = packet.payload[1];
            const bool start = (fu_header & kFuStartMask) != 0U;
            const bool end = (fu_header & kFuEndMask) != 0U;
            if ((indicator & kFuIndicatorForbiddenBit) != 0U || (fu_header & kFuTypeMask) == 0U) {
                corrupted_ = true;
            } else if (start) {
                if (fu_active_) {
                    corrupted_ = true;
                }
                fu_active_ = true;
                fu_nal_header_ = static_cast<std::uint8_t>((indicator & kFuIndicatorNriMask) |
                                                            (fu_header & kFuTypeMask));
                append_nal(&fu_nal_header_, sizeof(fu_nal_header_), packet.sequence);
                if (packet.payload.size() > kFuPayloadHeaderBytes) {
                    const auto fragment = packet.payload.data() + kFuPayloadHeaderBytes;
                    const auto fragment_size = packet.payload.size() - kFuPayloadHeaderBytes;
                    if (current_.annex_b.size() + fragment_size > maximum_access_unit_bytes_) {
                        corrupted_ = true;
                    } else {
                        current_.annex_b.insert(current_.annex_b.end(), fragment, fragment + fragment_size);
                    }
                }
                current_.last_sequence = packet.sequence;
            } else if (!fu_active_) {
                corrupted_ = true;
            } else {
                const auto fragment = packet.payload.data() + kFuPayloadHeaderBytes;
                const auto fragment_size = packet.payload.size() - kFuPayloadHeaderBytes;
                if (current_.annex_b.size() + fragment_size > maximum_access_unit_bytes_) {
                    corrupted_ = true;
                } else {
                    current_.annex_b.insert(current_.annex_b.end(), fragment, fragment + fragment_size);
                }
                current_.last_sequence = packet.sequence;
                if (end) {
                    fu_active_ = false;
                    if ((fu_nal_header_ & kFuTypeMask) == kH264IdrNalType) {
                        current_.contains_idr = true;
                    }
                }
            }
        }
    } else {
        corrupted_ = true;
    }

    if (packet.marker) {
        finish(receive_time_ns);
    }
}

void RtpH264Assembler::flush_reorder(std::uint64_t receive_time_ns)
{
    if (reorder_buffer_.empty()) {
        gap_started_ns_ = 0;
        return;
    }
    auto next = reorder_buffer_.end();
    std::uint16_t next_distance = std::numeric_limits<std::uint16_t>::max();
    for (auto candidate = reorder_buffer_.begin(); candidate != reorder_buffer_.end(); ++candidate) {
        if (!is_sequence_ahead(candidate->packet.sequence, expected_sequence_)) {
            continue;
        }
        const auto distance = static_cast<std::uint16_t>(candidate->packet.sequence - expected_sequence_);
        if (next == reorder_buffer_.end() || distance < next_distance) {
            next = candidate;
            next_distance = distance;
        }
    }
    if (next == reorder_buffer_.end()) {
        reorder_buffer_.clear();
        observe_reorder_occupancy();
        gap_started_ns_ = 0;
        return;
    }
    if (next_distance > 0 && on_loss_) {
        on_loss_(static_cast<std::size_t>(next_distance));
    }
    corrupted_ = true;
    expected_sequence_ = next->packet.sequence;
    process_packet(next->packet, next->receive_time_ns == 0 ? receive_time_ns : next->receive_time_ns);
    reorder_buffer_.erase(next);
    observe_reorder_occupancy();
    while (true) {
        auto contiguous = std::find_if(reorder_buffer_.begin(), reorder_buffer_.end(), [this](const PendingPacket &value) {
            return value.packet.sequence == expected_sequence_;
        });
        if (contiguous == reorder_buffer_.end()) {
            break;
        }
        process_packet(contiguous->packet, contiguous->receive_time_ns);
        reorder_buffer_.erase(contiguous);
        observe_reorder_occupancy();
    }
    gap_started_ns_ = reorder_buffer_.empty() ? 0 : receive_time_ns;
}

void RtpH264Assembler::push(const RtpPacket &packet, std::uint64_t receive_time_ns)
{
    if (!have_sequence_) {
        expected_sequence_ = packet.sequence;
        have_sequence_ = true;
        process_packet(packet, receive_time_ns);
        return;
    }
    if (packet.sequence == expected_sequence_) {
        process_packet(packet, receive_time_ns);
        while (true) {
            auto contiguous = std::find_if(reorder_buffer_.begin(), reorder_buffer_.end(), [this](const PendingPacket &value) {
                return value.packet.sequence == expected_sequence_;
            });
            if (contiguous == reorder_buffer_.end()) {
                break;
            }
            process_packet(contiguous->packet, contiguous->receive_time_ns);
            reorder_buffer_.erase(contiguous);
        }
        gap_started_ns_ = reorder_buffer_.empty() ? 0 : gap_started_ns_;
        return;
    }
    if (!is_sequence_ahead(packet.sequence, expected_sequence_)) {
        return;
    }
    if (std::find_if(reorder_buffer_.begin(), reorder_buffer_.end(), [&packet](const PendingPacket &value) {
            return value.packet.sequence == packet.sequence;
        }) == reorder_buffer_.end()) {
        reorder_buffer_.push_back(PendingPacket{packet, receive_time_ns});
        observe_reorder_occupancy();
    }
    if (gap_started_ns_ == 0) {
        gap_started_ns_ = receive_time_ns;
    }
    const auto deadline_ns = static_cast<std::uint64_t>(reorder_deadline_ms_) * kNanosecondsPerMillisecond;
    const bool deadline_expired = receive_time_ns >= gap_started_ns_ &&
                                  receive_time_ns - gap_started_ns_ >= deadline_ns;
    if (reorder_buffer_.size() >= contract::kMaximumReorderPackets || deadline_expired) {
        if (deadline_expired) {
            reorder_deadline_drops_.fetch_add(1);
        }
        flush_reorder(receive_time_ns);
    }
}

RtpH264Packetizer::RtpH264Packetizer(std::size_t mtu_bytes, std::uint32_t payload_type,
                                     std::uint32_t clock_rate, SendPacketCallback send_packet)
    : mtu_bytes_(mtu_bytes), payload_type_(payload_type), clock_rate_(clock_rate), send_packet_(std::move(send_packet))
{
}

bool RtpH264Packetizer::send_datagram(const std::uint8_t *payload, std::size_t payload_size, bool marker,
                                      std::uint32_t timestamp, std::uint32_t &sequence, std::uint32_t ssrc)
{
    if (payload_size == 0 || payload_size + contract::kRtpHeaderBytes > mtu_bytes_) {
        return false;
    }
    std::vector<std::uint8_t> datagram(contract::kRtpHeaderBytes + payload_size);
    datagram[0] = static_cast<std::uint8_t>(kRtpVersion << 6U);
    datagram[1] = static_cast<std::uint8_t>((marker ? kRtpMarkerMask : 0U) | (payload_type_ & kRtpPayloadTypeMask));
    write_u16(datagram.data() + 2U, static_cast<std::uint16_t>(sequence));
    write_u32(datagram.data() + 4U, timestamp);
    write_u32(datagram.data() + 8U, ssrc);
    std::memcpy(datagram.data() + contract::kRtpHeaderBytes, payload, payload_size);
    if (!send_packet_(datagram.data(), datagram.size())) {
        return false;
    }
    sequence = (sequence + 1U) & std::numeric_limits<std::uint16_t>::max();
    return true;
}

bool RtpH264Packetizer::send_nal(const std::uint8_t *nal, std::size_t size, bool is_last_nal,
                                 std::uint32_t timestamp, std::uint32_t &sequence, std::uint32_t ssrc)
{
    if (!nal || size < kH264NalHeaderBytes) {
        return false;
    }
    const std::size_t maximum_payload = mtu_bytes_ - contract::kRtpHeaderBytes;
    if (size <= maximum_payload) {
        return send_datagram(nal, size, is_last_nal, timestamp, sequence, ssrc);
    }

    const std::size_t maximum_fragment = maximum_payload - kFuPayloadHeaderBytes;
    if (maximum_fragment == 0) {
        return false;
    }
    const std::uint8_t indicator = static_cast<std::uint8_t>((nal[0] & kFuIndicatorNriMask) | kFuANalType);
    const std::uint8_t nal_type = static_cast<std::uint8_t>(nal[0] & kFuTypeMask);
    std::size_t offset = kH264NalHeaderBytes;
    bool start = true;
    while (offset < size) {
        const std::size_t fragment_size = std::min(maximum_fragment, size - offset);
        const bool end = offset + fragment_size == size;
        std::vector<std::uint8_t> payload(kFuPayloadHeaderBytes + fragment_size);
        payload[0] = indicator;
        payload[1] = static_cast<std::uint8_t>((start ? kFuStartMask : 0U) |
                                               (end ? kFuEndMask : 0U) | nal_type);
        std::memcpy(payload.data() + kFuPayloadHeaderBytes, nal + offset, fragment_size);
        if (!send_datagram(payload.data(), payload.size(), end && is_last_nal, timestamp, sequence, ssrc)) {
            return false;
        }
        offset += fragment_size;
        start = false;
    }
    return true;
}

bool RtpH264Packetizer::send_access_unit(const std::vector<std::uint8_t> &annex_b, std::uint64_t timestamp_us,
                                         std::uint32_t &sequence, std::uint32_t ssrc)
{
    if (annex_b.empty() || mtu_bytes_ <= contract::kRtpHeaderBytes + kFuPayloadHeaderBytes) {
        return false;
    }
    const auto timestamp = static_cast<std::uint32_t>((timestamp_us * clock_rate_) / kMicrosecondsPerSecond);
    std::size_t code_bytes = 0;
    std::size_t start = find_start_code(annex_b, 0, code_bytes);
    if (start == std::numeric_limits<std::size_t>::max()) {
        return false;
    }
    while (start < annex_b.size()) {
        const std::size_t nal_start = start + code_bytes;
        if (nal_start >= annex_b.size()) {
            return false;
        }
        std::size_t next_code_bytes = 0;
        const std::size_t next = find_start_code(annex_b, nal_start, next_code_bytes);
        std::size_t nal_end = next == std::numeric_limits<std::size_t>::max() ? annex_b.size() : next;
        while (nal_end > nal_start && annex_b[nal_end - 1U] == 0U) {
            --nal_end;
        }
        if (nal_end <= nal_start) {
            return false;
        }
        const bool last = next == std::numeric_limits<std::size_t>::max();
        if (!send_nal(annex_b.data() + nal_start, nal_end - nal_start, last, timestamp, sequence, ssrc)) {
            return false;
        }
        if (last) {
            break;
        }
        start = next;
        code_bytes = next_code_bytes;
    }
    return true;
}

} // namespace cambridge
