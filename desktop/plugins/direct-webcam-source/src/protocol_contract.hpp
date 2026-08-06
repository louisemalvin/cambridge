#pragma once

#include <cstddef>
#include <cstdint>

namespace direct_webcam::contract {

inline constexpr std::uint32_t kProtocolVersion = 2;
inline constexpr std::uint32_t kMinimumGeneration = 1;
inline constexpr std::size_t kMaximumSessionIdBytes = 128;
inline constexpr std::size_t kMaximumErrorBytes = 512;
inline constexpr std::uint32_t kMinimumDimension = 16;
inline constexpr std::uint32_t kDimensionAlignment = 2;
inline constexpr std::uint32_t kMaximumLongEdge = 3840;
inline constexpr std::uint32_t kMaximumShortEdge = 2160;
inline constexpr std::uint32_t kMaximumFps = 30;
inline constexpr std::uint32_t kMinimumFps = 30;
inline constexpr std::uint32_t kMinimumBitrateBps = 100'000;
inline constexpr std::uint32_t kMaximumBitrateBps = 100'000'000;
inline constexpr std::uint32_t kMinimumPort = 1;
inline constexpr std::uint32_t kMaximumPort = 65'535;
inline constexpr std::uint32_t kRtpPayloadType = 96;
inline constexpr std::uint32_t kRtpClockRateHz = 90000;
inline constexpr std::size_t kControlHeaderBytes = 4;
inline constexpr std::size_t kMaximumControlMessageBytes = 8192;
inline constexpr std::size_t kMaximumRtpDatagramBytes = 1500;
inline constexpr std::size_t kRtpMtuBytes = 1200;
inline constexpr std::size_t kMaximumAccessUnitBytes = 8 * 1024 * 1024;
inline constexpr std::size_t kMaximumInFlightAccessUnits = 2;
inline constexpr std::size_t kMaximumReorderPackets = 64;
inline constexpr std::size_t kMailboxCapacity = 1;
inline constexpr std::size_t kDefaultReceiveBufferBytes = 4 * 1024 * 1024;
inline constexpr std::uint32_t kDefaultControlPort = 55031;
inline constexpr std::uint32_t kDefaultMediaPortOffset = 1;
inline constexpr std::uint32_t kDefaultMediaPort = kDefaultControlPort + kDefaultMediaPortOffset;
inline constexpr std::uint32_t kDefaultMaximumLongEdge = kMaximumLongEdge;
inline constexpr std::uint32_t kDefaultMaximumShortEdge = kMaximumShortEdge;
inline constexpr std::uint32_t kDefaultCodedWidth = 2560;
inline constexpr std::uint32_t kDefaultCodedHeight = 1440;
inline constexpr std::uint32_t kDefaultReorderDeadlineMs = 20;
inline constexpr std::uint32_t kDefaultMaximumDecoderQueueAgeMs = 100;
inline constexpr std::uint32_t kDefaultMaximumLiveFrameAgeMs = 250;
inline constexpr std::uint32_t kDefaultRecoveryIdrTimeoutMs = 1500;
inline constexpr std::uint32_t kControlConnectTimeoutMs = 2000;
inline constexpr std::uint32_t kControlRequestTimeoutMs = 2000;
inline constexpr std::uint32_t kWorkerPollIntervalMs = 100;
inline constexpr std::uint32_t kJoinTimeoutMs = 2000;
inline constexpr std::uint32_t kRtpHeaderBytes = 12;
inline constexpr std::uint32_t kFuIndicatorBytes = 1;
inline constexpr std::uint32_t kFuHeaderBytes = 1;
inline constexpr std::uint32_t kH264StartCodeBytes = 4;
inline constexpr std::uint32_t kH264FuANalType = 28;
inline constexpr std::uint32_t kH264IdrNalType = 5;
inline constexpr std::uint32_t kSupportedFps = 30;
inline constexpr std::size_t kTexturePoolSlots = 3;

inline constexpr char kCodecH264[] = "h264";
inline constexpr char kDefaultProfileId[] = "2k30";
inline constexpr char kDefaultDrmDevice[] = "/dev/dri/renderD128";
inline constexpr char kDefaultDecoderMode[] = "auto";
inline constexpr char kDefaultDiagnosticsPath[] = "direct-webcam-diagnostics.json";
inline constexpr char kMessageHello[] = "hello";
inline constexpr char kMessageAccepted[] = "accepted";
inline constexpr char kMessageRequestIdr[] = "request_idr";
inline constexpr char kMessageStop[] = "stop";
inline constexpr char kMessageStatus[] = "status";
inline constexpr char kMessageError[] = "error";

} // namespace direct_webcam::contract
