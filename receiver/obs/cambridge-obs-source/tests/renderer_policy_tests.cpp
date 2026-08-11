#include "../src/media_path.hpp"

#include <cstdlib>
#include <string>
#include <utility>

namespace {

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

class FakeRenderer {
public:
    explicit FakeRenderer(cambridge::MediaPathFailureCallback on_failure)
        : on_failure_(std::move(on_failure))
    {
    }

    void activate(cambridge::SessionMediaPath path)
    {
        path_ = path;
        failed_generation_ = 0;
    }

    bool present(std::uint64_t generation, bool native_storage)
    {
        if (failed_generation_ == generation) {
            return false;
        }
        const bool expected_native = path_ == cambridge::SessionMediaPath::Native;
        if (path_ == cambridge::SessionMediaPath::Unselected || expected_native != native_storage) {
            fail(generation, expected_native ? cambridge::MediaPathFailureCode::NativeImport
                                              : cambridge::MediaPathFailureCode::SoftwareUpload,
                 "storage does not match the locked path");
            return false;
        }
        ++present_calls_;
        return true;
    }

    [[nodiscard]] std::size_t present_calls() const { return present_calls_; }

private:
    void fail(std::uint64_t generation, cambridge::MediaPathFailureCode code, const std::string &detail)
    {
        if (failed_generation_ == generation) {
            return;
        }
        failed_generation_ = generation;
        if (on_failure_) {
            on_failure_(generation, code, detail);
        }
    }

    cambridge::SessionMediaPath path_ = cambridge::SessionMediaPath::Unselected;
    std::uint64_t failed_generation_ = 0;
    std::size_t present_calls_ = 0;
    cambridge::MediaPathFailureCallback on_failure_;
};

void test_native_import_failure_is_idempotent_and_never_software()
{
    std::size_t failures = 0;
    cambridge::MediaPathFailureCode failure_code = cambridge::MediaPathFailureCode::Decode;
    FakeRenderer renderer([&](std::uint64_t, cambridge::MediaPathFailureCode code, const std::string &) {
        ++failures;
        failure_code = code;
    });
    renderer.activate(cambridge::SessionMediaPath::Native);

    require(!renderer.present(7, false));
    require(!renderer.present(7, true));
    require(failures == 1U);
    require(failure_code == cambridge::MediaPathFailureCode::NativeImport);
    require(renderer.present_calls() == 0U);
}

void test_software_path_rejects_native_storage()
{
    std::size_t failures = 0;
    FakeRenderer renderer([&](std::uint64_t, cambridge::MediaPathFailureCode code, const std::string &) {
        require(code == cambridge::MediaPathFailureCode::SoftwareUpload);
        ++failures;
    });
    renderer.activate(cambridge::SessionMediaPath::Software);
    require(!renderer.present(11, true));
    require(failures == 1U);
}

void test_new_generation_can_present_after_old_failure()
{
    FakeRenderer renderer([](std::uint64_t, cambridge::MediaPathFailureCode, const std::string &) {});
    renderer.activate(cambridge::SessionMediaPath::Native);
    require(!renderer.present(13, false));
    renderer.activate(cambridge::SessionMediaPath::Native);
    require(renderer.present(14, true));
    require(renderer.present_calls() == 1U);
}

} // namespace

int main()
{
    test_native_import_failure_is_idempotent_and_never_software();
    test_software_path_rejects_native_storage();
    test_new_generation_can_present_after_old_failure();
    return 0;
}
