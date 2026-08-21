#include "../src/control_protocol.hpp"

#include "../src/protocol_contract.generated.hpp"

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

std::string hello_json(std::uint32_t width, std::uint32_t height, std::uint32_t rotation,
                       std::uint32_t fps, std::uint32_t target_bitrate)
{
    return "{\"protocolVersion\":7,\"type\":\"hello\",\"sessionId\":\"session-test\","
           "\"generation\":7,\"profileId\":\"phone-authored-mode\",\"codec\":\"h264\","
           "\"codedWidth\":" + std::to_string(width) + ",\"codedHeight\":" + std::to_string(height) +
           ",\"rotationDegrees\":" + std::to_string(rotation) + ",\"fps\":" + std::to_string(fps) +
           ",\"targetBitrateBps\":" + std::to_string(target_bitrate) +
           ",\"senderRtcpPort\":55033}";
}

void test_hello_round_trip()
{
    const std::string json =
        R"({"protocolVersion":7,"type":"hello","sessionId":"session-1","generation":7,)"
        R"("profileId":"2k30",)"
        R"("codec":"h264","codedWidth":2560,"codedHeight":1440,)"
        R"("rotationDegrees":90,"fps":30,"targetBitrateBps":18000000,"senderRtcpPort":55033})";
    cambridge::ControlMessage message;
    std::string error;
    require(cambridge::decode_control_message(json, message, error));
    require(message.type == cambridge::contract::kMessageHello);
    require(message.hello.session_id == "session-1");
    require(message.hello.generation == 7);
    require(message.hello.coded_width == 2560);
    require(message.hello.coded_height == 1440);
    require(message.hello.rotation_degrees == 90);
    require(message.hello.profile_id == "2k30");
    require(message.hello.fps == 30);
}

void test_duplicate_and_invalid_messages_are_rejected()
{
    const std::string duplicate =
        R"({"protocolVersion":7,"protocolVersion":7,"type":"stop","sessionId":"s","generation":1})";
    cambridge::ControlMessage message;
    std::string error;
    require(!cambridge::decode_control_message(duplicate, message, error));

    const std::string wrong_codec =
        R"({"protocolVersion":7,"type":"hello","sessionId":"s","generation":1,)"
        R"("profileId":"2k30",)"
        R"("codec":"av1","codedWidth":2560,"codedHeight":1440,)"
        R"("rotationDegrees":0,"fps":30,"targetBitrateBps":18000000,"senderRtcpPort":55033})";
    require(!cambridge::decode_control_message(wrong_codec, message, error));

    const std::string old_protocol =
        R"({"protocolVersion":3,"type":"hello","sessionId":"s","generation":1,)"
        R"("profileId":"2k30",)"
        R"("codec":"h264","width":1280,"height":720,"fps":30,"targetBitrateBps":4000000,"senderRtcpPort":55033})";
    require(!cambridge::decode_control_message(old_protocol, message, error));

    const std::string malformed_geometry =
        R"({"protocolVersion":7,"type":"hello","sessionId":"s","generation":1,)"
        R"("profileId":"2k30",)"
        R"("codec":"h264","codedWidth":2560,"codedHeight":1440,)"
        R"("rotationDegrees":45,"fps":30,"targetBitrateBps":18000000,"senderRtcpPort":55033})";
    require(!cambridge::decode_control_message(malformed_geometry, message, error));
}

void test_reverse_orientations_are_valid()
{
    for (const int rotation : {0, 90, 180, 270}) {
        const std::string json =
            "{\"protocolVersion\":7,\"type\":\"hello\",\"sessionId\":\"s\",\"generation\":1,"
            "\"profileId\":\"2k30\",\"codec\":\"h264\",\"codedWidth\":2560,\"codedHeight\":1440,"
            "\"rotationDegrees\":" +
            std::to_string(rotation) + ",\"fps\":30,\"targetBitrateBps\":18000000,\"senderRtcpPort\":55033}";
        cambridge::ControlMessage message;
        std::string error;
        require(cambridge::decode_control_message(json, message, error));
        require(message.hello.rotation_degrees == static_cast<std::uint32_t>(rotation));
    }
}

void test_phone_authored_bitrates_are_not_preset_validated()
{
    const std::uint32_t safe_width = 1920;
    const std::uint32_t safe_height = 1080;
    const std::uint32_t safe_fps = 24;
    const std::uint32_t intermediate_bitrate = 11'000'000;
    for (const std::uint32_t bitrate : {
             cambridge::contract::kMinimumBitrateBps,
             18'000'000U,
             cambridge::contract::kMaximumBitrateBps,
             intermediate_bitrate,
         }) {
        cambridge::ControlMessage message;
        std::string error;
        require(cambridge::decode_control_message(
            hello_json(safe_width, safe_height, 0, safe_fps, bitrate), message, error));
        require(message.hello.target_bitrate_bps == bitrate);
        require(message.hello.fps == safe_fps);
        require(message.hello.profile_id == "phone-authored-mode");
    }
}

void test_global_bounds_and_alignment_are_enforced_without_ui_presets()
{
    cambridge::ControlMessage message;
    std::string error;
    require(!cambridge::decode_control_message(
        hello_json(1920, 1080, 0, 24, cambridge::contract::kMinimumBitrateBps - 1), message, error));
    require(!cambridge::decode_control_message(
        hello_json(1920, 1080, 0, 24, cambridge::contract::kMaximumBitrateBps + 1), message, error));
    require(!cambridge::decode_control_message(hello_json(1921, 1080, 0, 24, 11'000'000), message, error));
    require(!cambridge::decode_control_message(
        hello_json(3840, 2160, 0, cambridge::contract::kMaximumFps + 1, 11'000'000), message, error));
}

void test_accepted_uses_shape_independent_bounds()
{
    const auto accepted = cambridge::encode_accepted_message(
        "session-1", 7, "2k30", cambridge::contract::kDefaultMediaRtpPort,
        cambridge::contract::kDefaultMediaRtcpPort,
        cambridge::contract::kMaximumLongEdge, cambridge::contract::kMaximumShortEdge);
    require(accepted.find("maxLongEdge") != std::string::npos);
    require(accepted.find("maxShortEdge") != std::string::npos);
    require(accepted.find("maxWidth") == std::string::npos);
    require(accepted.find("maxHeight") == std::string::npos);
}

void test_probe_and_capabilities_round_trip()
{
    const std::string probe =
        R"({"protocolVersion":7,"type":"probe","requestId":"request-1"})";
    cambridge::ControlMessage message;
    std::string error;
    require(cambridge::decode_control_message(probe, message, error));
    require(message.type == cambridge::contract::kMessageProbe);
    require(message.request_id == "request-1");

    const auto capabilities = cambridge::encode_capabilities_message(
        "request-1", cambridge::contract::kDefaultReceiverId,
        cambridge::contract::kDefaultReceiverDisplayName,
        cambridge::contract::kMaximumLongEdge, cambridge::contract::kMaximumShortEdge);
    require(capabilities.find("\"type\":\"capabilities\"") != std::string::npos);
    require(capabilities.find("\"requestId\":\"request-1\"") != std::string::npos);
    require(capabilities.find("\"receiverId\":\"cambridge-obs-source\"") != std::string::npos);
    require(capabilities.find("profiles") == std::string::npos);
}

void test_control_frame_has_big_endian_length_prefix()
{
    const auto frame = cambridge::frame_control_message("{}");
    require(frame.size() == cambridge::contract::kControlHeaderBytes + 2);
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
    test_phone_authored_bitrates_are_not_preset_validated();
    test_global_bounds_and_alignment_are_enforced_without_ui_presets();
    test_accepted_uses_shape_independent_bounds();
    test_probe_and_capabilities_round_trip();
    test_control_frame_has_big_endian_length_prefix();
    return 0;
}
