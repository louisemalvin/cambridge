#pragma once

#include <cstdint>
#include <vector>

namespace cambridge {

struct AccessUnit {
    std::vector<std::uint8_t> annex_b;
    std::uint32_t rtp_timestamp = 0;
    std::uint64_t receive_time_ns = 0;
};

} // namespace cambridge
