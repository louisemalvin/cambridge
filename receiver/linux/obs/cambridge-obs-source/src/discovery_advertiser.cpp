#include "discovery_advertiser.hpp"

#include "network_address_candidates.hpp"
#include "protocol_contract.hpp"

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <utility>

#ifdef CAMBRIDGE_HAS_AVAHI
#include <avahi-client/client.h>
#include <avahi-client/publish.h>
#include <avahi-common/error.h>
#include <avahi-common/simple-watch.h>
#include <avahi-common/strlst.h>
#endif

namespace cambridge {

struct DiscoveryAdvertiser::Impl {
    explicit Impl(std::uint16_t port) : control_port(port) {}

    std::uint16_t control_port;
#ifdef CAMBRIDGE_HAS_AVAHI
    std::mutex mutex;
    std::condition_variable ready;
    std::thread thread;
    AvahiSimplePoll *simple_poll = nullptr;
    AvahiClient *client = nullptr;
    AvahiEntryGroup *entry_group = nullptr;
    bool stopping = false;
    bool startup_complete = false;
    bool startup_succeeded = false;
    std::string startup_error;
    std::string service_name = contract::kDefaultReceiverDisplayName;
#endif
};

#ifdef CAMBRIDGE_HAS_AVAHI
namespace {

constexpr auto kNoAvahiPublishFlags = static_cast<AvahiPublishFlags>(0);

bool add_txt_pair(AvahiStringList *&txt, const std::string &key, const std::string &value)
{
    AvahiStringList *updated = avahi_string_list_add_pair(txt, key.c_str(), value.c_str());
    if (!updated) {
        return false;
    }
    txt = updated;
    return true;
}

void mark_failure(DiscoveryAdvertiser::Impl &impl, const std::string &reason)
{
    AvahiSimplePoll *simple_poll = nullptr;
    {
        std::lock_guard<std::mutex> lock(impl.mutex);
        if (!impl.startup_complete) {
            impl.startup_error = reason;
            impl.startup_complete = true;
            impl.startup_succeeded = false;
        }
        simple_poll = impl.simple_poll;
    }
    impl.ready.notify_all();
    if (simple_poll) {
        avahi_simple_poll_quit(simple_poll);
    }
}

bool register_service(DiscoveryAdvertiser::Impl &impl, AvahiClient *client)
{
    impl.entry_group = avahi_entry_group_new(
        client,
        [](AvahiEntryGroup *, AvahiEntryGroupState state, void *userdata) {
            auto &entry = *static_cast<DiscoveryAdvertiser::Impl *>(userdata);
            if (state == AVAHI_ENTRY_GROUP_ESTABLISHED) {
                {
                    std::lock_guard<std::mutex> lock(entry.mutex);
                    entry.startup_complete = true;
                    entry.startup_succeeded = true;
                }
                entry.ready.notify_all();
            } else if (state == AVAHI_ENTRY_GROUP_COLLISION) {
                mark_failure(entry, "mDNS service name collision");
            } else if (state == AVAHI_ENTRY_GROUP_FAILURE) {
                mark_failure(entry, avahi_strerror(avahi_client_errno(entry.client)));
            }
        },
        &impl);
    if (!impl.entry_group) {
        mark_failure(impl, "could not create the mDNS entry group");
        return false;
    }

    const std::string protocol_version = std::to_string(contract::kProtocolVersion);
    const std::string discovery_version = std::to_string(contract::kDiscoveryVersion);
    AvahiStringList *txt = nullptr;
    const bool base_metadata_added =
        add_txt_pair(txt, contract::kDiscoveryReceiverIdKey, contract::kDefaultReceiverId) &&
        add_txt_pair(txt, contract::kDiscoveryReceiverNameKey, contract::kDefaultReceiverDisplayName) &&
        add_txt_pair(txt, contract::kDiscoveryProtocolVersionKey, protocol_version) &&
        add_txt_pair(txt, contract::kDiscoveryCodecKey, contract::kCodecH264) &&
        add_txt_pair(txt, contract::kDiscoveryVersionKey, discovery_version);
    if (!base_metadata_added) {
        avahi_string_list_free(txt);
        mark_failure(impl, "could not allocate mDNS service metadata");
        return false;
    }
    const auto addresses = discoverable_ipv4_addresses(contract::kMaximumDiscoveryAddressCount);
    for (std::size_t index{}; index < addresses.size(); ++index) {
        const std::string key = std::string(contract::kDiscoveryAddressKeyPrefix) + std::to_string(index);
        if (!add_txt_pair(txt, key, addresses[index])) {
            avahi_string_list_free(txt);
            mark_failure(impl, "could not allocate mDNS address metadata");
            return false;
        }
    }

    const int add_result = avahi_entry_group_add_service_strlst(
        impl.entry_group,
        AVAHI_IF_UNSPEC,
        AVAHI_PROTO_INET,
        kNoAvahiPublishFlags,
        impl.service_name.c_str(),
        contract::kDiscoveryServiceType,
        nullptr,
        nullptr,
        impl.control_port,
        txt);
    avahi_string_list_free(txt);
    if (add_result < AVAHI_OK) {
        mark_failure(impl, avahi_strerror(add_result));
        return false;
    }
    const int commit_result = avahi_entry_group_commit(impl.entry_group);
    if (commit_result < AVAHI_OK) {
        mark_failure(impl, avahi_strerror(commit_result));
        return false;
    }
    return true;
}

void client_callback(AvahiClient *client, AvahiClientState state, void *userdata)
{
    auto &impl = *static_cast<DiscoveryAdvertiser::Impl *>(userdata);
    if (state == AVAHI_CLIENT_S_RUNNING) {
        register_service(impl, client);
    } else if (state == AVAHI_CLIENT_FAILURE) {
        mark_failure(impl, avahi_strerror(avahi_client_errno(client)));
    }
}

void run(DiscoveryAdvertiser::Impl &impl)
{
    AvahiSimplePoll *simple_poll = avahi_simple_poll_new();
    if (!simple_poll) {
        mark_failure(impl, "could not create the mDNS poll loop");
        return;
    }
    {
        std::lock_guard<std::mutex> lock(impl.mutex);
        if (impl.stopping) {
            avahi_simple_poll_free(simple_poll);
            return;
        }
        impl.simple_poll = simple_poll;
    }

    int client_error = AVAHI_OK;
    AvahiClient *client = avahi_client_new(
        avahi_simple_poll_get(simple_poll), AVAHI_CLIENT_NO_FAIL, client_callback, &impl, &client_error);
    if (!client) {
        mark_failure(impl, avahi_strerror(client_error));
        avahi_simple_poll_free(simple_poll);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(impl.mutex);
        impl.client = client;
    }

    avahi_simple_poll_loop(simple_poll);
    if (impl.entry_group) {
        avahi_entry_group_free(impl.entry_group);
        impl.entry_group = nullptr;
    }
    avahi_client_free(client);
    avahi_simple_poll_free(simple_poll);
    {
        std::lock_guard<std::mutex> lock(impl.mutex);
        impl.client = nullptr;
        impl.simple_poll = nullptr;
    }
}

} // namespace
#endif

DiscoveryAdvertiser::DiscoveryAdvertiser(std::uint16_t control_port)
    : impl_(std::make_unique<Impl>(control_port))
{
}

DiscoveryAdvertiser::~DiscoveryAdvertiser()
{
    stop();
}

bool DiscoveryAdvertiser::start(std::string &error)
{
#ifndef CAMBRIDGE_HAS_AVAHI
    error = "mDNS advertisement is unavailable in this plugin build";
    return false;
#else
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (impl_->thread.joinable()) {
            error = "mDNS advertisement is already running";
            return false;
        }
        impl_->stopping = false;
        impl_->startup_complete = false;
        impl_->startup_succeeded = false;
        impl_->startup_error.clear();
    }
    impl_->thread = std::thread(run, std::ref(*impl_));
    bool started = false;
    {
        std::unique_lock<std::mutex> lock(impl_->mutex);
        started = impl_->ready.wait_for(
            lock,
            std::chrono::milliseconds(contract::kControlRequestTimeoutMs),
            [this] { return impl_->startup_complete; });
        if (!started) {
            error = "mDNS advertisement did not become ready before the request timeout";
        } else if (!impl_->startup_succeeded) {
            error = impl_->startup_error.empty() ? "mDNS advertisement failed" : impl_->startup_error;
        }
    }
    if (!started || !impl_->startup_succeeded) {
        stop();
        return false;
    }
    return true;
#endif
}

void DiscoveryAdvertiser::stop()
{
#ifdef CAMBRIDGE_HAS_AVAHI
    AvahiSimplePoll *simple_poll = nullptr;
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->stopping = true;
        simple_poll = impl_->simple_poll;
    }
    if (simple_poll) {
        avahi_simple_poll_quit(simple_poll);
    }
    if (impl_->thread.joinable()) {
        impl_->thread.join();
    }
#endif
}

} // namespace cambridge
