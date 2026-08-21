#include "../src/control_protocol.hpp"
#include "../src/control_server.hpp"
#include "../src/protocol_contract.generated.hpp"
#include "../src/receiver_constants.hpp"
#include "../src/platform/posix/posix_compat.hpp"

#include <arpa/inet.h>
#include <array>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <poll.h>
#include <string>
#include <thread>
#include <vector>

#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

namespace {

using namespace std::chrono_literals;

constexpr char kLoopbackAddress[] = "127.0.0.1";
constexpr int kSocketType = SOCK_STREAM;
constexpr int kSocketProtocol = IPPROTO_TCP;
constexpr int kInvalidSocket = -1;
constexpr int kPollEvents = POLLIN;
constexpr int kClosePollEvents = POLLIN | POLLHUP | POLLERR;
constexpr int kFragmentPauseMs = 10;
constexpr int kTimeoutGraceMs = 250;
constexpr int kQuietWaitPolls = 2;
constexpr int kWaitPolls = 4;
constexpr std::size_t kFragmentHeaderFirstBytes = 1;
constexpr std::size_t kFragmentHeaderMiddleBytes = 2;
constexpr std::size_t kFragmentPayloadFirstBytes = 7;
constexpr std::size_t kExpectedHelloCount = 1;
constexpr std::size_t kExpectedDisconnectCount = 1;
constexpr std::size_t kExpectedReplacementHelloCount = 2;
constexpr std::size_t kExpectedReplacementDisconnectCount = 2;

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

struct CallbackState {
    std::mutex mutex;
    std::condition_variable condition;
    std::size_t hello_count = 0;
    std::size_t disconnect_count = 0;
};

template <typename Predicate>
bool wait_for(CallbackState &state, Predicate predicate, std::chrono::milliseconds timeout)
{
    std::unique_lock<std::mutex> lock(state.mutex);
    return state.condition.wait_for(lock, timeout, predicate);
}

std::uint16_t unused_loopback_port()
{
    const int descriptor = socket(AF_INET, kSocketType, kSocketProtocol);
    require(descriptor != kInvalidSocket);
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(0);
    require(bind(descriptor, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) == 0);
    socklen_t address_size = sizeof(address);
    require(getsockname(descriptor, reinterpret_cast<sockaddr *>(&address), &address_size) == 0);
    const auto port = ntohs(address.sin_port);
    close(descriptor);
    return port;
}

int connect_loopback(std::uint16_t port)
{
    const int descriptor = socket(AF_INET, kSocketType, kSocketProtocol);
    require(descriptor != kInvalidSocket);
    sockaddr_in address{};
    address.sin_family = AF_INET;
    require(inet_pton(AF_INET, kLoopbackAddress, &address.sin_addr) == 1);
    address.sin_port = htons(port);
    int result = connect(descriptor, reinterpret_cast<const sockaddr *>(&address), sizeof(address));
    while (result != 0 && errno == EINTR) {
        result = connect(descriptor, reinterpret_cast<const sockaddr *>(&address), sizeof(address));
    }
    require(result == 0);
    return descriptor;
}

bool poll_socket(int descriptor, short events, std::chrono::milliseconds timeout)
{
    pollfd poll_descriptor{descriptor, events, 0};
    const int result = poll(&poll_descriptor, 1, static_cast<int>(timeout.count()));
    return result > 0 && (poll_descriptor.revents & events) != 0;
}

bool receive_exact(int descriptor, std::uint8_t *data, std::size_t size, std::chrono::milliseconds timeout)
{
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    std::size_t offset = 0;
    while (offset < size) {
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now());
        if (remaining.count() <= 0 || !poll_socket(descriptor, kPollEvents, remaining)) {
            return false;
        }
        const ssize_t received = recv(descriptor, data + offset, size - offset, 0);
        if (received <= 0) {
            return false;
        }
        offset += static_cast<std::size_t>(received);
    }
    return true;
}

bool receive_frame(int descriptor, std::string &json, std::chrono::milliseconds timeout)
{
    std::array<std::uint8_t, cambridge::contract::kControlHeaderBytes> header{};
    if (!receive_exact(descriptor, header.data(), header.size(), timeout)) {
        return false;
    }
    const std::uint32_t size = (static_cast<std::uint32_t>(header[0]) << 24U) |
                               (static_cast<std::uint32_t>(header[1]) << 16U) |
                               (static_cast<std::uint32_t>(header[2]) << 8U) |
                               static_cast<std::uint32_t>(header[3]);
    if (size == 0 || size > cambridge::contract::kMaximumControlMessageBytes) {
        return false;
    }
    std::vector<std::uint8_t> payload(size);
    if (!receive_exact(descriptor, payload.data(), payload.size(), timeout)) {
        return false;
    }
    json.assign(reinterpret_cast<const char *>(payload.data()), payload.size());
    return true;
}

bool wait_for_close(int descriptor, std::chrono::milliseconds timeout)
{
    if (!poll_socket(descriptor, kClosePollEvents, timeout)) {
        return false;
    }
    std::uint8_t byte = 0;
    const ssize_t received = recv(descriptor, &byte, sizeof(byte), 0);
    return received <= 0;
}

void send_all(int descriptor, const std::uint8_t *data, std::size_t size)
{
    std::size_t offset = 0;
    while (offset < size) {
        const ssize_t sent = cambridge::posix::send_without_sigpipe(
            descriptor, data + offset, size - offset, 0);
        require(sent > 0);
        offset += static_cast<std::size_t>(sent);
    }
}

void send_fragmented(int descriptor, const std::vector<std::uint8_t> &frame)
{
    send_all(descriptor, frame.data(), kFragmentHeaderFirstBytes);
    std::this_thread::sleep_for(kFragmentPauseMs * 1ms);
    send_all(descriptor, frame.data() + kFragmentHeaderFirstBytes, kFragmentHeaderMiddleBytes);
    std::this_thread::sleep_for(kFragmentPauseMs * 1ms);
    send_all(
        descriptor,
        frame.data() + kFragmentHeaderFirstBytes + kFragmentHeaderMiddleBytes,
        frame.size() - kFragmentHeaderFirstBytes - kFragmentHeaderMiddleBytes);
}

std::string hello_json(std::uint64_t generation)
{
    return "{\"protocolVersion\":" + std::to_string(cambridge::contract::kProtocolVersion) +
           ",\"type\":\"hello\",\"sessionId\":\"socket-test\",\"generation\":" +
           std::to_string(generation) + ",\"profileId\":\"1080p30\",\"codec\":\"h264\","
           "\"codedWidth\":1920,\"codedHeight\":1080,\"rotationDegrees\":0,\"fps\":30,"
           "\"targetBitrateBps\":10000000,\"senderRtcpPort\":55033}";
}

cambridge::ControlServer make_server(std::uint16_t port, CallbackState &state)
{
    return cambridge::ControlServer(
        port,
        static_cast<std::uint16_t>(cambridge::contract::kDefaultMediaRtpPort),
        static_cast<std::uint16_t>(cambridge::contract::kDefaultMediaRtcpPort),
        cambridge::contract::kMaximumLongEdge,
        cambridge::contract::kMaximumShortEdge,
        [&state](const cambridge::HelloMessage &, const std::string &, std::string &) {
            {
                std::lock_guard<std::mutex> lock(state.mutex);
                state.hello_count += 1;
            }
            state.condition.notify_all();
            return true;
        },
        [](const std::string &) { return std::string{}; },
        [](const cambridge::ControlMessage &) {},
        [&state]() {
            {
                std::lock_guard<std::mutex> lock(state.mutex);
                state.disconnect_count += 1;
            }
            state.condition.notify_all();
        });
}

void start_server(cambridge::ControlServer &server)
{
    std::string error;
    require(server.start(error));
}

void test_initial_frame_timeout_with_connected_peer()
{
    CallbackState state;
    const auto port = unused_loopback_port();
    auto server = make_server(port, state);
    start_server(server);
    const int client = connect_loopback(port);

    const auto timeout = std::chrono::milliseconds(
        cambridge::contract::kControlRequestTimeoutMs + kTimeoutGraceMs);
    require(wait_for_close(client, timeout));
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        require(state.hello_count == 0);
    }
    close(client);
    server.stop();
}

void test_fragmented_hello_and_quiet_accepted_connection()
{
    CallbackState state;
    const auto port = unused_loopback_port();
    auto server = make_server(port, state);
    start_server(server);
    const int client = connect_loopback(port);
    const auto frame = cambridge::frame_control_message(hello_json(cambridge::contract::kMinimumGeneration));
    send_fragmented(client, frame);

    std::string accepted;
    require(receive_frame(client, accepted, 1s));
    require(accepted.find("\"type\":\"accepted\"") != std::string::npos);
    require(wait_for(
        state,
        [&state]() { return state.hello_count == kExpectedHelloCount; },
        std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kWaitPolls)));

    const auto quiet_wait = std::chrono::milliseconds(
        cambridge::contract::kControlRequestTimeoutMs +
        cambridge::receiver::kWorkerPollIntervalMs * kQuietWaitPolls);
    std::this_thread::sleep_for(quiet_wait);
    require(server.is_connected());
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        require(state.disconnect_count == 0);
    }

    close(client);
    require(wait_for(
        state,
        [&state]() { return state.disconnect_count == kExpectedDisconnectCount; },
        std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kWaitPolls)));
    server.stop();
}

void test_malformed_and_oversized_frames_are_rejected()
{
    CallbackState state;
    const auto port = unused_loopback_port();
    auto server = make_server(port, state);
    start_server(server);

    const int malformed_client = connect_loopback(port);
    const auto malformed_frame = cambridge::frame_control_message("{");
    send_all(malformed_client, malformed_frame.data(), malformed_frame.size());
    std::string error;
    require(receive_frame(malformed_client, error, 1s));
    require(error.find("\"type\":\"error\"") != std::string::npos);
    close(malformed_client);

    const int oversized_client = connect_loopback(port);
    const std::uint32_t oversized_size = cambridge::contract::kMaximumControlMessageBytes + 1;
    const std::array<std::uint8_t, cambridge::contract::kControlHeaderBytes> oversized_header{
        static_cast<std::uint8_t>(oversized_size >> 24U),
        static_cast<std::uint8_t>(oversized_size >> 16U),
        static_cast<std::uint8_t>(oversized_size >> 8U),
        static_cast<std::uint8_t>(oversized_size),
    };
    send_all(oversized_client, oversized_header.data(), oversized_header.size());
    require(wait_for_close(oversized_client, 1s));
    close(oversized_client);

    {
        std::lock_guard<std::mutex> lock(state.mutex);
        require(state.hello_count == 0);
    }
    server.stop();
}

void test_error_closes_active_connection_for_recovery()
{
    CallbackState state;
    const auto port = unused_loopback_port();
    auto server = make_server(port, state);
    start_server(server);

    const int client = connect_loopback(port);
    const auto frame = cambridge::frame_control_message(hello_json(cambridge::contract::kMinimumGeneration));
    send_all(client, frame.data(), frame.size());
    std::string accepted;
    require(receive_frame(client, accepted, 1s));
    require(wait_for(
        state,
        [&state]() { return state.hello_count == kExpectedHelloCount; },
        std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kWaitPolls)));

    require(server.send_json_and_close(cambridge::encode_error_message("media path failure")));
    std::string error;
    require(receive_frame(client, error, 1s));
    require(error.find("\"type\":\"error\"") != std::string::npos);
    require(wait_for(
        state,
        [&state]() { return state.disconnect_count == kExpectedDisconnectCount; },
        std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kWaitPolls)));
    close(client);

    const int replacement = connect_loopback(port);
    send_all(replacement, frame.data(), frame.size());
    require(receive_frame(replacement, accepted, 1s));
    require(wait_for(
        state,
        [&state]() { return state.hello_count == kExpectedReplacementHelloCount; },
        std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kWaitPolls)));
    close(replacement);
    require(wait_for(
        state,
        [&state]() { return state.disconnect_count == kExpectedReplacementDisconnectCount; },
        std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kWaitPolls)));
    server.stop();
}

void test_stop_cancels_pending_initial_read()
{
    CallbackState state;
    const auto port = unused_loopback_port();
    auto server = make_server(port, state);
    start_server(server);
    const int client = connect_loopback(port);

    std::this_thread::sleep_for(
        std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kQuietWaitPolls));
    server.stop();
    require(wait_for_close(client, std::chrono::milliseconds(cambridge::receiver::kWorkerPollIntervalMs * kWaitPolls)));
    close(client);
}

} // namespace

int main()
{
    test_initial_frame_timeout_with_connected_peer();
    test_fragmented_hello_and_quiet_accepted_connection();
    test_malformed_and_oversized_frames_are_rejected();
    test_error_closes_active_connection_for_recovery();
    test_stop_cancels_pending_initial_read();
    return 0;
}
