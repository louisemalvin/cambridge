#include "../src/gstreamer_media_receiver.hpp"
#include "../src/gstreamer_runtime.hpp"
#include "../../../../sender/android/app/src/main/jni/gstreamer_sender.hpp"
#include "../src/protocol_contract.generated.hpp"

#include <gst/app/gstappsink.h>
#include <gst/gst.h>

#include <arpa/inet.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

namespace contract = cambridge::contract;

constexpr char kLoopbackAddress[] = "127.0.0.1";
constexpr std::uint16_t kEphemeralPort = 0;
constexpr std::uint64_t kTestGeneration = 1;
constexpr std::uint32_t kTestTargetBitrateBps = 2'000'000;
constexpr std::uint32_t kTestMinimumBitrateBps = 750'000;
constexpr std::uint32_t kTestFrameRate = 30;
constexpr std::uint32_t kTestFrameIntervalUs = 1'000'000 / kTestFrameRate;
constexpr std::uint32_t kTestFrameCount = 36;
constexpr std::uint32_t kTestVideoWidth = 256;
constexpr std::uint32_t kTestVideoHeight = 256;
constexpr std::uint32_t kTestDeliveryTimeoutMs = 3'000;
constexpr std::uint32_t kTestRecoveryTimeoutMs = 4'000;
constexpr std::uint32_t kTestThrottleDelayUs = 8'000;
constexpr std::uint32_t kInitialRtcpSettleMs = 1'250;
constexpr std::uint32_t kBandwidthObservationMs = 1'000;
constexpr std::uint32_t kUnrecoverableFrameCount = 8;
constexpr std::size_t kSingleLostDatagramCount = 1;
constexpr std::size_t kBurstLostDatagramCount = 10;
constexpr std::size_t kUnrecoverableDatagramCount = 64;
constexpr std::size_t kMinimumExpectedAccessUnits = 2;
constexpr std::size_t kExpectedBandwidthSamples = 2;
constexpr int kPollTimeoutMs = 25;
constexpr int kSocketReceiveBufferBytes = 64 * 1024;

struct GeneratedAccessUnit {
    std::vector<std::uint8_t> data;
    bool keyframe = false;
};

void require(bool condition, const std::string &message)
{
    if (!condition) {
        throw std::runtime_error(message);
    }
}

std::uint16_t allocate_loopback_port()
{
    const int socket_fd = socket(AF_INET, SOCK_DGRAM, 0);
    require(socket_fd >= 0, "could not create a UDP port probe socket");
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(kEphemeralPort);
    require(bind(socket_fd, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) == 0,
            "could not bind a UDP port probe socket");
    socklen_t address_length = sizeof(address);
    const int name_result = getsockname(socket_fd, reinterpret_cast<sockaddr *>(&address), &address_length);
    const std::uint16_t port = ntohs(address.sin_port);
    close(socket_fd);
    require(name_result == 0 && port != kEphemeralPort, "could not read an allocated UDP port");
    return port;
}

class UdpForwarder {
public:
    UdpForwarder() = default;
    ~UdpForwarder() { stop(); }

    UdpForwarder(const UdpForwarder &) = delete;
    UdpForwarder &operator=(const UdpForwarder &) = delete;

    void start(std::uint16_t input_port, std::uint16_t output_port)
    {
        socket_fd_ = socket(AF_INET, SOCK_DGRAM, 0);
        require(socket_fd_ >= 0, "could not create the RTP proxy socket");
        int receive_buffer = kSocketReceiveBufferBytes;
        setsockopt(socket_fd_, SOL_SOCKET, SO_RCVBUF, &receive_buffer, sizeof(receive_buffer));

        sockaddr_in input_address{};
        input_address.sin_family = AF_INET;
        input_address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        input_address.sin_port = htons(input_port);
        require(bind(socket_fd_, reinterpret_cast<const sockaddr *>(&input_address), sizeof(input_address)) == 0,
                "could not bind the RTP proxy socket");

        output_address_.sin_family = AF_INET;
        output_address_.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        output_address_.sin_port = htons(output_port);
        running_.store(true);
        thread_ = std::thread(&UdpForwarder::run, this);
    }

    void stop()
    {
        running_.store(false);
        if (thread_.joinable()) {
            thread_.join();
        }
        if (socket_fd_ >= 0) {
            close(socket_fd_);
            socket_fd_ = -1;
        }
    }

    void drop_next(std::size_t datagrams)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        drop_remaining_ = datagrams;
    }

    void clear_drops()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        drop_all_ = false;
        drop_remaining_ = 0;
    }

    void drop_all()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        drop_all_ = true;
    }

    void set_delay(std::chrono::microseconds delay)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        delay_ = delay;
    }

    [[nodiscard]] std::uint64_t forwarded() const { return forwarded_.load(); }
    [[nodiscard]] std::uint64_t dropped() const { return dropped_.load(); }

private:
    void run()
    {
        std::array<std::uint8_t, 64 * 1024> buffer{};
        while (running_.load()) {
            pollfd descriptor{socket_fd_, POLLIN, 0};
            if (poll(&descriptor, 1, kPollTimeoutMs) <= 0 || !(descriptor.revents & POLLIN)) {
                continue;
            }
            const ssize_t received = recvfrom(socket_fd_, buffer.data(), buffer.size(), 0, nullptr, nullptr);
            if (received <= 0) {
                continue;
            }
            std::chrono::microseconds delay;
            bool drop = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (drop_all_ || drop_remaining_ != 0) {
                    if (drop_remaining_ != 0) {
                        --drop_remaining_;
                    }
                    drop = true;
                }
                delay = delay_;
            }
            if (drop) {
                dropped_.fetch_add(1);
                continue;
            }
            if (delay.count() != 0) {
                std::this_thread::sleep_for(delay);
            }
            sendto(socket_fd_, buffer.data(), static_cast<std::size_t>(received), 0,
                   reinterpret_cast<const sockaddr *>(&output_address_), sizeof(output_address_));
            forwarded_.fetch_add(1);
        }
    }

    int socket_fd_ = -1;
    sockaddr_in output_address_{};
    std::thread thread_;
    std::atomic<bool> running_{false};
    mutable std::mutex mutex_;
    std::size_t drop_remaining_ = 0;
    bool drop_all_ = false;
    std::chrono::microseconds delay_{0};
    std::atomic<std::uint64_t> forwarded_{0};
    std::atomic<std::uint64_t> dropped_{0};
};

class AccessUnitCollector {
public:
    void add(cambridge::AccessUnit access_unit)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        access_units_.push_back(std::move(access_unit.annex_b));
        condition_.notify_all();
    }

    [[nodiscard]] std::size_t count() const
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return access_units_.size();
    }

    bool wait_for_count(std::size_t expected, std::uint32_t timeout_ms)
    {
        std::unique_lock<std::mutex> lock(mutex_);
        return condition_.wait_for(lock, std::chrono::milliseconds(timeout_ms), [this, expected] {
            return access_units_.size() >= expected;
        });
    }

private:
    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::vector<std::vector<std::uint8_t>> access_units_;
};

class GStreamerHarness {
public:
    GStreamerHarness()
    {
        const std::uint16_t receiver_rtp_port = allocate_loopback_port();
        const std::uint16_t receiver_rtcp_port = allocate_loopback_port();
        const std::uint16_t sender_rtcp_port = allocate_loopback_port();
        const std::uint16_t proxy_port = allocate_loopback_port();

        cambridge::GStreamerMediaReceiverConfig receiver_config;
        receiver_config.rtp_port = receiver_rtp_port;
        receiver_config.rtcp_port = receiver_rtcp_port;
        receiver_config.payload_type = static_cast<std::uint8_t>(contract::kRtpPayloadType);
        receiver_config.rtx_payload_type = static_cast<std::uint8_t>(contract::kRtxPayloadType);
        receiver_config.clock_rate_hz = contract::kRtpClockRateHz;
        receiver_config.jitter_latency_ms = contract::kJitterLatencyMs;
        receiver_config.maximum_access_unit_bytes = contract::kMaximumAccessUnitBytes;
        receiver_ = std::make_unique<cambridge::GStreamerMediaReceiver>(
            receiver_config,
            [this](cambridge::AccessUnit access_unit) { collector_.add(std::move(access_unit)); },
            [this](const std::string &message) {
                std::lock_guard<std::mutex> lock(callback_mutex_);
                receiver_error_ = message;
            });

        cambridge::GStreamerSessionConfig session;
        session.generation = kTestGeneration;
        session.sender_address = kLoopbackAddress;
        session.sender_rtcp_port = sender_rtcp_port;
        session.target_bitrate_bps = kTestTargetBitrateBps;
        std::string error;
        require(receiver_->start_session(session, error),
                "receiver session did not start: " + error);

        proxy_ = std::make_unique<UdpForwarder>();
        proxy_->start(proxy_port, receiver_rtp_port);

        GStreamerSender::Callbacks callbacks;
        callbacks.estimated_bitrate_changed = [this](std::uint32_t bitrate) {
            std::lock_guard<std::mutex> lock(callback_mutex_);
            estimated_bitrates_.push_back(bitrate);
        };
        callbacks.keyframe_requested = [this] { keyframe_requests_.fetch_add(1); };
        callbacks.transport_error = [this](const std::string &message) {
            std::lock_guard<std::mutex> lock(callback_mutex_);
            sender_error_ = message;
        };
        sender_ = std::make_unique<GStreamerSender>(std::move(callbacks));

        GStreamerSender::Config sender_config;
        sender_config.remote_host = kLoopbackAddress;
        sender_config.remote_rtp_port = proxy_port;
        sender_config.remote_rtcp_port = receiver_rtcp_port;
        sender_config.local_rtcp_port = sender_rtcp_port;
        sender_config.target_bitrate_bps = kTestTargetBitrateBps;
        sender_config.minimum_bitrate_bps = kTestMinimumBitrateBps;
        sender_config.mtu_bytes = contract::kRtpMtuBytes;
        const bool sender_started = sender_->start(sender_config, error);
        require(sender_started, "sender did not start: " + error);
    }

    ~GStreamerHarness()
    {
        if (sender_) {
            sender_->stop();
        }
        if (proxy_) {
            proxy_->stop();
        }
        if (receiver_) {
            receiver_->stop_session();
        }
    }

    GStreamerHarness(const GStreamerHarness &) = delete;
    GStreamerHarness &operator=(const GStreamerHarness &) = delete;

    void push(const GeneratedAccessUnit &access_unit, std::uint32_t index)
    {
        const bool accepted = sender_->push_access_unit(
            access_unit.data.data(), access_unit.data.size(),
            static_cast<std::int64_t>(index) * kTestFrameIntervalUs, access_unit.keyframe);
        require(accepted, "GStreamer sender rejected an access unit");
    }

    [[nodiscard]] AccessUnitCollector &collector() { return collector_; }
    [[nodiscard]] UdpForwarder &proxy() { return *proxy_; }
    [[nodiscard]] std::size_t keyframe_requests() const { return keyframe_requests_.load(); }

    [[nodiscard]] std::vector<std::uint32_t> estimated_bitrates() const
    {
        std::lock_guard<std::mutex> lock(callback_mutex_);
        return estimated_bitrates_;
    }

private:
    AccessUnitCollector collector_;
    std::unique_ptr<cambridge::GStreamerMediaReceiver> receiver_;
    std::unique_ptr<UdpForwarder> proxy_;
    std::unique_ptr<GStreamerSender> sender_;
    mutable std::mutex callback_mutex_;
    std::string receiver_error_;
    std::string sender_error_;
    std::vector<std::uint32_t> estimated_bitrates_;
    std::atomic<std::size_t> keyframe_requests_{0};
};

std::vector<GeneratedAccessUnit> generate_access_units()
{
    GstElementFactory *encoder_factory = gst_element_factory_find("x264enc");
    if (!encoder_factory) {
        return {};
    }
    gst_object_unref(encoder_factory);

    GError *parse_error = nullptr;
    const std::string pipeline_description =
        "videotestsrc num-buffers=" + std::to_string(kTestFrameCount) + " pattern=smpte ! "
        "video/x-raw,width=" + std::to_string(kTestVideoWidth) + ",height=" +
        std::to_string(kTestVideoHeight) + ",framerate=" + std::to_string(kTestFrameRate) + "/1 ! "
        "x264enc tune=zerolatency speed-preset=ultrafast key-int-max=" +
        std::to_string(kTestFrameRate) + " byte-stream=true ! "
        "h264parse config-interval=-1 ! "
        "video/x-h264,stream-format=byte-stream,alignment=au ! "
        "appsink name=cambridge-h264-sink emit-signals=false sync=false";
    GstElement *pipeline = gst_parse_launch(
        pipeline_description.c_str(),
        &parse_error);
    require(pipeline != nullptr,
            parse_error && parse_error->message ? parse_error->message : "could not build H.264 test pipeline");
    if (parse_error) {
        g_error_free(parse_error);
    }
    GstElement *sink = gst_bin_get_by_name(GST_BIN(pipeline), "cambridge-h264-sink");
    require(sink != nullptr, "could not find the H.264 test appsink");
    require(gst_element_set_state(pipeline, GST_STATE_PLAYING) != GST_STATE_CHANGE_FAILURE,
            "could not start the H.264 test pipeline");

    std::vector<GeneratedAccessUnit> access_units;
    for (std::uint32_t index = 0; index < kTestFrameCount; ++index) {
        GstSample *sample = gst_app_sink_pull_sample(GST_APP_SINK(sink));
        require(sample != nullptr, "H.264 test pipeline ended before producing all frames");
        GstBuffer *buffer = gst_sample_get_buffer(sample);
        GstMapInfo map{};
        require(buffer != nullptr && gst_buffer_map(buffer, &map, GST_MAP_READ),
                "could not map a generated H.264 access unit");
        GeneratedAccessUnit access_unit;
        access_unit.data.assign(map.data, map.data + map.size);
        access_unit.keyframe = !GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);
        gst_buffer_unmap(buffer, &map);
        gst_sample_unref(sample);
        require(!access_unit.data.empty(), "generated H.264 access unit was empty");
        access_units.push_back(std::move(access_unit));
    }
    gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_object_unref(sink);
    gst_object_unref(pipeline);
    return access_units;
}

void test_invalid_session_is_rejected()
{
    cambridge::GStreamerMediaReceiverConfig config;
    cambridge::GStreamerMediaReceiver receiver(config, {}, {});
    cambridge::GStreamerSessionConfig session;
    std::string error;
    require(!receiver.start_session(session, error), "invalid GStreamer session was accepted");
    require(!error.empty(), "invalid GStreamer session did not report an error");
}

void test_no_loss(const std::vector<GeneratedAccessUnit> &access_units)
{
    GStreamerHarness harness;
    const std::size_t expected_access_units = access_units.size();
    for (std::uint32_t index = 0; index < expected_access_units; ++index) {
        harness.push(access_units[index], index);
        std::this_thread::sleep_for(std::chrono::milliseconds(kTestFrameIntervalUs / 1'000));
    }
    require(harness.collector().wait_for_count(expected_access_units, kTestDeliveryTimeoutMs),
            "no-loss transport did not deliver continuous access units");
}

void test_one_packet_loss_is_recovered(const std::vector<GeneratedAccessUnit> &access_units)
{
    GStreamerHarness harness;
    harness.push(access_units.front(), 0);
    require(harness.collector().wait_for_count(1, kTestDeliveryTimeoutMs),
            "one-loss test did not establish the initial access unit");
    std::this_thread::sleep_for(std::chrono::milliseconds(kInitialRtcpSettleMs));
    const std::size_t initial_keyframe_requests = harness.keyframe_requests();
    harness.proxy().drop_next(kSingleLostDatagramCount);
    harness.push(access_units[1], 1);
    require(harness.collector().wait_for_count(kMinimumExpectedAccessUnits, kTestRecoveryTimeoutMs),
            "one lost RTP datagram was not recovered through RTX");
    require(harness.proxy().dropped() >= kSingleLostDatagramCount,
            "one-loss test did not inject its configured loss");
    require(harness.keyframe_requests() == initial_keyframe_requests,
            "single retransmittable packet loss unnecessarily requested an additional keyframe");
}

void test_unrecoverable_loss_requests_keyframe(const std::vector<GeneratedAccessUnit> &access_units)
{
    require(access_units.size() > kUnrecoverableFrameCount,
            "unrecoverable-loss test did not receive enough generated frames");
    GStreamerHarness harness;
    harness.push(access_units.front(), 0);
    require(harness.collector().wait_for_count(1, kTestDeliveryTimeoutMs),
            "unrecoverable-loss test did not establish the initial access unit");
    std::this_thread::sleep_for(std::chrono::milliseconds(kInitialRtcpSettleMs));
    const std::size_t initial_keyframe_requests = harness.keyframe_requests();
    harness.proxy().drop_all();
    for (std::uint32_t offset = 0; offset < kUnrecoverableFrameCount; ++offset) {
        const std::uint32_t frame_index = offset + 1;
        harness.push(access_units[frame_index], frame_index);
        std::this_thread::sleep_for(std::chrono::milliseconds(kTestFrameIntervalUs / 1'000));
    }
    harness.proxy().clear_drops();
    harness.push(access_units.front(), kUnrecoverableFrameCount + 1);
    require(harness.collector().wait_for_count(kMinimumExpectedAccessUnits, kTestRecoveryTimeoutMs),
            "receiver did not resume after a clean keyframe following unrecoverable loss");
    require(harness.keyframe_requests() > initial_keyframe_requests,
            "unrecoverable loss did not produce a GStreamer keyframe request");
    require(harness.proxy().dropped() >= kUnrecoverableDatagramCount,
            "unrecoverable-loss test did not hold the configured loss window");
}

void test_burst_loss_does_not_persist(const std::vector<GeneratedAccessUnit> &access_units)
{
    GStreamerHarness harness;
    harness.push(access_units.front(), 0);
    require(harness.collector().wait_for_count(1, kTestDeliveryTimeoutMs),
            "burst-loss test did not establish the initial access unit");
    harness.proxy().drop_next(kBurstLostDatagramCount);
    harness.push(access_units[1], 1);
    harness.push(access_units.front(), 2);
    require(harness.collector().wait_for_count(kMinimumExpectedAccessUnits, kTestRecoveryTimeoutMs),
            "burst loss left the receiver without a clean subsequent access unit");
    require(harness.proxy().dropped() >= kBurstLostDatagramCount,
            "burst-loss test did not inject ten consecutive RTP datagrams");
}

void push_motion_sequence(GStreamerHarness &harness, const std::vector<GeneratedAccessUnit> &access_units,
                          std::uint32_t start_index)
{
    for (std::uint32_t offset = 0; offset < access_units.size(); ++offset) {
        harness.push(access_units[offset], start_index + offset);
        std::this_thread::sleep_for(std::chrono::milliseconds(kTestFrameIntervalUs / 1'000));
    }
}

void test_bandwidth_drop_and_recovery(const std::vector<GeneratedAccessUnit> &access_units)
{
    GStreamerHarness harness;
    push_motion_sequence(harness, access_units, 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(kBandwidthObservationMs));
    harness.proxy().set_delay(std::chrono::microseconds(kTestThrottleDelayUs));
    push_motion_sequence(harness, access_units, kTestFrameCount);
    std::this_thread::sleep_for(std::chrono::milliseconds(kBandwidthObservationMs));
    const std::vector<std::uint32_t> throttled_estimates = harness.estimated_bitrates();
    require(!throttled_estimates.empty(), "GCC did not publish an estimate during the bandwidth drop");
    harness.proxy().set_delay(std::chrono::microseconds(0));
    push_motion_sequence(harness, access_units, kTestFrameCount * 2);
    std::this_thread::sleep_for(std::chrono::milliseconds(kBandwidthObservationMs));
    const std::vector<std::uint32_t> recovered_estimates = harness.estimated_bitrates();
    require(harness.collector().count() >= kMinimumExpectedAccessUnits,
            "bandwidth test lost all access-unit delivery");
    require(recovered_estimates.size() >= kExpectedBandwidthSamples,
            "GCC did not publish enough estimates during bandwidth recovery");
    const auto throttled_minimum = *std::min_element(throttled_estimates.begin(), throttled_estimates.end());
    const auto throttled_maximum = *std::max_element(throttled_estimates.begin(), throttled_estimates.end());
    const auto recovered_maximum = *std::max_element(recovered_estimates.begin(), recovered_estimates.end());
    require(throttled_minimum < kTestTargetBitrateBps,
            "GCC estimate did not decrease during the bandwidth drop");
    require(recovered_maximum > throttled_maximum,
            "GCC estimate did not recover after bandwidth was restored");
}

} // namespace

int main()
{
    try {
        test_invalid_session_is_rejected();
        std::string runtime_error;
        if (!cambridge::initialize_gstreamer(runtime_error)) {
            std::cerr << "GStreamer integration tests skipped: " << runtime_error << '\n';
            return 77;
        }
        const std::vector<GeneratedAccessUnit> access_units = generate_access_units();
        if (access_units.empty()) {
            std::cerr << "GStreamer integration tests skipped: x264enc is unavailable\n";
            return 77;
        }
        test_no_loss(access_units);
        test_one_packet_loss_is_recovered(access_units);
        test_unrecoverable_loss_requests_keyframe(access_units);
        test_burst_loss_does_not_persist(access_units);
        test_bandwidth_drop_and_recovery(access_units);
        return EXIT_SUCCESS;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
