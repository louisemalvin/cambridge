#include "../src/media_path.hpp"

#include <cstdlib>
#include <optional>

namespace {

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

struct FakeDecoderPreparation {
    std::size_t native_calls = 0;
    std::size_t software_calls = 0;
    bool path_locked = false;

    cambridge::NativeSetupResult prepare_native(cambridge::NativeSetupStatus status)
    {
        require(!path_locked);
        ++native_calls;
        return {status, "fake native result"};
    }

    bool prepare_software()
    {
        ++software_calls;
        return true;
    }
};

void test_software_never_prepares_native()
{
    FakeDecoderPreparation decoder;
    const auto decision = cambridge::decide_media_path(cambridge::DecoderMode::Software, std::nullopt);
    require(decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Software);
    require(decoder.native_calls == 0U);
    require(decoder.prepare_software());
    require(decoder.software_calls == 1U);
}

void test_automatic_unsupported_prepares_software_once()
{
    FakeDecoderPreparation decoder;
    const auto native = decoder.prepare_native(cambridge::NativeSetupStatus::Unsupported);
    const auto decision = cambridge::decide_media_path(cambridge::DecoderMode::Automatic, native);
    require(decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Software);
    require(decoder.native_calls == 1U);
    require(decoder.prepare_software());
    require(decoder.software_calls == 1U);
}

void test_automatic_failed_does_not_prepare_software()
{
    FakeDecoderPreparation decoder;
    const auto native = decoder.prepare_native(cambridge::NativeSetupStatus::Failed);
    const auto decision = cambridge::decide_media_path(cambridge::DecoderMode::Automatic, native);
    require(!decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Failed);
    require(decoder.native_calls == 1U);
    require(decoder.software_calls == 0U);
}

void test_native_required_never_falls_back()
{
    FakeDecoderPreparation decoder;
    const auto native = decoder.prepare_native(cambridge::NativeSetupStatus::Unsupported);
    const auto decision = cambridge::decide_media_path(cambridge::DecoderMode::NativeRequired, native);
    require(!decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Failed);
    require(decoder.native_calls == 1U);
    require(decoder.software_calls == 0U);
}

void test_failure_after_activation_never_reopens_a_decoder()
{
    FakeDecoderPreparation decoder;
    const auto native = decoder.prepare_native(cambridge::NativeSetupStatus::Ready);
    const auto decision = cambridge::decide_media_path(cambridge::DecoderMode::Automatic, native);
    require(decision.accepted);
    require(decision.path == cambridge::SessionMediaPath::Native);

    decoder.path_locked = true;
    require(decoder.native_calls == 1U);
    require(decoder.software_calls == 0U);
}

} // namespace

int main()
{
    test_software_never_prepares_native();
    test_automatic_unsupported_prepares_software_once();
    test_automatic_failed_does_not_prepare_software();
    test_native_required_never_falls_back();
    test_failure_after_activation_never_reopens_a_decoder();
    return 0;
}
