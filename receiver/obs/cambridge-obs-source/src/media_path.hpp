#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>

namespace cambridge {

enum class DecoderMode {
    Automatic,
    NativeRequired,
    Software,
};

enum class SessionMediaPath {
    Unselected,
    Native,
    Software,
    Failed,
};

enum class NativeSetupStatus {
    Ready,
    Unsupported,
    Failed,
};

enum class MediaPathFailureCode {
    Decode,
    NativeExport,
    NativeImport,
    NativeConversion,
    SoftwareUpload,
};

struct NativeSetupResult {
    NativeSetupStatus status = NativeSetupStatus::Failed;
    std::string reason;
};

struct MediaPathDecision {
    SessionMediaPath path = SessionMediaPath::Failed;
    bool accepted = false;
    std::string event;
    std::string error;
};

using MediaPathFailureCallback =
    std::function<void(std::uint64_t, MediaPathFailureCode, const std::string &)>;

DecoderMode parse_decoder_mode(std::string_view stored_value);
bool is_known_decoder_mode(std::string_view stored_value);

MediaPathDecision decide_media_path(
    DecoderMode requested_mode,
    const std::optional<NativeSetupResult> &native_setup);

std::string_view decoder_mode_name(DecoderMode mode);
std::string_view session_media_path_name(SessionMediaPath path);
std::string_view native_setup_status_name(NativeSetupStatus status);
std::string_view media_path_failure_code_name(MediaPathFailureCode code);

} // namespace cambridge
