#pragma once

#include "control_protocol.hpp"

#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace cambridge {

class ControlServer {
public:
    using HelloHandler = std::function<bool(const HelloMessage &, const std::string &, std::string &)>;
    using ProbeHandler = std::function<std::string(const std::string &)>;
    using MessageHandler = std::function<void(const ControlMessage &)>;
    using DisconnectHandler = std::function<void()>;

    ControlServer(std::uint16_t port, std::uint16_t media_port, std::uint32_t maximum_long_edge,
                  std::uint32_t maximum_short_edge, HelloHandler on_hello, ProbeHandler on_probe,
                  MessageHandler on_message,
                  DisconnectHandler on_disconnect);
    ~ControlServer();

    ControlServer(const ControlServer &) = delete;
    ControlServer &operator=(const ControlServer &) = delete;

    bool start(std::string &error);
    void stop();
    bool send_json(const std::string &json);
    bool send_json_and_close(const std::string &json);
    bool is_connected() const;

private:
    void run();
    void close_active_connection();
    bool take_pending_connection(int descriptor);
    bool read_frame(int fd, std::string &json, std::uint32_t timeout_ms);
    bool write_frame(int fd, const std::string &json);

    std::uint16_t port_;
    std::uint16_t media_port_;
    std::uint32_t maximum_long_edge_;
    std::uint32_t maximum_short_edge_;
    HelloHandler on_hello_;
    ProbeHandler on_probe_;
    MessageHandler on_message_;
    DisconnectHandler on_disconnect_;
    mutable std::mutex mutex_;
    std::mutex write_mutex_;
    int listen_fd_ = -1;
    int active_fd_ = -1;
    int pending_fd_ = -1;
    bool stopping_ = false;
    std::thread thread_;
};

} // namespace cambridge
