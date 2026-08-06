#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace direct_webcam {

struct HelloMessage {
    std::string session_id;
    std::uint64_t generation = 0;
    std::string profile_id;
    std::uint32_t coded_width = 0;
    std::uint32_t coded_height = 0;
    std::uint32_t rotation_degrees = 0;
    std::uint32_t fps = 0;
    std::uint32_t bitrate_bps = 0;
    std::string codec;
};

struct ControlMessage {
    std::string type;
    std::string request_id;
    std::string session_id;
    std::uint64_t generation = 0;
    HelloMessage hello;
};

bool decode_control_message(const std::string &json, ControlMessage &message, std::string &error);
std::string encode_accepted_message(const std::string &session_id, std::uint64_t generation,
                                    const std::string &profile_id,
                                    std::uint32_t media_port, std::uint32_t maximum_long_edge,
                                    std::uint32_t maximum_short_edge);
std::string encode_capabilities_message(const std::string &request_id, const std::string &receiver_id,
                                        const std::string &display_name,
                                        const std::vector<std::string> &profile_ids,
                                        std::uint32_t maximum_long_edge,
                                        std::uint32_t maximum_short_edge);
std::string encode_error_message(const std::string &reason);
std::vector<std::uint8_t> frame_control_message(const std::string &json);

} // namespace direct_webcam
