#include "network_address_candidates.hpp"

#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <set>

namespace cambridge {
namespace {

constexpr std::uint32_t kIpv4LoopbackMask = 0xff00'0000U;
constexpr std::uint32_t kIpv4LoopbackNetwork = 0x7f00'0000U;
constexpr std::uint32_t kIpv4LinkLocalMask = 0xffff'0000U;
constexpr std::uint32_t kIpv4LinkLocalNetwork = 0xa9fe'0000U;
constexpr std::uint32_t kIpv4MulticastMask = 0xf000'0000U;
constexpr std::uint32_t kIpv4MulticastNetwork = 0xe000'0000U;
constexpr std::uint32_t kIpv4LimitedBroadcast = 0xffff'ffffU;
constexpr int kSystemCallSuccess = 0;

struct IfAddrsDeleter {
    void operator()(ifaddrs *addresses) const
    {
        if (addresses) {
            freeifaddrs(addresses);
        }
    }
};

bool is_active_non_loopback(const ifaddrs &interface_address)
{
    const auto flags = interface_address.ifa_flags;
    return (flags & static_cast<unsigned int>(IFF_UP)) != 0U &&
           (flags & static_cast<unsigned int>(IFF_RUNNING)) != 0U &&
           (flags & static_cast<unsigned int>(IFF_LOOPBACK)) == 0U;
}

bool is_discoverable_ipv4(const in_addr &address)
{
    const std::uint32_t host_order = ntohl(address.s_addr);
    return host_order != INADDR_ANY && host_order != kIpv4LimitedBroadcast &&
           (host_order & kIpv4LoopbackMask) != kIpv4LoopbackNetwork &&
           (host_order & kIpv4LinkLocalMask) != kIpv4LinkLocalNetwork &&
           (host_order & kIpv4MulticastMask) != kIpv4MulticastNetwork;
}

std::string format_address(const ifaddrs &interface_address)
{
    std::array<char, INET_ADDRSTRLEN> text{};
    const int family = interface_address.ifa_addr->sa_family;
    if (family != AF_INET) {
        return {};
    }
    const auto *ipv4 = reinterpret_cast<const sockaddr_in *>(interface_address.ifa_addr);
    if (!is_discoverable_ipv4(ipv4->sin_addr)) {
        return {};
    }
    const void *address = &ipv4->sin_addr;
    if (!inet_ntop(family, address, text.data(), static_cast<socklen_t>(text.size()))) {
        return {};
    }
    return text.data();
}

} // namespace

std::vector<std::string> discoverable_ipv4_addresses(std::size_t maximum_count)
{
    if (maximum_count == 0U) {
        return {};
    }
    ifaddrs *raw_addresses = nullptr;
    if (getifaddrs(&raw_addresses) != kSystemCallSuccess || !raw_addresses) {
        return {};
    }
    const std::unique_ptr<ifaddrs, IfAddrsDeleter> addresses(raw_addresses);
    std::set<std::string> candidates;
    for (const ifaddrs *current = addresses.get(); current; current = current->ifa_next) {
        if (!current->ifa_addr || !is_active_non_loopback(*current)) {
            continue;
        }
        const std::string candidate = format_address(*current);
        if (!candidate.empty()) {
            candidates.insert(candidate);
        }
    }

    std::vector<std::string> result;
    result.reserve(std::min(maximum_count, candidates.size()));
    for (const std::string &candidate : candidates) {
        if (result.size() >= maximum_count) {
            break;
        }
        result.push_back(candidate);
    }
    return result;
}

} // namespace cambridge
