#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace cambridge {

class DiscoveryAdvertiser {
public:
    explicit DiscoveryAdvertiser(std::uint16_t control_port);
    ~DiscoveryAdvertiser();

    DiscoveryAdvertiser(const DiscoveryAdvertiser &) = delete;
    DiscoveryAdvertiser &operator=(const DiscoveryAdvertiser &) = delete;

    bool start(std::string &error);
    void stop();

    struct Impl;

private:
    std::unique_ptr<Impl> impl_;
};

} // namespace cambridge
