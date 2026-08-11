#include "../src/media_path.hpp"

#include <cstdlib>
#include <atomic>
#include <optional>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr std::uint64_t kFirstGeneration = 41;
constexpr std::uint64_t kSecondGeneration = 42;
constexpr std::size_t kRepeatedLifecycleCount = 128;
constexpr std::size_t kConcurrentFailureProducerCount = 16;

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

void require_decision(cambridge::DecoderMode mode,
                      const std::optional<cambridge::NativeSetupResult> &native_setup,
                      bool accepted, cambridge::SessionMediaPath path)
{
    const cambridge::MediaPathDecision decision = cambridge::decide_media_path(mode, native_setup);
    require(decision.accepted == accepted);
    require(decision.path == path);
}

void test_every_decoder_decision_row()
{
    using cambridge::DecoderMode;
    using cambridge::NativeSetupResult;
    using cambridge::NativeSetupStatus;
    using cambridge::SessionMediaPath;

    require_decision(DecoderMode::Software, std::nullopt, true, SessionMediaPath::Software);
    require_decision(DecoderMode::Software,
                     NativeSetupResult{NativeSetupStatus::Ready, "unexpected"}, false,
                     SessionMediaPath::Failed);
    require_decision(DecoderMode::Automatic,
                     NativeSetupResult{NativeSetupStatus::Ready, "ready"}, true,
                     SessionMediaPath::Native);
    require_decision(DecoderMode::Automatic,
                     NativeSetupResult{NativeSetupStatus::Unsupported, "unsupported"}, true,
                     SessionMediaPath::Software);
    require_decision(DecoderMode::Automatic,
                     NativeSetupResult{NativeSetupStatus::Failed, "failed"}, false,
                     SessionMediaPath::Failed);
    require_decision(DecoderMode::Automatic, std::nullopt, false, SessionMediaPath::Failed);
    require_decision(DecoderMode::NativeRequired,
                     NativeSetupResult{NativeSetupStatus::Ready, "ready"}, true,
                     SessionMediaPath::Native);
    require_decision(DecoderMode::NativeRequired,
                     NativeSetupResult{NativeSetupStatus::Unsupported, "unsupported"}, false,
                     SessionMediaPath::Failed);
    require_decision(DecoderMode::NativeRequired,
                     NativeSetupResult{NativeSetupStatus::Failed, "failed"}, false,
                     SessionMediaPath::Failed);
    require_decision(DecoderMode::NativeRequired, std::nullopt, false,
                     SessionMediaPath::Failed);
}

void test_only_first_failure_for_active_generation_is_retained()
{
    cambridge::PendingMediaPathFailureQueue failures;
    failures.activate(kFirstGeneration);
    require(failures.post(
        {kFirstGeneration, cambridge::MediaPathFailureCode::Decode, "first"}));
    require(!failures.post(
        {kFirstGeneration, cambridge::MediaPathFailureCode::NativeImport, "second"}));

    const auto pending = failures.take();
    require(pending.has_value());
    require(pending->stream_generation == kFirstGeneration);
    require(pending->code == cambridge::MediaPathFailureCode::Decode);
    require(pending->detail == "first");
    require(!failures.take().has_value());
}

void test_old_generation_cannot_fail_new_generation()
{
    cambridge::PendingMediaPathFailureQueue failures;
    failures.activate(kFirstGeneration);
    require(failures.post(
        {kFirstGeneration, cambridge::MediaPathFailureCode::Decode, "old"}));
    failures.activate(kSecondGeneration);
    require(!failures.post(
        {kFirstGeneration, cambridge::MediaPathFailureCode::Decode, "late old"}));
    require(failures.post(
        {kSecondGeneration, cambridge::MediaPathFailureCode::NativeConversion, "new"}));
    const auto pending = failures.take();
    require(pending.has_value());
    require(pending->stream_generation == kSecondGeneration);
}

void test_concurrent_failures_retain_exactly_one()
{
    cambridge::PendingMediaPathFailureQueue failures;
    failures.activate(kFirstGeneration);
    std::atomic<std::size_t> retained_count{0};
    std::vector<std::thread> producers;
    producers.reserve(kConcurrentFailureProducerCount);
    for (std::size_t index = 0; index < kConcurrentFailureProducerCount; ++index) {
        producers.emplace_back([&failures, &retained_count, index] {
            if (failures.post({kFirstGeneration, cambridge::MediaPathFailureCode::Decode,
                               "concurrent-" + std::to_string(index)})) {
                retained_count.fetch_add(1);
            }
        });
    }
    for (std::thread &producer : producers) {
        producer.join();
    }
    require(retained_count.load() == 1U);
    require(failures.take().has_value());
    require(!failures.take().has_value());
}

void test_stop_and_repeated_lifecycle_are_idempotent()
{
    cambridge::PendingMediaPathFailureQueue failures;
    failures.activate(kFirstGeneration);
    failures.deactivate();
    failures.deactivate();
    require(!failures.post(
        {kFirstGeneration, cambridge::MediaPathFailureCode::Decode, "after stop"}));

    for (std::size_t index = 0; index < kRepeatedLifecycleCount; ++index) {
        failures.activate(static_cast<std::uint64_t>(index) + kSecondGeneration);
        failures.deactivate();
    }
    require(!failures.take().has_value());
}

} // namespace

int main()
{
    test_every_decoder_decision_row();
    test_only_first_failure_for_active_generation_is_retained();
    test_old_generation_cannot_fail_new_generation();
    test_concurrent_failures_retain_exactly_one();
    test_stop_and_repeated_lifecycle_are_idempotent();
    return 0;
}
