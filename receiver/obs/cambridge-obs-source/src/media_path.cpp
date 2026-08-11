#include "media_path.hpp"

#include <utility>

namespace cambridge {
namespace {

constexpr std::string_view kAutomaticValue = "auto";
constexpr std::string_view kNativeRequiredValue = "native_required";
constexpr std::string_view kSoftwareValue = "cpu";
constexpr std::string_view kUnspecifiedReason = "unspecified";

std::string setup_reason(const NativeSetupResult &setup)
{
    return setup.reason.empty() ? std::string(kUnspecifiedReason) : setup.reason;
}

} // namespace

DecoderMode parse_decoder_mode(std::string_view stored_value)
{
    if (stored_value == kNativeRequiredValue) {
        return DecoderMode::NativeRequired;
    }
    if (stored_value == kSoftwareValue) {
        return DecoderMode::Software;
    }
    return DecoderMode::Automatic;
}

bool is_known_decoder_mode(std::string_view stored_value)
{
    return stored_value == kAutomaticValue || stored_value == kNativeRequiredValue ||
           stored_value == kSoftwareValue;
}

MediaPathDecision decide_media_path(DecoderMode requested_mode,
                                    const std::optional<NativeSetupResult> &native_setup)
{
    if (requested_mode == DecoderMode::Software) {
        if (native_setup) {
            return {SessionMediaPath::Failed, false, {}, "software_mode_received_native_setup"};
        }
        return {SessionMediaPath::Software, true, "software_selected", {}};
    }
    if (!native_setup) {
        return {SessionMediaPath::Failed, false, {}, "native_setup_missing"};
    }

    const NativeSetupResult &setup = *native_setup;
    const std::string reason = setup_reason(setup);
    if (setup.status == NativeSetupStatus::Ready) {
        return {SessionMediaPath::Native, true, "native_selected", {}};
    }
    if (setup.status == NativeSetupStatus::Unsupported) {
        if (requested_mode == DecoderMode::Automatic) {
            return {SessionMediaPath::Software, true,
                    "native_unsupported_selecting_software:" + reason, {}};
        }
        return {SessionMediaPath::Failed, false, {}, "native_required_unavailable:" + reason};
    }
    return {SessionMediaPath::Failed, false, {}, "native_setup_failed:" + reason};
}

bool frame_storage_matches_media_path(SessionMediaPath path, FrameStorageKind storage_kind)
{
    if (path == SessionMediaPath::Native) {
        return storage_kind == FrameStorageKind::Native;
    }
    if (path == SessionMediaPath::Software) {
        return storage_kind == FrameStorageKind::CpuNv12;
    }
    return false;
}

void PendingMediaPathFailureQueue::activate(std::uint64_t stream_generation)
{
    std::lock_guard<std::mutex> lock(mutex_);
    active_generation_ = stream_generation;
    pending_.reset();
}

void PendingMediaPathFailureQueue::deactivate()
{
    std::lock_guard<std::mutex> lock(mutex_);
    active_generation_ = kInactiveStreamGeneration;
    pending_.reset();
}

bool PendingMediaPathFailureQueue::post(PendingMediaPathFailure failure)
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (failure.stream_generation == kInactiveStreamGeneration ||
        failure.stream_generation != active_generation_ || pending_) {
        return false;
    }
    pending_ = std::move(failure);
    return true;
}

std::optional<PendingMediaPathFailure> PendingMediaPathFailureQueue::take()
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (!pending_ || pending_->stream_generation != active_generation_) {
        pending_.reset();
        return std::nullopt;
    }
    std::optional<PendingMediaPathFailure> result = std::move(pending_);
    pending_.reset();
    return result;
}

std::string_view decoder_mode_name(DecoderMode mode)
{
    switch (mode) {
    case DecoderMode::Automatic:
        return kAutomaticValue;
    case DecoderMode::NativeRequired:
        return kNativeRequiredValue;
    case DecoderMode::Software:
        return kSoftwareValue;
    }
    return kAutomaticValue;
}

std::string_view session_media_path_name(SessionMediaPath path)
{
    switch (path) {
    case SessionMediaPath::Unselected:
        return "unselected";
    case SessionMediaPath::Native:
        return "native";
    case SessionMediaPath::Software:
        return "software";
    case SessionMediaPath::Failed:
        return "failed";
    }
    return "failed";
}

std::string_view native_setup_status_name(NativeSetupStatus status)
{
    switch (status) {
    case NativeSetupStatus::Ready:
        return "ready";
    case NativeSetupStatus::Unsupported:
        return "unsupported";
    case NativeSetupStatus::Failed:
        return "failed";
    }
    return "failed";
}

std::string_view media_path_failure_code_name(MediaPathFailureCode code)
{
    switch (code) {
    case MediaPathFailureCode::Decode:
        return "decode";
    case MediaPathFailureCode::NativeExport:
        return "native_export";
    case MediaPathFailureCode::NativeImport:
        return "native_import";
    case MediaPathFailureCode::NativeConversion:
        return "native_conversion";
    case MediaPathFailureCode::SoftwareUpload:
        return "software_upload";
    }
    return "decode";
}

} // namespace cambridge
