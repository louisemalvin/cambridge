#include "../src/discovery_metadata.hpp"
#include "../src/platform/interfaces/discovery_advertiser.hpp"
#include "../src/protocol_contract.generated.hpp"

#include <cstdlib>
#include <string>

namespace {

constexpr std::size_t kLifecycleCycleCount = 10;

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

void test_bonjour_start_stop_cycles()
{
    for (std::size_t cycle = 0; cycle < kLifecycleCycleCount; ++cycle) {
        cambridge::DiscoveryConfig config =
            cambridge::build_discovery_config(cambridge::contract::kDefaultControlPort);
        config.instance_name += " lifecycle-" + std::to_string(cycle);
        auto advertiser = cambridge::create_discovery_advertiser();
        std::string error;
        require(advertiser != nullptr);
        require(advertiser->start(config, error));
        advertiser->stop();
        advertiser.reset();
    }
}

} // namespace

int main()
{
    test_bonjour_start_stop_cycles();
    return 0;
}
