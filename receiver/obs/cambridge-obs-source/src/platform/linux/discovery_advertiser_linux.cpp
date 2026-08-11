#include "../interfaces/discovery_advertiser.hpp"

#include "../../discovery_metadata.hpp"
#include "../../receiver_constants.hpp"

#include <chrono>
#include <condition_variable>
#include <functional>
#include <memory>
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
namespace {

constexpr std::uint16_t kUnsetPort = 0;

struct LinuxDiscoveryAdvertiserState {
    DiscoveryConfig config;
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
#endif
};

#ifdef CAMBRIDGE_HAS_AVAHI

constexpr auto kNoAvahiPublishFlags = static_cast<AvahiPublishFlags>(0);

void mark_failure(LinuxDiscoveryAdvertiserState &state, const std::string &reason)
{
    AvahiSimplePoll *simple_poll = nullptr;
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (!state.startup_complete) {
            state.startup_error = reason;
            state.startup_complete = true;
            state.startup_succeeded = false;
        }
        simple_poll = state.simple_poll;
    }
    state.ready.notify_all();
    if (simple_poll) {
        avahi_simple_poll_quit(simple_poll);
    }
}

bool register_service(LinuxDiscoveryAdvertiserState &state, AvahiClient *client)
{
    state.entry_group = avahi_entry_group_new(
        client,
        [](AvahiEntryGroup *, AvahiEntryGroupState entry_state, void *userdata) {
            auto &entry = *static_cast<LinuxDiscoveryAdvertiserState *>(userdata);
            if (entry_state == AVAHI_ENTRY_GROUP_ESTABLISHED) {
                {
                    std::lock_guard<std::mutex> lock(entry.mutex);
                    entry.startup_complete = true;
                    entry.startup_succeeded = true;
                }
                entry.ready.notify_all();
            } else if (entry_state == AVAHI_ENTRY_GROUP_COLLISION) {
                mark_failure(entry, "mDNS service name collision");
            } else if (entry_state == AVAHI_ENTRY_GROUP_FAILURE) {
                mark_failure(entry, avahi_strerror(avahi_client_errno(entry.client)));
            }
        },
        &state);
    if (!state.entry_group) {
        mark_failure(state, "could not create the mDNS entry group");
        return false;
    }

    AvahiStringList *txt = nullptr;
    for (const std::string &entry : state.config.txt_entries) {
        AvahiStringList *updated = avahi_string_list_add(txt, entry.c_str());
        if (!updated) {
            avahi_string_list_free(txt);
            mark_failure(state, "could not allocate mDNS service metadata");
            return false;
        }
        txt = updated;
    }

    const std::string service_type(discovery_service_type());
    const int add_result = avahi_entry_group_add_service_strlst(
        state.entry_group, AVAHI_IF_UNSPEC, AVAHI_PROTO_INET, kNoAvahiPublishFlags,
        state.config.instance_name.c_str(), service_type.c_str(), nullptr, nullptr,
        state.config.control_port, txt);
    avahi_string_list_free(txt);
    if (add_result < AVAHI_OK) {
        mark_failure(state, avahi_strerror(add_result));
        return false;
    }
    const int commit_result = avahi_entry_group_commit(state.entry_group);
    if (commit_result < AVAHI_OK) {
        mark_failure(state, avahi_strerror(commit_result));
        return false;
    }
    return true;
}

void client_callback(AvahiClient *client, AvahiClientState client_state, void *userdata)
{
    auto &state = *static_cast<LinuxDiscoveryAdvertiserState *>(userdata);
    if (client_state == AVAHI_CLIENT_S_RUNNING) {
        register_service(state, client);
    } else if (client_state == AVAHI_CLIENT_FAILURE) {
        mark_failure(state, avahi_strerror(avahi_client_errno(client)));
    }
}

void run(LinuxDiscoveryAdvertiserState &state)
{
    AvahiSimplePoll *simple_poll = avahi_simple_poll_new();
    if (!simple_poll) {
        mark_failure(state, "could not create the mDNS poll loop");
        return;
    }
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        if (state.stopping) {
            avahi_simple_poll_free(simple_poll);
            return;
        }
        state.simple_poll = simple_poll;
    }

    int client_error = AVAHI_OK;
    AvahiClient *client = avahi_client_new(
        avahi_simple_poll_get(simple_poll), AVAHI_CLIENT_NO_FAIL, client_callback, &state, &client_error);
    if (!client) {
        mark_failure(state, avahi_strerror(client_error));
        avahi_simple_poll_free(simple_poll);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        state.client = client;
    }

    avahi_simple_poll_loop(simple_poll);
    if (state.entry_group) {
        avahi_entry_group_free(state.entry_group);
        state.entry_group = nullptr;
    }
    avahi_client_free(client);
    avahi_simple_poll_free(simple_poll);
    {
        std::lock_guard<std::mutex> lock(state.mutex);
        state.client = nullptr;
        state.simple_poll = nullptr;
    }
}

#endif

class LinuxDiscoveryAdvertiser final : public DiscoveryAdvertiser {
public:
    LinuxDiscoveryAdvertiser() : state_(std::make_unique<LinuxDiscoveryAdvertiserState>()) {}
    ~LinuxDiscoveryAdvertiser() override { stop(); }

    bool start(const DiscoveryConfig &config, std::string &error) override
    {
#ifndef CAMBRIDGE_HAS_AVAHI
        static_cast<void>(config);
        error = "mDNS advertisement is unavailable in this plugin build";
        return false;
#else
        if (config.instance_name.empty() || config.control_port == kUnsetPort) {
            error = "mDNS advertisement configuration is incomplete";
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(state_->mutex);
            if (state_->thread.joinable()) {
                error = "mDNS advertisement is already running";
                return false;
            }
            state_->config = config;
            state_->stopping = false;
            state_->startup_complete = false;
            state_->startup_succeeded = false;
            state_->startup_error.clear();
        }
        state_->thread = std::thread(run, std::ref(*state_));
        bool started = false;
        {
            std::unique_lock<std::mutex> lock(state_->mutex);
            started = state_->ready.wait_for(
                lock, std::chrono::milliseconds(receiver::kDiscoveryStartupTimeoutMs),
                [this] { return state_->startup_complete; });
            if (!started) {
                error = "mDNS advertisement did not become ready before the request timeout";
            } else if (!state_->startup_succeeded) {
                error = state_->startup_error.empty() ? "mDNS advertisement failed" : state_->startup_error;
            }
        }
        if (!started || !state_->startup_succeeded) {
            stop();
            return false;
        }
        return true;
#endif
    }

    void stop() override
    {
#ifdef CAMBRIDGE_HAS_AVAHI
        AvahiSimplePoll *simple_poll = nullptr;
        {
            std::lock_guard<std::mutex> lock(state_->mutex);
            state_->stopping = true;
            simple_poll = state_->simple_poll;
        }
        if (simple_poll) {
            avahi_simple_poll_quit(simple_poll);
        }
        if (state_->thread.joinable()) {
            state_->thread.join();
        }
#endif
    }

private:
    std::unique_ptr<LinuxDiscoveryAdvertiserState> state_;
};

} // namespace

std::unique_ptr<DiscoveryAdvertiser> create_discovery_advertiser()
{
    return std::make_unique<LinuxDiscoveryAdvertiser>();
}

} // namespace cambridge
