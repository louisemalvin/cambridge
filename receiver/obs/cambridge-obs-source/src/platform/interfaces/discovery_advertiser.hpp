#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace cambridge {

struct DiscoveryConfig {
    std::string instance_name;
    std::uint16_t control_port = 0;
    std::vector<std::string> txt_entries;
};

class DiscoveryAdvertiser {
public:
    virtual ~DiscoveryAdvertiser() = default;
    virtual bool start(const DiscoveryConfig &config, std::string &error) = 0;
    virtual void stop() = 0;
};

std::unique_ptr<DiscoveryAdvertiser> create_discovery_advertiser();

} // namespace cambridge
