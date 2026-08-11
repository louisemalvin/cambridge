#include "discovery_metadata.hpp"

#include "network_address_candidates.hpp"
#include "protocol_contract.generated.hpp"

#include <string>

namespace cambridge {
namespace {

void add_txt_entry(DiscoveryConfig &config, std::string_view key, std::string_view value)
{
    config.txt_entries.emplace_back(std::string(key) + "=" + std::string(value));
}

} // namespace

DiscoveryConfig build_discovery_config(std::uint16_t control_port)
{
    DiscoveryConfig config;
    config.instance_name = contract::kDefaultReceiverDisplayName;
    config.control_port = control_port;
    config.txt_entries.reserve(contract::kDiscoveryTxtKeys.size());
    add_txt_entry(config, contract::kDiscoveryReceiverIdKey, contract::kDefaultReceiverId);
    add_txt_entry(config, contract::kDiscoveryReceiverNameKey, contract::kDefaultReceiverDisplayName);
    add_txt_entry(config, contract::kDiscoveryProtocolVersionKey,
                  std::to_string(contract::kProtocolVersion));
    add_txt_entry(config, contract::kDiscoveryCodecKey, contract::kCodecH264);
    add_txt_entry(config, contract::kDiscoveryVersionKey,
                  std::to_string(contract::kDiscoveryVersion));

    const auto addresses = discoverable_ipv4_addresses(contract::kMaximumDiscoveryAddressCount);
    for (std::size_t index{}; index < addresses.size(); ++index) {
        add_txt_entry(config, std::string(contract::kDiscoveryAddressKeyPrefix) + std::to_string(index),
                      addresses[index]);
    }
    return config;
}

std::string_view discovery_service_type()
{
    return contract::kDiscoveryServiceType;
}

} // namespace cambridge
