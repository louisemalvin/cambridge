#include "../src/control_protocol.hpp"

#include "../src/protocol_contract.hpp"

#include <cstdlib>
#include <cstdint>
#include <string>

namespace {

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

void test_hello_round_trip()
{
    const std::string json =
        R"({"protocolVersion":3,"type":"hello","sessionId":"session-1","generation":7,)"
        R"("codec":"h264","codedWidth":2560,"codedHeight":1440,)"
        R"("rotationDegrees":90,"fps":30,"bitrateBps":18000000})";
    direct_webcam::ControlMessage message;
    std::string error;
    require(direct_webcam::decode_control_message(json, message, error));
    require(message.type == direct_webcam::contract::kMessageHello);
    require(message.hello.session_id == "session-1");
    require(message.hello.generation == 7);
    require(message.hello.coded_width == 2560);
    require(message.hello.coded_height == 1440);
    require(message.hello.rotation_degrees == 90);
    require(message.hello.fps == direct_webcam::contract::kSupportedFps);
}

void test_duplicate_and_invalid_messages_are_rejected()
{
    const std::string duplicate =
        R"({"protocolVersion":3,"protocolVersion":3,"type":"stop","sessionId":"s","generation":1})";
    direct_webcam::ControlMessage message;
    std::string error;
    require(!direct_webcam::decode_control_message(duplicate, message, error));

    const std::string wrong_codec =
        R"({"protocolVersion":3,"type":"hello","sessionId":"s","generation":1,)"
        R"("codec":"av1","codedWidth":2560,"codedHeight":1440,)"
        R"("rotationDegrees":0,"fps":30,"bitrateBps":18000000})";
    require(!direct_webcam::decode_control_message(wrong_codec, message, error));

    const std::string old_protocol =
        R"({"protocolVersion":2,"type":"hello","sessionId":"s","generation":1,)"
        R"("codec":"h264","width":1280,"height":720,"fps":30,"bitrateBps":4000000})";
    require(!direct_webcam::decode_control_message(old_protocol, message, error));

    const std::string malformed_geometry =
        R"({"protocolVersion":3,"type":"hello","sessionId":"s","generation":1,)"
        R"("codec":"h264","codedWidth":2560,"codedHeight":1440,)"
        R"("rotationDegrees":45,"fps":30,"bitrateBps":18000000})";
    require(!direct_webcam::decode_control_message(malformed_geometry, message, error));
}

void test_reverse_orientations_are_valid()
{
    for (const int rotation : {0, 90, 180, 270}) {
        const std::string json =
            "{\"protocolVersion\":3,\"type\":\"hello\",\"sessionId\":\"s\",\"generation\":1,"
            "\"codec\":\"h264\",\"codedWidth\":2560,\"codedHeight\":1440,"
            "\"rotationDegrees\":" +
            std::to_string(rotation) + ",\"fps\":30,\"bitrateBps\":18000000}";
        direct_webcam::ControlMessage message;
        std::string error;
        require(direct_webcam::decode_control_message(json, message, error));
        require(message.hello.rotation_degrees == static_cast<std::uint32_t>(rotation));
    }
}

void test_accepted_uses_shape_independent_bounds()
{
    const auto accepted = direct_webcam::encode_accepted_message(
        "session-1", 7, direct_webcam::contract::kDefaultMediaPort,
        direct_webcam::contract::kMaximumLongEdge, direct_webcam::contract::kMaximumShortEdge);
    require(accepted.find("maxLongEdge") != std::string::npos);
    require(accepted.find("maxShortEdge") != std::string::npos);
    require(accepted.find("maxWidth") == std::string::npos);
    require(accepted.find("maxHeight") == std::string::npos);
}

void test_control_frame_has_big_endian_length_prefix()
{
    const auto frame = direct_webcam::frame_control_message("{}");
    require(frame.size() == direct_webcam::contract::kControlHeaderBytes + 2);
    require(frame[0] == 0);
    require(frame[1] == 0);
    require(frame[2] == 0);
    require(frame[3] == 2);
    require(frame[4] == '{');
    require(frame[5] == '}');
}

} // namespace

int main()
{
    test_hello_round_trip();
    test_duplicate_and_invalid_messages_are_rejected();
    test_reverse_orientations_are_valid();
    test_accepted_uses_shape_independent_bounds();
    test_control_frame_has_big_endian_length_prefix();
    return 0;
}
