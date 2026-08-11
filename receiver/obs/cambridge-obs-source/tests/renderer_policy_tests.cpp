#include "../src/media_path.hpp"

#include <array>
#include <cstdlib>

namespace {

struct StoragePathCase {
    cambridge::SessionMediaPath path;
    cambridge::FrameStorageKind storage;
    bool expected;
};

constexpr std::array<StoragePathCase, 8> kStoragePathCases{{
    {cambridge::SessionMediaPath::Unselected, cambridge::FrameStorageKind::CpuNv12, false},
    {cambridge::SessionMediaPath::Unselected, cambridge::FrameStorageKind::Native, false},
    {cambridge::SessionMediaPath::Native, cambridge::FrameStorageKind::CpuNv12, false},
    {cambridge::SessionMediaPath::Native, cambridge::FrameStorageKind::Native, true},
    {cambridge::SessionMediaPath::Software, cambridge::FrameStorageKind::CpuNv12, true},
    {cambridge::SessionMediaPath::Software, cambridge::FrameStorageKind::Native, false},
    {cambridge::SessionMediaPath::Failed, cambridge::FrameStorageKind::CpuNv12, false},
    {cambridge::SessionMediaPath::Failed, cambridge::FrameStorageKind::Native, false},
}};

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

void test_every_storage_path_row()
{
    for (const StoragePathCase &test_case : kStoragePathCases) {
        require(cambridge::frame_storage_matches_media_path(test_case.path, test_case.storage) ==
                test_case.expected);
    }
}

} // namespace

int main()
{
    test_every_storage_path_row();
    return 0;
}
