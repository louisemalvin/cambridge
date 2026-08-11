#include "../interfaces/discovery_advertiser.hpp"

#include "../../discovery_metadata.hpp"
#include "../../receiver_constants.hpp"

#include <dns_sd.h>

#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

#include <chrono>
#include <cerrno>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <system_error>
#include <thread>

namespace cambridge {
namespace {

constexpr std::uint16_t kUnsetPort = 0;
constexpr int kInvalidDescriptor = -1;
constexpr int kAnyInterfaceIndex = 0;
constexpr DNSServiceFlags kNoDnsServiceFlags = static_cast<DNSServiceFlags>(0);
constexpr std::uint16_t kNoTxtBufferBytes = 0;
constexpr std::uint8_t kWakeByte = 1;
constexpr std::size_t kFirstCharacterIndex = 0;
constexpr std::size_t kWakePipeDescriptorCount = 2;
constexpr std::size_t kWakeDescriptorIndex = 0;
constexpr std::size_t kDnsDescriptorIndex = 1;
constexpr std::size_t kPollDescriptorCount = 2;
constexpr std::size_t kWakeBufferBytes = 16;
constexpr short kNoPollEvents = 0;
constexpr int kPollError = -1;
constexpr short kWakeEvents = POLLIN | POLLHUP | POLLERR;
constexpr short kDnsEvents = POLLIN | POLLHUP | POLLERR;
constexpr int kPollIntervalMs = static_cast<int>(receiver::kWorkerPollIntervalMs);

struct MacosDiscoveryAdvertiserState {
    DiscoveryConfig config;
    std::mutex mutex;
    std::condition_variable ready;
    std::thread thread;
    DNSServiceRef service_ref = nullptr;
    int wake_read = kInvalidDescriptor;
    int wake_write = kInvalidDescriptor;
    bool stopping = false;
    bool startup_complete = false;
    bool startup_succeeded = false;
    std::string startup_error;
};

void set_error_from_errno(std::string &error)
{
    error = std::strerror(errno);
}

bool set_close_on_exec(int descriptor, std::string &error)
{
    const int descriptor_flags = fcntl(descriptor, F_GETFD);
    if (descriptor_flags < 0 || fcntl(descriptor, F_SETFD, descriptor_flags | FD_CLOEXEC) < 0) {
        set_error_from_errno(error);
        return false;
    }
    return true;
}

bool create_wake_pipe(MacosDiscoveryAdvertiserState &state, std::string &error)
{
    int descriptors[kWakePipeDescriptorCount] = {kInvalidDescriptor, kInvalidDescriptor};
    if (pipe(descriptors) < 0) {
        set_error_from_errno(error);
        return false;
    }
    if (!set_close_on_exec(descriptors[kWakeDescriptorIndex], error) ||
        !set_close_on_exec(descriptors[kDnsDescriptorIndex], error)) {
        close(descriptors[kWakeDescriptorIndex]);
        close(descriptors[kDnsDescriptorIndex]);
        return false;
    }
    state.wake_read = descriptors[kWakeDescriptorIndex];
    state.wake_write = descriptors[kDnsDescriptorIndex];
    return true;
}

void close_wake_pipe(MacosDiscoveryAdvertiserState &state)
{
    const int read_descriptor = state.wake_read;
    const int write_descriptor = state.wake_write;
    state.wake_read = kInvalidDescriptor;
    state.wake_write = kInvalidDescriptor;
    if (read_descriptor != kInvalidDescriptor) {
        close(read_descriptor);
    }
    if (write_descriptor != kInvalidDescriptor) {
        close(write_descriptor);
    }
}

void wake_worker(MacosDiscoveryAdvertiserState &state)
{
    int descriptor = kInvalidDescriptor;
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        descriptor = state.wake_write;
    }
    if (descriptor != kInvalidDescriptor) {
        const ssize_t written = write(descriptor, &kWakeByte, sizeof(kWakeByte));
        static_cast<void>(written);
    }
}

bool is_stopping(MacosDiscoveryAdvertiserState &state)
{
    std::lock_guard<std::mutex> lock(state.mutex);
    return state.stopping;
}

void mark_startup_failure(MacosDiscoveryAdvertiserState &state, std::string reason)
{
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (!state.startup_complete) {
            state.startup_complete = true;
            state.startup_succeeded = false;
            state.startup_error = std::move(reason);
        }
        state.stopping = true;
    }
    state.ready.notify_all();
    wake_worker(state);
}

void mark_startup_success(MacosDiscoveryAdvertiserState &state)
{
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (state.startup_complete) {
            return;
        }
        state.startup_complete = true;
        state.startup_succeeded = true;
    }
    state.ready.notify_all();
}

std::string dns_service_error(DNSServiceErrorType error_code)
{
    return "Bonjour registration failed with DNS-SD error " +
           std::to_string(static_cast<int>(error_code));
}

bool add_txt_entries(const DiscoveryConfig &config, TXTRecordRef &record, std::string &error)
{
    TXTRecordCreate(&record, kNoTxtBufferBytes, nullptr);
    const auto maximum_txt_value_bytes = std::numeric_limits<std::uint8_t>::max();
    for (const std::string &entry : config.txt_entries) {
        const std::size_t separator = entry.find('=');
        if (separator == std::string::npos || separator == kFirstCharacterIndex) {
            error = "Bonjour metadata contains an invalid TXT entry";
            TXTRecordDeallocate(&record);
            return false;
        }
        const std::string key = entry.substr(0, separator);
        const std::string value = entry.substr(separator + 1);
        if (value.size() > maximum_txt_value_bytes) {
            error = "Bonjour metadata contains an oversized TXT value";
            TXTRecordDeallocate(&record);
            return false;
        }
        const DNSServiceErrorType set_result = TXTRecordSetValue(
            &record, key.c_str(), static_cast<std::uint8_t>(value.size()), value.data());
        if (set_result != kDNSServiceErr_NoError) {
            error = dns_service_error(set_result);
            TXTRecordDeallocate(&record);
            return false;
        }
    }
    return true;
}

void registration_callback(DNSServiceRef, DNSServiceFlags, DNSServiceErrorType error_code,
                           const char *, const char *, const char *, void *context)
{
    auto &state = *static_cast<MacosDiscoveryAdvertiserState *>(context);
    if (error_code == kDNSServiceErr_NoError) {
        mark_startup_success(state);
    } else {
        mark_startup_failure(state, dns_service_error(error_code));
    }
}

void drain_wake_pipe(int descriptor)
{
    std::uint8_t bytes[kWakeBufferBytes]{};
    const ssize_t read_count = read(descriptor, bytes, sizeof(bytes));
    static_cast<void>(read_count);
}

void run(MacosDiscoveryAdvertiserState &state)
{
    TXTRecordRef txt_record{};
    std::string error;
    if (!add_txt_entries(state.config, txt_record, error)) {
        mark_startup_failure(state, std::move(error));
        return;
    }

    const std::string service_type(discovery_service_type());
    DNSServiceRef service_ref = nullptr;
    const DNSServiceErrorType register_result = DNSServiceRegister(
        &service_ref, kNoDnsServiceFlags, kAnyInterfaceIndex, state.config.instance_name.c_str(),
        service_type.c_str(), nullptr, nullptr, htons(state.config.control_port),
        TXTRecordGetLength(&txt_record), TXTRecordGetBytesPtr(&txt_record), registration_callback,
        &state);
    TXTRecordDeallocate(&txt_record);
    if (register_result != kDNSServiceErr_NoError) {
        mark_startup_failure(state, dns_service_error(register_result));
        return;
    }

    {
        std::lock_guard<std::mutex> lock(state.mutex);
        state.service_ref = service_ref;
    }

    while (!is_stopping(state)) {
        pollfd descriptors[kPollDescriptorCount] = {
            {state.wake_read, kWakeEvents, kNoPollEvents},
            {DNSServiceRefSockFD(service_ref), kDnsEvents, kNoPollEvents},
        };
        if (descriptors[kDnsDescriptorIndex].fd == kInvalidDescriptor) {
            mark_startup_failure(state, "Bonjour returned an invalid service descriptor");
            break;
        }
        const int poll_result = poll(descriptors, kPollDescriptorCount, kPollIntervalMs);
        if (poll_result == kPollError) {
            if (errno == EINTR) {
                continue;
            }
            set_error_from_errno(error);
            mark_startup_failure(state, "Bonjour poll failed: " + error);
            break;
        }
        if (descriptors[kWakeDescriptorIndex].revents != 0) {
            drain_wake_pipe(state.wake_read);
            if (is_stopping(state)) {
                break;
            }
        }
        if ((descriptors[kDnsDescriptorIndex].revents & kDnsEvents) != 0) {
            const DNSServiceErrorType process_result = DNSServiceProcessResult(service_ref);
            if (process_result != kDNSServiceErr_NoError) {
                mark_startup_failure(state, dns_service_error(process_result));
                break;
            }
        }
    }

    DNSServiceRefDeallocate(service_ref);
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        state.service_ref = nullptr;
    }
}

class MacosDiscoveryAdvertiser final : public DiscoveryAdvertiser {
public:
    MacosDiscoveryAdvertiser() : state_(std::make_unique<MacosDiscoveryAdvertiserState>()) {}
    ~MacosDiscoveryAdvertiser() override { stop(); }

    bool start(const DiscoveryConfig &config, std::string &error) override
    {
        if (config.instance_name.empty() || config.control_port == kUnsetPort) {
            error = "Bonjour advertisement configuration is incomplete";
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(state_->mutex);
            if (state_->thread.joinable()) {
                error = "Bonjour advertisement is already running";
                return false;
            }
            state_->config = config;
            state_->stopping = false;
            state_->startup_complete = false;
            state_->startup_succeeded = false;
            state_->startup_error.clear();
        }
        if (!create_wake_pipe(*state_, error)) {
            return false;
        }
        try {
            state_->thread = std::thread(run, std::ref(*state_));
        } catch (const std::system_error &exception) {
            error = "could not start Bonjour worker: " + std::string(exception.what());
            close_wake_pipe(*state_);
            return false;
        }

        bool startup_complete = false;
        bool startup_succeeded = false;
        {
            std::unique_lock<std::mutex> lock(state_->mutex);
            startup_complete = state_->ready.wait_for(
                lock, std::chrono::milliseconds(receiver::kDiscoveryStartupTimeoutMs),
                [this] { return state_->startup_complete; });
            if (startup_complete) {
                startup_succeeded = state_->startup_succeeded;
                error = state_->startup_error;
            } else {
                error = "Bonjour advertisement did not become ready before the request timeout";
            }
        }
        if (!startup_complete || !startup_succeeded) {
            if (error.empty()) {
                error = "Bonjour advertisement failed";
            }
            stop();
            return false;
        }
        return true;
    }

    void stop() override
    {
        {
            std::lock_guard<std::mutex> lock(state_->mutex);
            state_->stopping = true;
        }
        wake_worker(*state_);
        if (state_->thread.joinable()) {
            state_->thread.join();
        }
        std::lock_guard<std::mutex> lock(state_->mutex);
        close_wake_pipe(*state_);
        state_->service_ref = nullptr;
    }

private:
    std::unique_ptr<MacosDiscoveryAdvertiserState> state_;
};

} // namespace

std::unique_ptr<DiscoveryAdvertiser> create_discovery_advertiser()
{
    return std::make_unique<MacosDiscoveryAdvertiser>();
}

} // namespace cambridge
