#include "../src/discovery_metadata.hpp"
#include "../src/protocol_contract.generated.hpp"

#include <cstdlib>
#include <set>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr std::size_t kBaseTxtEntryCount = 5;

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

std::string_view key_of(std::string_view entry)
{
    const std::size_t separator = entry.find('=');
    require(separator != std::string_view::npos);
    return entry.substr(0, separator);
}

void test_metadata_is_stable_and_generated()
{
    const auto first = cambridge::build_discovery_config(cambridge::contract::kDefaultControlPort);
    const auto second = cambridge::build_discovery_config(cambridge::contract::kDefaultControlPort);
    require(first.instance_name == cambridge::contract::kDefaultReceiverDisplayName);
    require(first.control_port == cambridge::contract::kDefaultControlPort);
    require(first.txt_entries == second.txt_entries);
    require(cambridge::discovery_service_type() == cambridge::contract::kDiscoveryServiceType);
    require(first.txt_entries.size() >= kBaseTxtEntryCount);
    require(first.txt_entries.size() <=
            kBaseTxtEntryCount + cambridge::contract::kMaximumDiscoveryAddressCount);

    const std::vector<std::string> expected_prefix = {
        std::string(cambridge::contract::kDiscoveryReceiverIdKey) + "=" +
            cambridge::contract::kDefaultReceiverId,
        std::string(cambridge::contract::kDiscoveryReceiverNameKey) + "=" +
            cambridge::contract::kDefaultReceiverDisplayName,
        std::string(cambridge::contract::kDiscoveryProtocolVersionKey) + "=" +
            std::to_string(cambridge::contract::kProtocolVersion),
        std::string(cambridge::contract::kDiscoveryCodecKey) + "=" + cambridge::contract::kCodecH264,
        std::string(cambridge::contract::kDiscoveryVersionKey) + "=" +
            std::to_string(cambridge::contract::kDiscoveryVersion),
    };
    for (std::size_t index = 0; index < expected_prefix.size(); ++index) {
        require(first.txt_entries[index] == expected_prefix[index]);
    }

    std::set<std::string_view> keys;
    std::size_t address_count = 0;
    for (const std::string &entry : first.txt_entries) {
        const std::string_view key = key_of(entry);
        require(keys.insert(key).second);
        if (key.rfind(cambridge::contract::kDiscoveryAddressKeyPrefix, 0) == 0) {
            ++address_count;
        }
    }
    require(address_count <= cambridge::contract::kMaximumDiscoveryAddressCount);
}

} // namespace

int main()
{
    test_metadata_is_stable_and_generated();
    return 0;
}
