#include "../src/rtp.hpp"

#include "../src/protocol_contract.hpp"

#include <cassert>
#include <cstdint>
#include <vector>

namespace {

void test_single_nal_round_trip()
{
    const std::vector<std::uint8_t> source = {0, 0, 0, 1, 0x65, 0x01, 0x02, 0x03};
    std::vector<std::vector<std::uint8_t>> datagrams;
    direct_webcam::RtpH264Packetizer packetizer(
        direct_webcam::contract::kRtpMtuBytes, direct_webcam::contract::kRtpPayloadType,
        direct_webcam::contract::kRtpClockRateHz,
        [&datagrams](const std::uint8_t *data, std::size_t size) {
            datagrams.emplace_back(data, data + size);
            return true;
        });
    std::uint32_t sequence = 0;
    (void)sequence;
    assert(packetizer.send_access_unit(source, 1'000'000, sequence, 7));
    assert(datagrams.size() == 1);

    direct_webcam::RtpPacket packet;
    std::string error;
    assert(direct_webcam::parse_rtp_packet(datagrams.front().data(), datagrams.front().size(), packet, error));
    assert(packet.marker);
    direct_webcam::AccessUnit assembled;
    direct_webcam::RtpH264Assembler assembler(
        direct_webcam::contract::kMaximumAccessUnitBytes,
        direct_webcam::contract::kDefaultReorderDeadlineMs,
        [&assembled](direct_webcam::AccessUnit value) { assembled = std::move(value); },
        [](std::size_t) { assert(false); });
    assembler.push(packet, 1);
    assert(assembled.annex_b == source);
    assert(assembled.contains_idr);
}

void test_fragmented_nal_and_loss()
{
    std::vector<std::uint8_t> source = {0, 0, 0, 1, 0x65};
    source.insert(source.end(), direct_webcam::contract::kRtpMtuBytes * 2, 0x55);
    std::vector<std::vector<std::uint8_t>> datagrams;
    direct_webcam::RtpH264Packetizer packetizer(
        direct_webcam::contract::kRtpMtuBytes, direct_webcam::contract::kRtpPayloadType,
        direct_webcam::contract::kRtpClockRateHz,
        [&datagrams](const std::uint8_t *data, std::size_t size) {
            datagrams.emplace_back(data, data + size);
            return true;
        });
    std::uint32_t sequence = 10;
    (void)sequence;
    assert(packetizer.send_access_unit(source, 2'000'000, sequence, 8));
    assert(datagrams.size() > 1);
    direct_webcam::RtpH264Assembler assembler(
        direct_webcam::contract::kMaximumAccessUnitBytes,
        direct_webcam::contract::kDefaultReorderDeadlineMs,
        [](direct_webcam::AccessUnit) { assert(false); },
        [](std::size_t lost) {
            assert(lost == 1);
            (void)lost;
        });
    for (std::size_t index = 0; index < datagrams.size(); ++index) {
        if (index == 1) {
            continue;
        }
        direct_webcam::RtpPacket packet;
        std::string error;
        assert(direct_webcam::parse_rtp_packet(datagrams[index].data(), datagrams[index].size(), packet, error));
        const auto receive_time = index == 0
                                       ? 1ULL
                                       : 1ULL + direct_webcam::contract::kDefaultReorderDeadlineMs * 1'000'000ULL;
        assembler.push(packet, receive_time);
    }
    direct_webcam::RtpPacket timeout_trigger;
    timeout_trigger.sequence = 13;
    timeout_trigger.timestamp = 2 * direct_webcam::contract::kRtpClockRateHz;
    timeout_trigger.payload = {0x41, 0x01};
    timeout_trigger.marker = true;
    assembler.push(timeout_trigger, 1ULL +
                                      (direct_webcam::contract::kDefaultReorderDeadlineMs + 1) *
                                          1'000'000ULL);
}

void test_reset_starts_a_new_sequence_space()
{
    direct_webcam::RtpH264Assembler assembler(
        direct_webcam::contract::kMaximumAccessUnitBytes,
        direct_webcam::contract::kDefaultReorderDeadlineMs,
        [](direct_webcam::AccessUnit value) {
            (void)value;
            assert(value.first_sequence == 7);
        },
        [](std::size_t) { assert(false); });
    direct_webcam::RtpPacket first;
    first.sequence = 60'000;
    first.timestamp = 1;
    first.marker = true;
    first.payload = {0x41, 0x01};
    assembler.push(first, 1);
    assembler.reset();
    direct_webcam::RtpPacket after_reset;
    after_reset.sequence = 7;
    after_reset.timestamp = 2;
    after_reset.marker = true;
    after_reset.payload = {0x41, 0x02};
    assembler.push(after_reset, 2);
}

} // namespace

int main()
{
    test_single_nal_round_trip();
    test_fragmented_nal_and_loss();
    test_reset_starts_a_new_sequence_space();
    return 0;
}
