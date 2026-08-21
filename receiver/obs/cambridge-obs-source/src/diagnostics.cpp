#include "diagnostics.hpp"

#include <fstream>
#include <iomanip>
#include <sstream>
#include <string_view>

namespace cambridge {
namespace {

constexpr unsigned char kJsonControlCharacterLimit = 0x20U;
constexpr unsigned char kJsonHexMask = 0x0FU;
constexpr unsigned char kJsonHexShift = 4U;
constexpr char kJsonHexDigits[] = "0123456789ABCDEF";

std::string json_escape(std::string_view value)
{
    std::string escaped;
    escaped.reserve(value.size());
    for (const char raw_character : value) {
        const unsigned char character = static_cast<unsigned char>(raw_character);
        switch (character) {
        case '"':
            escaped += "\\\"";
            break;
        case '\\':
            escaped += "\\\\";
            break;
        case '\b':
            escaped += "\\b";
            break;
        case '\f':
            escaped += "\\f";
            break;
        case '\n':
            escaped += "\\n";
            break;
        case '\r':
            escaped += "\\r";
            break;
        case '\t':
            escaped += "\\t";
            break;
        default:
            if (character < kJsonControlCharacterLimit) {
                escaped += "\\u00";
                escaped += kJsonHexDigits[(character >> kJsonHexShift) & kJsonHexMask];
                escaped += kJsonHexDigits[character & kJsonHexMask];
            } else {
                escaped += static_cast<char>(character);
            }
            break;
        }
    }
    return escaped;
}

void write_string(std::ostream &output, std::string_view value)
{
    output << '"' << json_escape(value) << '"';
}

void write_bool(std::ostream &output, bool value)
{
    output << (value ? "true" : "false");
}

} // namespace

bool write_diagnostics(const DiagnosticsSnapshot &snapshot, const std::string &path,
                      std::string &error)
{
    std::ofstream output(path, std::ios::trunc);
    if (!output) {
        error = "could not open diagnostics path";
        return false;
    }

    output << "{\n"
           << "  \"module\": ";
    write_string(output, snapshot.module);
    output << ",\n  \"version\": ";
    write_string(output, snapshot.version);
    output << ",\n  \"gitCommit\": ";
    write_string(output, snapshot.git_commit);
    output << ",\n  \"protocolVersion\": " << snapshot.protocol_version
           << ",\n  \"state\": ";
    write_string(output, snapshot.state);
    output << ",\n  \"codedWidth\": " << snapshot.coded_width
           << ",\n  \"codedHeight\": " << snapshot.coded_height
           << ",\n  \"displayWidth\": " << snapshot.display_width
           << ",\n  \"displayHeight\": " << snapshot.display_height
           << ",\n  \"rotationDegrees\": " << snapshot.rotation_degrees
           << ",\n  \"decoder\": ";
    write_string(output, snapshot.decoder);
    output << ",\n  \"render\": ";
    write_string(output, snapshot.render);
    output << ",\n  \"mailboxOccupancy\": " << snapshot.mailbox_occupancy
           << ",\n  \"mailboxMaximum\": " << snapshot.mailbox_maximum
           << ",\n  \"framesReplaced\": " << snapshot.frames_replaced
           << ",\n  \"framesStale\": " << snapshot.frames_stale
           << ",\n  \"framesDecoded\": " << snapshot.frames_decoded
           << ",\n  \"framesRendered\": " << snapshot.frames_rendered
           << ",\n  \"hardwareCpuTransfers\": " << snapshot.hardware_cpu_transfers
           << ",\n  \"requestedDecoderMode\": ";
    write_string(output, snapshot.requested_decoder_mode);
    output << ",\n  \"sessionMediaPath\": ";
    write_string(output, snapshot.session_media_path);
    output << ",\n  \"mediaPathLocked\": ";
    write_bool(output, snapshot.media_path_locked);
    output << ",\n  \"nativeSetupStatus\": ";
    write_string(output, snapshot.native_setup_status);
    output << ",\n  \"nativeSetupReason\": ";
    write_string(output, snapshot.native_setup_reason);
    output << ",\n  \"mediaPathFailures\": " << snapshot.media_path_failures
           << ",\n  \"lastMediaPathFailureCode\": ";
    write_string(output, snapshot.last_media_path_failure_code);
    output << ",\n  \"lastMediaPathFailureDetail\": ";
    write_string(output, snapshot.last_media_path_failure_detail);
    output << ",\n  \"nativeImportFailures\": " << snapshot.native_import_failures
           << ",\n  \"nativePoolExhaustions\": " << snapshot.native_pool_exhaustions
           << ",\n  \"cpuFrameCopies\": " << snapshot.cpu_frame_copies
           << ",\n  \"gpuCopies\": " << snapshot.gpu_copies
           << ",\n  \"dmaBufImportFailures\": " << snapshot.dma_buf_import_failures
           << ",\n  \"accessUnitsDelivered\": " << snapshot.access_units_delivered
           << ",\n  \"accessUnitBytesDelivered\": " << snapshot.access_unit_bytes_delivered
           << ",\n  \"transportErrors\": " << snapshot.transport_errors
           << ",\n  \"decodeFailures\": " << snapshot.decode_failures
           << ",\n  \"decoderQueueDrops\": " << snapshot.decoder_queue_drops
           << ",\n  \"decoderQueueOccupancy\": " << snapshot.decoder_queue_occupancy
           << ",\n  \"maxReceiveToDecodeMs\": " << snapshot.max_receive_to_decode_ms
           << ",\n  \"maxReceiveToPublishMs\": " << snapshot.max_receive_to_publish_ms
           << ",\n  \"maxReceiveToRenderMs\": " << snapshot.max_receive_to_render_ms
           << ",\n  \"configured\": {\"controlPort\": " << snapshot.configured_control_port
           << ", \"mediaRtpPort\": " << snapshot.configured_media_rtp_port
           << ", \"mediaRtcpPort\": " << snapshot.configured_media_rtcp_port
           << ", \"maximumDecoderQueueAgeMs\": "
           << snapshot.configured_maximum_decoder_queue_age_ms
           << ", \"maximumLiveFrameAgeMs\": "
           << snapshot.configured_maximum_live_frame_age_ms << "}\n"
           << "}\n";
    if (!output) {
        error = "could not write diagnostics path";
        return false;
    }
    return true;
}

} // namespace cambridge
