#include "../src/media_path.hpp"

#include <cstdlib>
#include <optional>
#include <string>

namespace {

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

cambridge::NativeSetupResult setup(cambridge::NativeSetupStatus status, const char *reason)
{
    return {status, reason};
}

void test_automatic_ready_selects_native()
{
    const auto decision = cambridge::decide_media_path(
        cambridge::DecoderMode::Automatic, setup(cambridge::NativeSetupStatus::Ready, "ready"));
    require(decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Native);
    require(decision.error.empty());
}

void test_automatic_unsupported_selects_software_once()
{
    const auto decision = cambridge::decide_media_path(
        cambridge::DecoderMode::Automatic, setup(cambridge::NativeSetupStatus::Unsupported, "no dmabuf"));
    require(decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Software);
    require(decision.event == "native_unsupported_selecting_software:no dmabuf");
}

void test_automatic_failed_rejects_start()
{
    const auto decision = cambridge::decide_media_path(
        cambridge::DecoderMode::Automatic, setup(cambridge::NativeSetupStatus::Failed, "allocation"));
    require(!decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Failed);
    require(decision.error == "native_setup_failed:allocation");
}

void test_native_required_accepts_only_ready()
{
    const auto ready = cambridge::decide_media_path(
        cambridge::DecoderMode::NativeRequired, setup(cambridge::NativeSetupStatus::Ready, "ready"));
    require(ready.accepted);
    require(ready.path == cambridge::SessionMediaPath::Native);

    const auto unsupported = cambridge::decide_media_path(
        cambridge::DecoderMode::NativeRequired, setup(cambridge::NativeSetupStatus::Unsupported, "no vaapi"));
    require(!unsupported.accepted);
    require(unsupported.path == cambridge::SessionMediaPath::Failed);

    const auto failed = cambridge::decide_media_path(
        cambridge::DecoderMode::NativeRequired, setup(cambridge::NativeSetupStatus::Failed, "api error"));
    require(!failed.accepted);
    require(failed.path == cambridge::SessionMediaPath::Failed);
}

void test_software_requires_no_native_setup()
{
    const auto decision = cambridge::decide_media_path(cambridge::DecoderMode::Software, std::nullopt);
    require(decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Software);

    const auto invalid = cambridge::decide_media_path(
        cambridge::DecoderMode::Software, setup(cambridge::NativeSetupStatus::Ready, "must not be used"));
    require(!invalid.accepted);
}

void test_missing_native_setup_is_a_failure()
{
    const auto automatic = cambridge::decide_media_path(cambridge::DecoderMode::Automatic, std::nullopt);
    const auto required = cambridge::decide_media_path(cambridge::DecoderMode::NativeRequired, std::nullopt);
    require(!automatic.accepted);
    require(!required.accepted);
    require(automatic.error == "native_setup_missing");
    require(required.error == "native_setup_missing");
}

void test_saved_values_and_unknown_values()
{
    require(cambridge::parse_decoder_mode("auto") == cambridge::DecoderMode::Automatic);
    require(cambridge::parse_decoder_mode("cpu") == cambridge::DecoderMode::Software);
    require(cambridge::parse_decoder_mode("native_required") == cambridge::DecoderMode::NativeRequired);
    require(cambridge::parse_decoder_mode("unknown") == cambridge::DecoderMode::Automatic);
    require(!cambridge::is_known_decoder_mode("unknown"));
}

} // namespace

int main()
{
    test_automatic_ready_selects_native();
    test_automatic_unsupported_selects_software_once();
    test_automatic_failed_rejects_start();
    test_native_required_accepts_only_ready();
    test_software_requires_no_native_setup();
    test_missing_native_setup_is_a_failure();
    test_saved_values_and_unknown_values();
    return 0;
}
