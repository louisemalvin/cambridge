#include "../src/network_address_candidates.hpp"

#include <arpa/inet.h>

#include <cstdlib>
#include <set>
#include <string>

namespace {

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

bool is_ipv4_literal(const std::string &candidate)
{
    in_addr ipv4{};
    return inet_pton(AF_INET, candidate.c_str(), &ipv4) == 1;
}

} // namespace

int main()
{
    require(cambridge::discoverable_ipv4_addresses(0U).empty());

    constexpr std::size_t maximum_candidates = 16U;
    const auto candidates = cambridge::discoverable_ipv4_addresses(maximum_candidates);
    require(candidates.size() <= maximum_candidates);
    require(std::set<std::string>(candidates.begin(), candidates.end()).size() == candidates.size());
    for (const std::string &candidate : candidates) {
        require(is_ipv4_literal(candidate));
        require(candidate != "127.0.0.1");
    }
    return 0;
}
