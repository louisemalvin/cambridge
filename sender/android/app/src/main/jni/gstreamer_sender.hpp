#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

class GStreamerSender {
public:
    struct Config {
        std::string remote_host;
        std::uint16_t remote_rtp_port = 0;
        std::uint16_t remote_rtcp_port = 0;
        std::uint16_t local_rtcp_port = 0;
        std::uint32_t target_bitrate_bps = 0;
        std::uint32_t mtu_bytes = 0;
    };

    struct Callbacks {
        std::function<void(std::uint32_t)> estimated_bitrate_changed;
        std::function<void()> keyframe_requested;
        std::function<void(const std::string &)> transport_error;
    };

    explicit GStreamerSender(Callbacks callbacks);
    ~GStreamerSender();

    GStreamerSender(const GStreamerSender &) = delete;
    GStreamerSender &operator=(const GStreamerSender &) = delete;

    bool start(const Config &config, std::string &error);

    bool push_access_unit(
        const std::uint8_t *data,
        std::size_t size,
        std::int64_t presentation_time_us,
        bool keyframe
    );

    void stop();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
