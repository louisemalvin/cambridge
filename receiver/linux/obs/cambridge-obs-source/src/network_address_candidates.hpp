#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace cambridge {

std::vector<std::string> discoverable_ipv4_addresses(std::size_t maximum_count);

} // namespace cambridge
