#pragma once

#include "platform/interfaces/discovery_advertiser.hpp"

#include <cstdint>
#include <string_view>

namespace cambridge {

DiscoveryConfig build_discovery_config(std::uint16_t control_port);
std::string_view discovery_service_type();

} // namespace cambridge
