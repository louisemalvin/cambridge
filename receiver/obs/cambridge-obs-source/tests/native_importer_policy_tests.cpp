#include "../src/platform/interfaces/native_frame_importer.hpp"

#include <cstdlib>
#include <string>

namespace {

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

class FakeNativeFrame final : public cambridge::NativeFrame {
};

class FaultInjectingImporter final : public cambridge::NativeFrameImporter {
public:
    cambridge::NativeSetupResult prepare(std::uint32_t, std::uint32_t) override
    {
        prepared_ = true;
        return {cambridge::NativeSetupStatus::Ready, {}};
    }

    cambridge::NativeImportResult import_frame(const cambridge::NativeFramePtr &,
                                               std::uint64_t) override
    {
        require(prepared_);
        ++import_calls_;
        return {nullptr, 0, "forced native import failure"};
    }

    void reset() override { prepared_ = false; }

    [[nodiscard]] std::size_t import_calls() const { return import_calls_; }

private:
    bool prepared_ = false;
    std::size_t import_calls_ = 0;
};

void test_forced_import_failure_cannot_become_cpu_output()
{
    FaultInjectingImporter importer;
    require(importer.prepare(1280, 720).status == cambridge::NativeSetupStatus::Ready);
    const cambridge::NativeFramePtr frame = std::make_shared<FakeNativeFrame>();
    const cambridge::NativeImportResult result = importer.import_frame(frame, 1);
    require(importer.import_calls() == 1U);
    require(!result.imported_texture);
    require(result.gpu_copy_count == 0U);
    require(result.error == "forced native import failure");
}

} // namespace

int main()
{
    test_forced_import_failure_cannot_become_cpu_output();
    return 0;
}
