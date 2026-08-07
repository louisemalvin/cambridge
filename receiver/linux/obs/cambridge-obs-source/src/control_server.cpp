#include "control_server.hpp"

#include "protocol_contract.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <vector>

namespace cambridge {
namespace {

constexpr int kListenBacklog = 1;
constexpr int kInvalidSocket = -1;
constexpr std::size_t kLengthPrefixBytes = 4;

bool wait_for_socket(int fd, short events)
{
    pollfd descriptor{fd, events, 0};
    const int result = poll(&descriptor, 1, static_cast<int>(contract::kWorkerPollIntervalMs));
    return result > 0 && (descriptor.revents & events) != 0;
}

bool receive_exact(int fd, std::uint8_t *data, std::size_t size)
{
    std::size_t offset = 0;
    while (offset < size) {
        pollfd descriptor{fd, POLLIN, 0};
        const int poll_result = poll(&descriptor, 1, static_cast<int>(contract::kWorkerPollIntervalMs));
        if (poll_result < 0 && errno == EINTR) {
            continue;
        }
        if (poll_result < 0 || (poll_result > 0 && (descriptor.revents & POLLIN) == 0)) {
            return false;
        }
        if (poll_result == 0) {
            continue;
        }
        const ssize_t received = recv(fd, data + offset, size - offset, MSG_NOSIGNAL);
        if (received <= 0) {
            return false;
        }
        offset += static_cast<std::size_t>(received);
    }
    return true;
}

bool send_exact(int fd, const std::uint8_t *data, std::size_t size)
{
    std::size_t offset = 0;
    while (offset < size) {
        const ssize_t sent = send(fd, data + offset, size - offset, MSG_NOSIGNAL);
        if (sent <= 0) {
            return false;
        }
        offset += static_cast<std::size_t>(sent);
    }
    return true;
}

std::string peer_address(const sockaddr_in &peer)
{
    std::array<char, INET_ADDRSTRLEN> address{};
    if (!inet_ntop(AF_INET, &peer.sin_addr, address.data(), address.size())) {
        return {};
    }
    return address.data();
}

} // namespace

ControlServer::ControlServer(std::uint16_t port, std::uint16_t media_port, std::uint32_t maximum_long_edge,
                             std::uint32_t maximum_short_edge, HelloHandler on_hello, ProbeHandler on_probe,
                             MessageHandler on_message,
                             DisconnectHandler on_disconnect)
    : port_(port), media_port_(media_port), maximum_long_edge_(maximum_long_edge),
      maximum_short_edge_(maximum_short_edge),
      on_hello_(std::move(on_hello)), on_probe_(std::move(on_probe)), on_message_(std::move(on_message)),
      on_disconnect_(std::move(on_disconnect))
{
}

ControlServer::~ControlServer()
{
    stop();
}

bool ControlServer::start(std::string &error)
{
    if (port_ == 0) {
        error = "control port is outside the valid network range";
        return false;
    }
    const int descriptor = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    if (descriptor == kInvalidSocket) {
        error = std::strerror(errno);
        return false;
    }
    int reuse = 1;
    setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(port_);
    if (bind(descriptor, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) != 0 ||
        listen(descriptor, kListenBacklog) != 0) {
        error = std::strerror(errno);
        close(descriptor);
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        listen_fd_ = descriptor;
        stopping_ = false;
    }
    thread_ = std::thread(&ControlServer::run, this);
    return true;
}

void ControlServer::stop()
{
    int listen_fd = kInvalidSocket;
    int active_fd = kInvalidSocket;
    int pending_fd = kInvalidSocket;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopping_ = true;
        listen_fd = listen_fd_;
        active_fd = active_fd_;
        pending_fd = pending_fd_;
        listen_fd_ = kInvalidSocket;
        active_fd_ = kInvalidSocket;
        pending_fd_ = kInvalidSocket;
    }
    if (listen_fd != kInvalidSocket) {
        shutdown(listen_fd, SHUT_RDWR);
        close(listen_fd);
    }
    if (active_fd != kInvalidSocket) {
        shutdown(active_fd, SHUT_RDWR);
        close(active_fd);
    }
    if (pending_fd != kInvalidSocket) {
        shutdown(pending_fd, SHUT_RDWR);
        close(pending_fd);
    }
    if (thread_.joinable()) {
        thread_.join();
    }
}

bool ControlServer::send_json(const std::string &json)
{
    int descriptor = kInvalidSocket;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        descriptor = active_fd_;
    }
    if (descriptor == kInvalidSocket || json.size() > contract::kMaximumControlMessageBytes) {
        return false;
    }
    return write_frame(descriptor, json);
}

bool ControlServer::is_connected() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return active_fd_ != kInvalidSocket;
}

void ControlServer::close_active_connection()
{
    int descriptor = kInvalidSocket;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        descriptor = active_fd_;
        active_fd_ = kInvalidSocket;
    }
    if (descriptor != kInvalidSocket) {
        shutdown(descriptor, SHUT_RDWR);
        close(descriptor);
    }
}

bool ControlServer::take_pending_connection(int descriptor)
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (pending_fd_ != descriptor || stopping_) {
        return false;
    }
    pending_fd_ = kInvalidSocket;
    return true;
}

bool ControlServer::read_frame(int fd, std::string &json)
{
    std::array<std::uint8_t, kLengthPrefixBytes> header{};
    if (!receive_exact(fd, header.data(), header.size())) {
        return false;
    }
    const std::uint32_t message_size = (static_cast<std::uint32_t>(header[0]) << 24U) |
                                       (static_cast<std::uint32_t>(header[1]) << 16U) |
                                       (static_cast<std::uint32_t>(header[2]) << 8U) |
                                       static_cast<std::uint32_t>(header[3]);
    if (message_size == 0 || message_size > contract::kMaximumControlMessageBytes) {
        return false;
    }
    std::vector<std::uint8_t> payload(message_size);
    if (!receive_exact(fd, payload.data(), payload.size())) {
        return false;
    }
    json.assign(reinterpret_cast<const char *>(payload.data()), payload.size());
    return true;
}

bool ControlServer::write_frame(int fd, const std::string &json)
{
    const std::vector<std::uint8_t> frame = frame_control_message(json);
    std::lock_guard<std::mutex> lock(write_mutex_);
    return send_exact(fd, frame.data(), frame.size());
}

void ControlServer::run()
{
    pthread_setname_np(pthread_self(), "cambridge-control");
    while (true) {
        int descriptor = kInvalidSocket;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopping_) {
                break;
            }
            descriptor = listen_fd_;
        }
        if (descriptor == kInvalidSocket || !wait_for_socket(descriptor, POLLIN)) {
            continue;
        }
        sockaddr_in peer{};
        socklen_t peer_size = sizeof(peer);
        const int client = accept4(descriptor, reinterpret_cast<sockaddr *>(&peer), &peer_size, SOCK_CLOEXEC);
        if (client == kInvalidSocket) {
            continue;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopping_) {
                close(client);
                break;
            }
            pending_fd_ = client;
        }

        std::string first_json;
        const bool received_first_frame = read_frame(client, first_json);
        const bool owns_pending_connection = take_pending_connection(client);
        if (!received_first_frame || !owns_pending_connection) {
            if (owns_pending_connection) {
                shutdown(client, SHUT_RDWR);
                close(client);
            }
            continue;
        }

        ControlMessage first_message;
        std::string error;
        if (!decode_control_message(first_json, first_message, error)) {
            write_frame(client, encode_error_message(error));
            shutdown(client, SHUT_RDWR);
            close(client);
            continue;
        }
        if (first_message.type == contract::kMessageProbe) {
            if (on_probe_) {
                write_frame(client, on_probe_(first_message.request_id));
            }
            shutdown(client, SHUT_RDWR);
            close(client);
            continue;
        }
        if (first_message.type != contract::kMessageHello) {
            write_frame(client, encode_error_message("the first control message must be hello"));
            shutdown(client, SHUT_RDWR);
            close(client);
            continue;
        }

        close_active_connection();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopping_) {
                shutdown(client, SHUT_RDWR);
                close(client);
                break;
            }
            active_fd_ = client;
        }

        bool accepted = false;
        bool first_message_pending = true;
        while (true) {
            ControlMessage message;
            if (first_message_pending) {
                message = std::move(first_message);
                first_message_pending = false;
            } else {
                std::string json;
                if (!read_frame(client, json)) {
                    break;
                }
                if (!decode_control_message(json, message, error)) {
                    write_frame(client, encode_error_message(error));
                    break;
                }
            }
            if (!accepted) {
                if (!on_hello_ || !on_hello_(message.hello, peer_address(peer), error)) {
                    write_frame(client, encode_error_message(error.empty() ? "session rejected" : error));
                    break;
                }
                accepted = true;
                if (!write_frame(client, encode_accepted_message(message.hello.session_id, message.hello.generation,
                                                                  message.hello.profile_id, media_port_, maximum_long_edge_,
                                                                  maximum_short_edge_))) {
                    break;
                }
                continue;
            }
            if (message.type == contract::kMessageStop) {
                if (on_message_) {
                    on_message_(message);
                }
            }
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (active_fd_ == client) {
                active_fd_ = kInvalidSocket;
            }
        }
        shutdown(client, SHUT_RDWR);
        close(client);
        if (accepted && on_disconnect_) {
            on_disconnect_();
        }
    }
}

} // namespace cambridge
