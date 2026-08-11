#pragma once

#include "protocol_contract.generated.hpp"

#include <cstddef>
#include <cstdint>

namespace cambridge::receiver {

// These values configure the receiver process and are intentionally outside
// the sender/receiver wire contract.
inline constexpr std::size_t kDefaultReceiveBufferBytes = 4 * 1024 * 1024;
inline constexpr std::uint32_t kWorkerPollIntervalMs = 100;
inline constexpr std::uint32_t kDiscoveryStartupTimeoutMs = contract::kControlRequestTimeoutMs;
inline constexpr char kDefaultDrmDevice[] = "/dev/dri/renderD128";
inline constexpr char kDefaultDecoderMode[] = "auto";
inline constexpr char kDefaultDiagnosticsPath[] = "cambridge-diagnostics.json";

} // namespace cambridge::receiver
