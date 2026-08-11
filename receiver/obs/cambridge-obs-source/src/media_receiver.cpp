#include "media_receiver.hpp"

#include "platform/posix/posix_compat.hpp"
#include "protocol_contract.generated.hpp"
#include "receiver_constants.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>

namespace cambridge {
namespace {

constexpr int kInvalidSocket = -1;
constexpr int kAddressFamily = AF_INET;
constexpr int kSocketType = SOCK_DGRAM;
constexpr int kSocketProtocol = IPPROTO_UDP;
constexpr int kSocketReuseEnabled = 1;
constexpr std::size_t kMinimumReceiveBufferBytes = 64 * 1024;
constexpr std::uint64_t kNanosecondsPerSecond = 1'000'000'000ULL;

std::string address_string(const sockaddr_in &address)
{
    std::array<char, INET_ADDRSTRLEN> buffer{};
    if (!inet_ntop(kAddressFamily, &address.sin_addr, buffer.data(), buffer.size())) {
        return {};
    }
    return buffer.data();
}

std::uint64_t monotonic_time_ns()
{
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<std::uint64_t>(time.tv_sec) * kNanosecondsPerSecond +
           static_cast<std::uint64_t>(time.tv_nsec);
}

} // namespace

MediaReceiver::MediaReceiver(MediaReceiverConfig config, AccessUnitCallback on_access_unit,
                             LossCallback on_loss, InvalidPacketCallback on_invalid_packet)
    : config_(config), on_access_unit_(std::move(on_access_unit)), on_loss_(std::move(on_loss)),
      on_invalid_packet_(std::move(on_invalid_packet))
{
}

MediaReceiver::~MediaReceiver()
{
    stop();
}

bool MediaReceiver::start(std::string &error)
{
    if (config_.media_port == 0 || config_.maximum_datagram_bytes < contract::kRtpHeaderBytes ||
        config_.maximum_datagram_bytes > contract::kMaximumRtpDatagramBytes ||
        config_.maximum_access_unit_bytes < config_.maximum_datagram_bytes) {
        error = "invalid media receiver bounds";
        return false;
    }
    const int descriptor = posix::create_cloexec_socket(kAddressFamily, kSocketType, kSocketProtocol, error);
    if (descriptor == kInvalidSocket) {
        return false;
    }
    setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, &kSocketReuseEnabled, sizeof(kSocketReuseEnabled));
    const int receive_buffer = static_cast<int>(std::max(config_.receive_buffer_bytes, kMinimumReceiveBufferBytes));
    setsockopt(descriptor, SOL_SOCKET, SO_RCVBUF, &receive_buffer, sizeof(receive_buffer));
    sockaddr_in address{};
    address.sin_family = kAddressFamily;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(config_.media_port);
    if (bind(descriptor, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) != 0) {
        error = std::strerror(errno);
        close(descriptor);
        return false;
    }
    socket_fd_.store(descriptor);
    stopping_.store(false);
    thread_ = std::thread(&MediaReceiver::run, this);
    return true;
}

void MediaReceiver::stop()
{
    stopping_.store(true);
    end_session();
    const int descriptor = socket_fd_.exchange(kInvalidSocket);
    if (descriptor != kInvalidSocket) {
        shutdown(descriptor, SHUT_RDWR);
        close(descriptor);
    }
    if (thread_.joinable()) {
        thread_.join();
    }
}

void MediaReceiver::begin_session(std::uint64_t stream_generation, const std::string &peer_address)
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    stream_generation_ = stream_generation;
    peer_address_ = peer_address;
    session_active_ = true;
    session_epoch_.fetch_add(1);
}

void MediaReceiver::end_session()
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    session_active_ = false;
    stream_generation_ = 0;
    peer_address_.clear();
    session_epoch_.fetch_add(1);
}

bool MediaReceiver::accepts_source(const std::string &source) const
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    return session_active_ && (peer_address_.empty() || peer_address_ == source);
}

void MediaReceiver::run()
{
    posix::set_current_thread_name("cambridge-rx");
    std::vector<std::uint8_t> buffer(config_.maximum_datagram_bytes);
    RtpH264Assembler assembler(
        config_.maximum_access_unit_bytes,
        config_.reorder_deadline_ms,
        [this](AccessUnit access_unit) {
            on_access_unit_(std::move(access_unit));
        },
        [this](std::size_t lost) {
            packets_lost_.fetch_add(lost);
            if (on_loss_) {
                on_loss_(lost);
            }
        });
    std::uint64_t observed_session_epoch = session_epoch_.load();

    while (!stopping_.load()) {
        const std::uint64_t current_session_epoch = session_epoch_.load();
        if (current_session_epoch != observed_session_epoch) {
            assembler.reset();
            reorder_occupancy_.store(0);
            observed_session_epoch = current_session_epoch;
        }
        const int descriptor = socket_fd_.load();
        if (descriptor == kInvalidSocket) {
            break;
        }
        pollfd poll_descriptor{descriptor, POLLIN, 0};
        if (poll(&poll_descriptor, 1, static_cast<int>(receiver::kWorkerPollIntervalMs)) <= 0) {
            continue;
        }
        if ((poll_descriptor.revents & POLLIN) == 0) {
            continue;
        }
        sockaddr_in source{};
        socklen_t source_size = sizeof(source);
        const ssize_t received = recvfrom(descriptor, buffer.data(), buffer.size(), 0,
                                          reinterpret_cast<sockaddr *>(&source), &source_size);
        if (received <= 0) {
            continue;
        }
        const std::string source_address = address_string(source);
        if (!accepts_source(source_address)) {
            invalid_source_packets_.fetch_add(1);
            continue;
        }
        packets_received_.fetch_add(1);
        bytes_received_.fetch_add(static_cast<std::uint64_t>(received));
        RtpPacket packet;
        std::string error;
        if (!parse_rtp_packet(buffer.data(), static_cast<std::size_t>(received), packet, error) ||
            packet.payload_type != config_.payload_type) {
            malformed_packets_.fetch_add(1);
            if (on_invalid_packet_) {
                on_invalid_packet_(error.empty() ? "unexpected RTP payload type" : error);
            }
            continue;
        }
        assembler.push(packet, monotonic_time_ns());
        reorder_occupancy_.store(assembler.reorder_occupancy());
        reorder_peak_.store(assembler.reorder_peak());
        reorder_deadline_drops_.store(assembler.reorder_deadline_drops());
    }
}

} // namespace cambridge
