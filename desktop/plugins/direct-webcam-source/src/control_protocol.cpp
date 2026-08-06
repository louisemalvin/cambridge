#include "control_protocol.hpp"

#include "protocol_contract.hpp"

#include <jansson.h>

#include <algorithm>
#include <limits>

namespace direct_webcam {
namespace {

bool get_string(json_t *object, const char *name, std::string &value, std::string &error,
                std::size_t maximum_bytes)
{
    json_t *item = json_object_get(object, name);
    if (!json_is_string(item)) {
        error = std::string("control field is not a string: ") + name;
        return false;
    }
    const char *raw = json_string_value(item);
    if (!raw || raw[0] == '\0' || std::char_traits<char>::length(raw) > maximum_bytes) {
        error = std::string("control string field is empty or oversized: ") + name;
        return false;
    }
    value = raw;
    return true;
}

bool get_unsigned(json_t *object, const char *name, std::uint64_t &value, std::string &error,
                  std::uint64_t minimum, std::uint64_t maximum)
{
    json_t *item = json_object_get(object, name);
    if (!json_is_integer(item)) {
        error = std::string("control field is not an integer: ") + name;
        return false;
    }
    const json_int_t raw = json_integer_value(item);
    if (raw < 0 || static_cast<std::uint64_t>(raw) < minimum || static_cast<std::uint64_t>(raw) > maximum) {
        error = std::string("control integer field is outside its bound: ") + name;
        return false;
    }
    value = static_cast<std::uint64_t>(raw);
    return true;
}

std::string dump_json(json_t *object)
{
    char *dumped = json_dumps(object, JSON_COMPACT | JSON_SORT_KEYS);
    if (!dumped) {
        return "{}";
    }
    std::string result(dumped);
    free(dumped);
    return result;
}

bool valid_geometry(std::uint32_t coded_width, std::uint32_t coded_height, std::uint32_t display_width,
                    std::uint32_t display_height, std::uint32_t rotation_degrees)
{
    const auto long_edge = [](std::uint32_t width, std::uint32_t height) {
        return std::max(width, height);
    };
    const auto short_edge = [](std::uint32_t width, std::uint32_t height) {
        return std::min(width, height);
    };
    if (coded_width % contract::kDimensionAlignment != 0 ||
        coded_height % contract::kDimensionAlignment != 0 ||
        display_width % contract::kDimensionAlignment != 0 ||
        display_height % contract::kDimensionAlignment != 0 ||
        long_edge(coded_width, coded_height) > contract::kMaximumLongEdge ||
        short_edge(coded_width, coded_height) > contract::kMaximumShortEdge ||
        long_edge(display_width, display_height) > contract::kMaximumLongEdge ||
        short_edge(display_width, display_height) > contract::kMaximumShortEdge) {
        return false;
    }
    const bool swaps_geometry = rotation_degrees == 90 || rotation_degrees == 270;
    if (rotation_degrees != 0 && rotation_degrees != 90 && rotation_degrees != 180 && rotation_degrees != 270) {
        return false;
    }
    const std::uint32_t expected_width = swaps_geometry ? coded_height : coded_width;
    const std::uint32_t expected_height = swaps_geometry ? coded_width : coded_height;
    return display_width == expected_width && display_height == expected_height;
}

} // namespace

bool decode_control_message(const std::string &json, ControlMessage &message, std::string &error)
{
    if (json.size() > contract::kMaximumControlMessageBytes) {
        error = "control message exceeds the maximum size";
        return false;
    }
    json_error_t parse_error{};
    json_t *root = json_loadb(json.data(), json.size(), JSON_REJECT_DUPLICATES, &parse_error);
    if (!root || !json_is_object(root)) {
        if (root) {
            json_decref(root);
        }
        error = "control message is not a JSON object";
        return false;
    }

    std::uint64_t protocol_version = 0;
    if (!get_unsigned(root, "protocolVersion", protocol_version, error, contract::kProtocolVersion,
                      contract::kProtocolVersion)) {
        json_decref(root);
        return false;
    }
    if (!get_string(root, "type", message.type, error, contract::kMaximumSessionIdBytes)) {
        json_decref(root);
        return false;
    }

    if (message.type == contract::kMessageHello) {
        message.hello.session_id.clear();
        message.hello.codec.clear();
        if (!get_string(root, "sessionId", message.hello.session_id, error, contract::kMaximumSessionIdBytes) ||
            !get_unsigned(root, "generation", message.hello.generation, error, contract::kMinimumGeneration,
                          std::numeric_limits<std::uint64_t>::max()) ||
            !get_string(root, "codec", message.hello.codec, error, contract::kMaximumSessionIdBytes)) {
            json_decref(root);
            return false;
        }
        if (message.hello.codec != contract::kCodecH264) {
            error = "only H.264 control sessions are supported";
            json_decref(root);
            return false;
        }
        std::uint64_t coded_width = 0;
        std::uint64_t coded_height = 0;
        std::uint64_t display_width = 0;
        std::uint64_t display_height = 0;
        std::uint64_t rotation_degrees = 0;
        std::uint64_t fps = 0;
        std::uint64_t bitrate = 0;
        if (!get_unsigned(root, "codedWidth", coded_width, error, contract::kMinimumDimension,
                          contract::kMaximumLongEdge) ||
            !get_unsigned(root, "codedHeight", coded_height, error, contract::kMinimumDimension,
                          contract::kMaximumLongEdge) ||
            !get_unsigned(root, "displayWidth", display_width, error, contract::kMinimumDimension,
                          contract::kMaximumLongEdge) ||
            !get_unsigned(root, "displayHeight", display_height, error, contract::kMinimumDimension,
                          contract::kMaximumLongEdge) ||
            !get_unsigned(root, "rotationDegrees", rotation_degrees, error, 0, 270) ||
            !get_unsigned(root, "fps", fps, error, contract::kMinimumFps, contract::kMaximumFps) ||
            !get_unsigned(root, "bitrateBps", bitrate, error, contract::kMinimumBitrateBps,
                          contract::kMaximumBitrateBps)) {
            json_decref(root);
            return false;
        }
        message.hello.coded_width = static_cast<std::uint32_t>(coded_width);
        message.hello.coded_height = static_cast<std::uint32_t>(coded_height);
        message.hello.display_width = static_cast<std::uint32_t>(display_width);
        message.hello.display_height = static_cast<std::uint32_t>(display_height);
        message.hello.rotation_degrees = static_cast<std::uint32_t>(rotation_degrees);
        message.hello.fps = static_cast<std::uint32_t>(fps);
        message.hello.bitrate_bps = static_cast<std::uint32_t>(bitrate);
        if (!valid_geometry(message.hello.coded_width, message.hello.coded_height,
                            message.hello.display_width, message.hello.display_height,
                            message.hello.rotation_degrees)) {
            error = "control geometry is inconsistent or outside its bounds";
            json_decref(root);
            return false;
        }
        message.session_id = message.hello.session_id;
        message.generation = message.hello.generation;
    } else if (message.type == contract::kMessageStop || message.type == contract::kMessageRequestIdr) {
        if (!get_string(root, "sessionId", message.session_id, error, contract::kMaximumSessionIdBytes) ||
            !get_unsigned(root, "generation", message.generation, error, contract::kMinimumGeneration,
                          std::numeric_limits<std::uint64_t>::max())) {
            json_decref(root);
            return false;
        }
    } else {
        error = "unsupported control message type";
        json_decref(root);
        return false;
    }
    json_decref(root);
    return true;
}

std::string encode_accepted_message(const std::string &session_id, std::uint64_t generation,
                                    std::uint32_t media_port, std::uint32_t maximum_long_edge,
                                    std::uint32_t maximum_short_edge)
{
    json_t *root = json_object();
    json_object_set_new(root, "protocolVersion", json_integer(contract::kProtocolVersion));
    json_object_set_new(root, "type", json_string(contract::kMessageAccepted));
    json_object_set_new(root, "sessionId", json_string(session_id.c_str()));
    json_object_set_new(root, "generation", json_integer(static_cast<json_int_t>(generation)));
    json_object_set_new(root, "mediaPort", json_integer(media_port));
    json_object_set_new(root, "maxLongEdge", json_integer(maximum_long_edge));
    json_object_set_new(root, "maxShortEdge", json_integer(maximum_short_edge));
    const std::string result = dump_json(root);
    json_decref(root);
    return result;
}

std::string encode_request_idr_message(const std::string &session_id, std::uint64_t generation)
{
    json_t *root = json_object();
    json_object_set_new(root, "protocolVersion", json_integer(contract::kProtocolVersion));
    json_object_set_new(root, "type", json_string(contract::kMessageRequestIdr));
    json_object_set_new(root, "sessionId", json_string(session_id.c_str()));
    json_object_set_new(root, "generation", json_integer(static_cast<json_int_t>(generation)));
    const std::string result = dump_json(root);
    json_decref(root);
    return result;
}

std::string encode_error_message(const std::string &reason)
{
    const std::string bounded_reason = reason.substr(0, contract::kMaximumErrorBytes);
    json_t *root = json_object();
    json_object_set_new(root, "protocolVersion", json_integer(contract::kProtocolVersion));
    json_object_set_new(root, "type", json_string(contract::kMessageError));
    json_object_set_new(root, "error", json_string(bounded_reason.c_str()));
    const std::string result = dump_json(root);
    json_decref(root);
    return result;
}

std::string encode_status_message(const std::string &session_id, std::uint64_t generation,
                                  const std::string &state, const StatusMetrics &metrics)
{
    json_t *root = json_object();
    json_t *metrics_object = json_object();
    json_object_set_new(root, "protocolVersion", json_integer(contract::kProtocolVersion));
    json_object_set_new(root, "type", json_string(contract::kMessageStatus));
    json_object_set_new(root, "sessionId", json_string(session_id.c_str()));
    json_object_set_new(root, "generation", json_integer(static_cast<json_int_t>(generation)));
    json_object_set_new(root, "state", json_string(state.c_str()));
    json_object_set_new(metrics_object, "framesDecoded", json_integer(static_cast<json_int_t>(metrics.frames_decoded)));
    json_object_set_new(metrics_object, "framesReplaced", json_integer(static_cast<json_int_t>(metrics.frames_replaced)));
    json_object_set_new(metrics_object, "packetsReceived", json_integer(static_cast<json_int_t>(metrics.packets_received)));
    json_object_set_new(metrics_object, "bytesReceived", json_integer(static_cast<json_int_t>(metrics.bytes_received)));
    json_object_set_new(metrics_object, "packetsLost", json_integer(static_cast<json_int_t>(metrics.packets_lost)));
    json_object_set_new(metrics_object, "malformedPackets", json_integer(static_cast<json_int_t>(metrics.malformed_packets)));
    json_object_set_new(metrics_object, "invalidSourcePackets",
                        json_integer(static_cast<json_int_t>(metrics.invalid_source_packets)));
    json_object_set_new(metrics_object, "decodeFailures", json_integer(static_cast<json_int_t>(metrics.decode_failures)));
    json_object_set_new(metrics_object, "decoderQueueDrops",
                        json_integer(static_cast<json_int_t>(metrics.decoder_queue_drops)));
    json_object_set_new(metrics_object, "decoderQueueOccupancy",
                        json_integer(static_cast<json_int_t>(metrics.decoder_queue_occupancy)));
    json_object_set_new(metrics_object, "decoderQueueMaximum",
                        json_integer(static_cast<json_int_t>(metrics.decoder_queue_maximum)));
    json_object_set_new(metrics_object, "reorderOccupancy",
                        json_integer(static_cast<json_int_t>(metrics.reorder_occupancy)));
    json_object_set_new(metrics_object, "reorderMaximum", json_integer(static_cast<json_int_t>(metrics.reorder_maximum)));
    json_object_set_new(metrics_object, "reorderPeak", json_integer(static_cast<json_int_t>(metrics.reorder_peak)));
    json_object_set_new(metrics_object, "reorderDeadlineDrops",
                        json_integer(static_cast<json_int_t>(metrics.reorder_deadline_drops)));
    json_object_set_new(metrics_object, "staleFrames", json_integer(static_cast<json_int_t>(metrics.stale_frames)));
    json_object_set_new(metrics_object, "mailboxOccupancy",
                        json_integer(static_cast<json_int_t>(metrics.mailbox_occupancy)));
    json_object_set_new(metrics_object, "mailboxMaximum",
                        json_integer(static_cast<json_int_t>(metrics.mailbox_maximum)));
    json_object_set_new(metrics_object, "importFailures",
                        json_integer(static_cast<json_int_t>(metrics.import_failures)));
    json_object_set_new(metrics_object, "cpuUploads", json_integer(static_cast<json_int_t>(metrics.cpu_uploads)));
    json_object_set_new(metrics_object, "gpuCopies", json_integer(static_cast<json_int_t>(metrics.gpu_copies)));
    json_object_set_new(metrics_object, "hardwareCpuTransfers",
                        json_integer(static_cast<json_int_t>(metrics.hardware_cpu_transfers)));
    json_object_set_new(metrics_object, "maxReceiveToDecodeMs",
                        json_integer(static_cast<json_int_t>(metrics.max_receive_to_decode_ms)));
    json_object_set_new(metrics_object, "maxReceiveToPublishMs",
                        json_integer(static_cast<json_int_t>(metrics.max_receive_to_publish_ms)));
    json_object_set_new(metrics_object, "maxReceiveToRenderMs",
                        json_integer(static_cast<json_int_t>(metrics.max_receive_to_render_ms)));
    json_object_set_new(metrics_object, "maxLiveFrameAgeMs",
                        json_integer(static_cast<json_int_t>(metrics.max_live_frame_age_ms)));
    json_object_set_new(metrics_object, "framesRendered", json_integer(static_cast<json_int_t>(metrics.frames_rendered)));
    json_object_set_new(metrics_object, "codedWidth", json_integer(metrics.coded_width));
    json_object_set_new(metrics_object, "codedHeight", json_integer(metrics.coded_height));
    json_object_set_new(metrics_object, "displayWidth", json_integer(metrics.display_width));
    json_object_set_new(metrics_object, "displayHeight", json_integer(metrics.display_height));
    json_object_set_new(metrics_object, "rotationDegrees", json_integer(metrics.rotation_degrees));
    json_object_set_new(metrics_object, "width", json_integer(metrics.width));
    json_object_set_new(metrics_object, "height", json_integer(metrics.height));
    json_object_set_new(metrics_object, "fps", json_integer(metrics.fps));
    json_object_set_new(metrics_object, "bitrateBps", json_integer(metrics.bitrate_bps));
    json_object_set_new(metrics_object, "decoder", json_string(metrics.decoder.c_str()));
    json_object_set_new(metrics_object, "renderMode", json_string(metrics.render_mode.c_str()));
    json_object_set_new(root, "metrics", metrics_object);
    const std::string result = dump_json(root);
    json_decref(root);
    return result;
}

std::vector<std::uint8_t> frame_control_message(const std::string &json)
{
    const std::size_t size = json.size();
    std::vector<std::uint8_t> frame(contract::kControlHeaderBytes + size);
    frame[0] = static_cast<std::uint8_t>(size >> 24U);
    frame[1] = static_cast<std::uint8_t>(size >> 16U);
    frame[2] = static_cast<std::uint8_t>(size >> 8U);
    frame[3] = static_cast<std::uint8_t>(size);
    std::copy(json.begin(), json.end(), frame.begin() + contract::kControlHeaderBytes);
    return frame;
}

} // namespace direct_webcam
