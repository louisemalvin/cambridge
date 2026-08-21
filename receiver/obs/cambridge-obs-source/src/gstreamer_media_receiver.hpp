#pragma once

#include "access_unit.hpp"

#include <gst/gst.h>
#include <gst/app/gstappsink.h>

#include <atomic>
#include <cstdint>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace cambridge {

struct GStreamerMediaReceiverConfig {
    std::uint16_t rtp_port = 0;
    std::uint16_t rtcp_port = 0;
    std::uint8_t payload_type = 0;
    std::uint8_t rtx_payload_type = 0;
    std::uint32_t clock_rate_hz = 0;
    std::uint32_t maximum_access_unit_bytes = 0;
};

struct GStreamerSessionConfig {
    std::uint64_t generation = 0;
    std::string sender_address;
    std::uint16_t sender_rtcp_port = 0;
};

struct GStreamerJitterbufferStats {
    std::uint64_t num_pushed = 0;
    std::uint64_t num_lost = 0;
    std::uint64_t num_late = 0;
    std::uint64_t num_duplicates = 0;
    std::uint64_t average_jitter_ns = 0;
    std::uint64_t rtx_count = 0;
    std::uint64_t rtx_success_count = 0;
    double rtx_per_packet = 0.0;
    std::uint64_t rtx_rtt_ns = 0;
};

class GStreamerMediaReceiver {
public:
    using AccessUnitCallback = std::function<void(AccessUnit)>;
    using ErrorCallback = std::function<void(const std::string &)>;

    GStreamerMediaReceiver(
        GStreamerMediaReceiverConfig config,
        AccessUnitCallback access_unit_callback,
        ErrorCallback error_callback
    );

    ~GStreamerMediaReceiver();

    bool start_session(
        const GStreamerSessionConfig &session,
        std::string &error
    );

    void stop_session();

    bool active() const;

    [[nodiscard]] std::uint64_t access_units_delivered() const { return access_units_delivered_.load(); }
    [[nodiscard]] std::uint64_t access_unit_bytes_delivered() const { return access_unit_bytes_delivered_.load(); }
    [[nodiscard]] std::uint64_t keyframe_requests() const { return keyframe_requests_.load(); }
    [[nodiscard]] GStreamerJitterbufferStats jitterbuffer_stats() const;

private:
    static GstElement *request_aux_receiver(GstElement *, guint session, gpointer user_data);
    static void remember_jitterbuffer(GstElement *, GstElement *jitterbuffer, guint session, guint, gpointer user_data);
    static void on_rtp_pad_added(GstElement *, GstPad *pad, gpointer user_data);
    static GstPadProbeReturn on_depay_event(GstPad *, GstPadProbeInfo *info, gpointer user_data);
    static GstFlowReturn on_new_sample(GstAppSink *sink, gpointer user_data);
    static gboolean on_bus_message(GstBus *, GstMessage *message, gpointer user_data);
    static gboolean on_diagnostics(gpointer user_data);

    void run_pipeline();
    bool build_pipeline(const GStreamerSessionConfig &session, std::string &error);
    void emit_diagnostics();
    void report_pipeline_error(const std::string &message);
    void signal_startup(bool success, const std::string &error);
    GstElement *make_rtx_receiver() const;
    GStreamerJitterbufferStats read_jitterbuffer_stats() const;

    GStreamerMediaReceiverConfig config_;
    AccessUnitCallback access_unit_callback_;
    ErrorCallback error_callback_;
    mutable std::mutex mutex_;
    std::condition_variable startup_condition_;
    GstElement *pipeline_ = nullptr;
    GMainContext *context_ = nullptr;
    GMainLoop *loop_ = nullptr;
    GSource *diagnostics_source_ = nullptr;
    GstElement *jitterbuffer_ = nullptr;
    std::thread thread_;
    bool startup_complete_ = false;
    bool startup_success_ = false;
    bool session_active_ = false;
    std::uint64_t generation_ = 0;
    std::string startup_error_;
    std::string sender_address_;
    std::uint16_t sender_rtcp_port_ = 0;
    std::atomic<std::uint64_t> access_units_delivered_{0};
    std::atomic<std::uint64_t> access_unit_bytes_delivered_{0};
    std::atomic<std::uint64_t> keyframe_requests_{0};
};

} // namespace cambridge
