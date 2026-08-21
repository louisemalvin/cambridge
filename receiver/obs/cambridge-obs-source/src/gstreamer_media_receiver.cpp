#include "gstreamer_media_receiver.hpp"

#include "protocol_contract.generated.hpp"

#include <gst/app/gstappsink.h>
#include <gst/rtp/gstrtpdefs.h>
#include <gst/video/video-event.h>

#include <algorithm>
#include <array>
#include <sstream>
#include <utility>

#include <time.h>

namespace cambridge {
namespace {

constexpr guint kVideoSession = static_cast<guint>(contract::kRtpSessionIndex);
constexpr gboolean kNonSynchronizedSink = FALSE;
constexpr gboolean kSynchronizedSink = FALSE;
constexpr gboolean kDropOnLatency = TRUE;
constexpr gboolean kRetransmissionEnabled = TRUE;
constexpr gboolean kLostEventsEnabled = TRUE;
constexpr gboolean kEmitAppSinkSignals = TRUE;
constexpr gboolean kDropOldAppSinkBuffers = TRUE;
constexpr gint kRtxDeadlineMs = static_cast<gint>(contract::kRtxHistoryMs);
constexpr gint kRtxRetryPeriodMs = static_cast<gint>(contract::kRtxHistoryMs);
constexpr GstClockTime kRtcpMinimumInterval = 0; // Let AVPF use the calculated feedback interval.
constexpr char kRtpAddress[] = "0.0.0.0";
constexpr char kRtpMedia[] = "video";
constexpr char kRtpEncodingName[] = "H264";
constexpr char kRtpExtensionFieldPrefix[] = "extmap-";
constexpr char kTwccExtensionUri[] =
    "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01";
constexpr char kRtpSourcePadPrefix[] = "recv_rtp_src_0_";
constexpr char kRtpCapsName[] = "application/x-rtp";
constexpr char kRtcpCapsName[] = "application/x-rtcp";
constexpr char kH264CapsName[] = "video/x-h264";
constexpr char kByteStreamFormat[] = "byte-stream";
constexpr char kAccessUnitAlignment[] = "au";
constexpr char kRtxPayloadMapName[] = "application/x-rtp-pt-map";
constexpr char kRtcpFeedbackNackPliField[] = "rtcp-fb-nack-pli";
constexpr char kRtcpFeedbackCcmFirField[] = "rtcp-fb-ccm-fir";
constexpr char kEmptyRtcpFeedbackValue[] = "";
constexpr char kAuxSinkPadName[] = "sink_0";
constexpr char kAuxSourcePadName[] = "src_0";
constexpr std::uint64_t kNanosecondsPerSecond = 1'000'000'000ULL;

std::uint64_t monotonic_time_ns()
{
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<std::uint64_t>(time.tv_sec) * kNanosecondsPerSecond +
           static_cast<std::uint64_t>(time.tv_nsec);
}

std::string element_name(GstMessage *message)
{
    GstObject *source = GST_MESSAGE_SRC(message);
    const gchar *name = source ? GST_OBJECT_NAME(source) : nullptr;
    return name ? name : "unknown";
}

std::string error_message(GstMessage *message, bool warning)
{
    GError *error = nullptr;
    gchar *debug = nullptr;
    if (warning) {
        gst_message_parse_warning(message, &error, &debug);
    } else {
        gst_message_parse_error(message, &error, &debug);
    }
    std::ostringstream output;
    output << (warning ? "GStreamer warning" : "GStreamer error")
           << " element=" << element_name(message);
    if (error) {
        output << " domain=" << g_quark_to_string(error->domain)
               << " code=" << error->code << " message=" << error->message;
    }
    if (debug) {
        output << " debug=" << debug;
    }
    if (error) {
        g_error_free(error);
    }
    g_free(debug);
    return output.str();
}

GstCaps *build_rtp_caps(const GStreamerMediaReceiverConfig &config)
{
    GstCaps *caps = gst_caps_new_simple(
        kRtpCapsName,
        "media", G_TYPE_STRING, kRtpMedia,
        "encoding-name", G_TYPE_STRING, kRtpEncodingName,
        "clock-rate", G_TYPE_INT, static_cast<gint>(config.clock_rate_hz),
        nullptr);
    GValue payload_values = G_VALUE_INIT;
    GValue payload_value = G_VALUE_INIT;
    g_value_init(&payload_values, GST_TYPE_LIST);
    g_value_init(&payload_value, G_TYPE_INT);
    g_value_set_int(&payload_value, static_cast<gint>(config.payload_type));
    gst_value_list_append_value(&payload_values, &payload_value);
    g_value_set_int(&payload_value, static_cast<gint>(config.rtx_payload_type));
    gst_value_list_append_value(&payload_values, &payload_value);
    gst_caps_set_value(caps, "payload", &payload_values);
    g_value_unset(&payload_value);
    g_value_unset(&payload_values);
    const std::string extension_field =
        std::string(kRtpExtensionFieldPrefix) + std::to_string(contract::kTwccExtensionId);
    gst_caps_set_simple(caps, extension_field.c_str(), G_TYPE_STRING, kTwccExtensionUri, nullptr);
    gst_caps_set_simple(
        caps,
        kRtcpFeedbackNackPliField, G_TYPE_STRING, kEmptyRtcpFeedbackValue,
        kRtcpFeedbackCcmFirField, G_TYPE_STRING, kEmptyRtcpFeedbackValue,
        nullptr);
    return caps;
}

GstCaps *build_h264_caps()
{
    return gst_caps_new_simple(
        kH264CapsName,
        "stream-format", G_TYPE_STRING, kByteStreamFormat,
        "alignment", G_TYPE_STRING, kAccessUnitAlignment,
        nullptr);
}

bool link_pad_to_request_pad(GstElement *source, const char *source_pad_name,
                             GstElement *target, const char *target_pad_name,
                             std::string &error)
{
    GstPad *source_pad = gst_element_get_static_pad(source, source_pad_name);
    GstPad *target_pad = gst_element_request_pad_simple(target, target_pad_name);
    if (!source_pad || !target_pad) {
        error = "could not request GStreamer transport pads";
        if (source_pad) {
            gst_object_unref(source_pad);
        }
        if (target_pad) {
            gst_object_unref(target_pad);
        }
        return false;
    }
    const GstPadLinkReturn link_result = gst_pad_link(source_pad, target_pad);
    gst_object_unref(source_pad);
    gst_object_unref(target_pad);
    if (link_result != GST_PAD_LINK_OK) {
        error = "could not link GStreamer transport pads";
        return false;
    }
    return true;
}

std::string session_pad_name(const char *prefix)
{
    return std::string(prefix) + std::to_string(kVideoSession);
}

gint rtcp_bandwidth_bps(std::uint32_t target_bitrate_bps)
{
    const gdouble bandwidth = static_cast<gdouble>(target_bitrate_bps) *
                              contract::kRtcpFeedbackBandwidthFraction;
    return static_cast<gint>(std::min(bandwidth, static_cast<gdouble>(G_MAXINT)));
}

} // namespace

GStreamerMediaReceiver::GStreamerMediaReceiver(
    GStreamerMediaReceiverConfig config,
    AccessUnitCallback access_unit_callback,
    ErrorCallback error_callback
)
    : config_(config),
      access_unit_callback_(std::move(access_unit_callback)),
      error_callback_(std::move(error_callback))
{
}

GStreamerMediaReceiver::~GStreamerMediaReceiver()
{
    stop_session();
}

bool GStreamerMediaReceiver::start_session(
    const GStreamerSessionConfig &session,
    std::string &error
)
{
    stop_session();
    error.clear();
    if (config_.rtp_port < contract::kMinimumPort || config_.rtcp_port < contract::kMinimumPort ||
        config_.rtp_port > contract::kMaximumPort || config_.rtcp_port > contract::kMaximumPort ||
        config_.rtp_port == config_.rtcp_port || config_.payload_type == config_.rtx_payload_type ||
        config_.clock_rate_hz == 0 ||
        config_.jitter_latency_ms == 0 || config_.maximum_access_unit_bytes == 0 ||
        session.generation == 0 || session.sender_address.empty() ||
        session.sender_rtcp_port < contract::kMinimumPort ||
        session.sender_rtcp_port > contract::kMaximumPort ||
        session.target_bitrate_bps < contract::kMinimumBitrateBps ||
        session.target_bitrate_bps > contract::kMaximumBitrateBps) {
        error = "invalid GStreamer media session configuration";
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        context_ = g_main_context_new();
        loop_ = context_ ? g_main_loop_new(context_, FALSE) : nullptr;
        if (!context_ || !loop_) {
            if (loop_) {
                g_main_loop_unref(loop_);
                loop_ = nullptr;
            }
            if (context_) {
                g_main_context_unref(context_);
                context_ = nullptr;
            }
            error = "GStreamer receiver could not allocate its main-loop context";
            return false;
        }
        startup_complete_ = false;
        startup_success_ = false;
        startup_error_.clear();
        stopping_ = false;
        session_active_ = true;
        generation_ = session.generation;
        sender_address_ = session.sender_address;
        sender_rtcp_port_ = session.sender_rtcp_port;
        target_bitrate_bps_ = session.target_bitrate_bps;
    }
    thread_ = std::thread(&GStreamerMediaReceiver::run_pipeline, this);
    {
        std::unique_lock<std::mutex> lock(mutex_);
        startup_condition_.wait(lock, [this] { return startup_complete_; });
        if (!startup_success_) {
            error = startup_error_.empty() ? "GStreamer receiver pipeline failed to start" : startup_error_;
        }
    }
    if (!error.empty()) {
        stop_session();
        return false;
    }
    return true;
}

void GStreamerMediaReceiver::stop_session()
{
    GMainLoop *loop = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        session_active_ = false;
        stopping_ = true;
        loop = loop_;
    }
    if (loop) {
        g_main_loop_quit(loop);
    }
    if (thread_.joinable()) {
        thread_.join();
    }
    GMainLoop *old_loop = nullptr;
    GMainContext *old_context = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        old_loop = loop_;
        old_context = context_;
        loop_ = nullptr;
        context_ = nullptr;
        pipeline_ = nullptr;
        startup_complete_ = false;
        startup_success_ = false;
        startup_error_.clear();
        generation_ = 0;
        sender_address_.clear();
        sender_rtcp_port_ = 0;
        target_bitrate_bps_ = 0;
    }
    if (old_loop) {
        g_main_loop_unref(old_loop);
    }
    if (old_context) {
        g_main_context_unref(old_context);
    }
}

bool GStreamerMediaReceiver::active() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return session_active_ && startup_success_ && pipeline_ != nullptr;
}

void GStreamerMediaReceiver::run_pipeline()
{
    GMainContext *context = nullptr;
    GMainLoop *loop = nullptr;
    GStreamerSessionConfig session;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        context = context_;
        loop = loop_;
        session.generation = generation_;
        session.sender_address = sender_address_;
        session.sender_rtcp_port = sender_rtcp_port_;
        session.target_bitrate_bps = target_bitrate_bps_;
    }
    if (!context || !loop) {
        signal_startup(false, "GStreamer receiver main-loop context is unavailable");
        return;
    }
    g_main_context_push_thread_default(context);
    std::string error;
    if (!build_pipeline(session, error)) {
        signal_startup(false, error);
        g_main_context_pop_thread_default(context);
        return;
    }

    GstElement *pipeline = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipeline = pipeline_;
    }
    GstBus *bus = gst_element_get_bus(pipeline);
    gst_bus_add_watch(bus, &GStreamerMediaReceiver::on_bus_message, this);
    gst_object_unref(bus);
    const GstStateChangeReturn state_result = gst_element_set_state(pipeline, GST_STATE_PLAYING);
    if (state_result == GST_STATE_CHANGE_FAILURE) {
        error = "GStreamer receiver pipeline could not enter PLAYING";
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(pipeline);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            pipeline_ = nullptr;
        }
        signal_startup(false, error);
        g_main_context_pop_thread_default(context);
        return;
    }
    signal_startup(true, {});
    g_main_loop_run(loop);
    gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_object_unref(pipeline);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipeline_ = nullptr;
    }
    g_main_context_pop_thread_default(context);
}

bool GStreamerMediaReceiver::build_pipeline(const GStreamerSessionConfig &session, std::string &error)
{
    GstElement *pipeline = gst_pipeline_new("cambridge-gstreamer-receiver");
    GstElement *rtpbin = gst_element_factory_make("rtpbin", "rtpbin");
    GstElement *rtp_source = gst_element_factory_make("udpsrc", "rtp-source");
    GstElement *rtcp_source = gst_element_factory_make("udpsrc", "rtcp-source");
    GstElement *rtcp_sink = gst_element_factory_make("udpsink", "rtcp-sink");
    GstElement *depay = gst_element_factory_make("rtph264depay", "h264-depay");
    GstElement *parser = gst_element_factory_make("h264parse", "h264-parse");
    GstElement *appsink = gst_element_factory_make("appsink", "h264-appsink");
    if (!pipeline || !rtpbin || !rtp_source || !rtcp_source || !rtcp_sink || !depay || !parser || !appsink) {
        error = "required GStreamer receiver element could not be created";
        const std::array<GstElement *, 7> standalone_elements = {
            rtpbin, rtp_source, rtcp_source, rtcp_sink, depay, parser, appsink,
        };
        for (GstElement *element : standalone_elements) {
            if (element) {
                gst_object_unref(element);
            }
        }
        if (pipeline) {
            gst_object_unref(pipeline);
        }
        return false;
    }
    gst_bin_add_many(
        GST_BIN(pipeline), rtpbin, rtp_source, rtcp_source, rtcp_sink, depay, parser, appsink, nullptr);

    GstCaps *rtp_caps = build_rtp_caps(config_);
    GstCaps *rtcp_caps = gst_caps_new_empty_simple(kRtcpCapsName);
    GstCaps *h264_caps = build_h264_caps();
    g_object_set(
        rtp_source,
        "address", kRtpAddress,
        "port", static_cast<gint>(config_.rtp_port),
        "caps", rtp_caps,
        nullptr);
    g_object_set(
        rtcp_source,
        "address", kRtpAddress,
        "port", static_cast<gint>(config_.rtcp_port),
        "caps", rtcp_caps,
        nullptr);
    g_object_set(
        rtcp_sink,
        "host", session.sender_address.c_str(),
        "port", static_cast<gint>(session.sender_rtcp_port),
        "sync", kNonSynchronizedSink,
        "async", kNonSynchronizedSink,
        nullptr);
    g_object_set(
        rtpbin,
        "rtp-profile", GST_RTP_PROFILE_AVPF,
        "do-retransmission", kRetransmissionEnabled,
        "do-lost", kLostEventsEnabled,
        nullptr);
    g_object_set(
        depay,
        "request-keyframe", TRUE,
        "wait-for-keyframe", TRUE,
        nullptr);
    g_object_set(
        appsink,
        "caps", h264_caps,
        "emit-signals", kEmitAppSinkSignals,
        "sync", kSynchronizedSink,
        "max-buffers", static_cast<guint>(contract::kMaximumInFlightAccessUnits),
        "drop", kDropOldAppSinkBuffers,
        nullptr);
    gst_caps_unref(rtp_caps);
    gst_caps_unref(rtcp_caps);
    gst_caps_unref(h264_caps);

    g_signal_connect(rtpbin, "request-aux-receiver", G_CALLBACK(&GStreamerMediaReceiver::request_aux_receiver), this);
    g_signal_connect(rtpbin, "new-jitterbuffer", G_CALLBACK(&GStreamerMediaReceiver::configure_jitterbuffer), this);
    g_signal_connect(rtpbin, "pad-added", G_CALLBACK(&GStreamerMediaReceiver::on_rtp_pad_added), this);
    g_signal_connect(appsink, "new-sample", G_CALLBACK(&GStreamerMediaReceiver::on_new_sample), this);

    if (!gst_element_link(depay, parser) || !gst_element_link(parser, appsink) ||
        !link_pad_to_request_pad(
            rtp_source, "src", rtpbin, session_pad_name("recv_rtp_sink_").c_str(), error) ||
        !link_pad_to_request_pad(
            rtcp_source, "src", rtpbin, session_pad_name("recv_rtcp_sink_").c_str(), error)) {
        if (error.empty()) {
            error = "could not link the GStreamer H.264 receiver path";
        }
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(pipeline);
        return false;
    }
    GstElement *internal_session = nullptr;
    g_signal_emit_by_name(rtpbin, "get-internal-session", kVideoSession, &internal_session);
    if (!internal_session) {
        error = "could not access the GStreamer RTP session";
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(pipeline);
        return false;
    }
    g_object_set(internal_session,
                 "bandwidth", static_cast<gdouble>(session.target_bitrate_bps),
                 "rtcp-min-interval", kRtcpMinimumInterval,
                 "rtcp-fraction", contract::kRtcpFeedbackBandwidthFraction,
                 "rtcp-rr-bandwidth", rtcp_bandwidth_bps(session.target_bitrate_bps),
                 "rtcp-rs-bandwidth", rtcp_bandwidth_bps(session.target_bitrate_bps),
                 nullptr);
    gst_object_unref(internal_session);

    const std::string send_rtcp_src_name = session_pad_name("send_rtcp_src_");
    GstPad *send_rtcp_pad = gst_element_request_pad_simple(rtpbin, send_rtcp_src_name.c_str());
    GstPad *rtcp_sink_pad = gst_element_get_static_pad(rtcp_sink, "sink");
    if (!send_rtcp_pad || !rtcp_sink_pad || gst_pad_link(send_rtcp_pad, rtcp_sink_pad) != GST_PAD_LINK_OK) {
        error = "could not link GStreamer RTCP sender pads";
        if (send_rtcp_pad) {
            gst_object_unref(send_rtcp_pad);
        }
        if (rtcp_sink_pad) {
            gst_object_unref(rtcp_sink_pad);
        }
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(pipeline);
        return false;
    }
    gst_object_unref(send_rtcp_pad);
    gst_object_unref(rtcp_sink_pad);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipeline_ = pipeline;
    }
    return true;
}

GstElement *GStreamerMediaReceiver::make_rtx_receiver() const
{
    GstBin *aux_bin = GST_BIN(gst_bin_new("cambridge-rtx-receiver"));
    GstElement *receiver = gst_element_factory_make("rtprtxreceive", "rtx-receiver");
    if (!aux_bin || !receiver) {
        if (aux_bin) {
            gst_object_unref(aux_bin);
        }
        return nullptr;
    }
    GstStructure *map = gst_structure_new(
        kRtxPayloadMapName,
        std::to_string(config_.payload_type).c_str(), G_TYPE_UINT,
        static_cast<guint>(config_.rtx_payload_type),
        nullptr);
    if (!map) {
        gst_object_unref(aux_bin);
        gst_object_unref(receiver);
        return nullptr;
    }
    g_object_set(receiver, "payload-type-map", map, nullptr);
    gst_structure_free(map);
    gst_bin_add(GST_BIN(aux_bin), receiver);

    GstPad *receiver_sink = gst_element_get_static_pad(receiver, "sink");
    GstPad *receiver_source = gst_element_get_static_pad(receiver, "src");
    GstPad *sink_ghost = receiver_sink ? gst_ghost_pad_new(kAuxSinkPadName, receiver_sink) : nullptr;
    GstPad *source_ghost = receiver_source ? gst_ghost_pad_new(kAuxSourcePadName, receiver_source) : nullptr;
    if (receiver_sink) {
        gst_object_unref(receiver_sink);
    }
    if (receiver_source) {
        gst_object_unref(receiver_source);
    }
    const bool sink_added = sink_ghost &&
                            gst_element_add_pad(GST_ELEMENT(aux_bin), sink_ghost);
    const bool source_added = source_ghost &&
                              gst_element_add_pad(GST_ELEMENT(aux_bin), source_ghost);
    if (!sink_added || !source_added) {
        if (sink_ghost && !sink_added) {
            gst_object_unref(sink_ghost);
        }
        if (source_ghost && !source_added) {
            gst_object_unref(source_ghost);
        }
        gst_object_unref(aux_bin);
        return nullptr;
    }
    return GST_ELEMENT(aux_bin);
}

GstElement *GStreamerMediaReceiver::request_aux_receiver(GstElement *, guint session, gpointer user_data)
{
    auto *receiver = static_cast<GStreamerMediaReceiver *>(user_data);
    if (!receiver || session != kVideoSession) {
        return nullptr;
    }
    return receiver->make_rtx_receiver();
}

void GStreamerMediaReceiver::configure_jitterbuffer(
    GstElement *, GstElement *jitterbuffer, guint session, guint, gpointer user_data)
{
    auto *receiver = static_cast<GStreamerMediaReceiver *>(user_data);
    if (session != kVideoSession) {
        return;
    }
    g_object_set(
        jitterbuffer,
        "latency", receiver->config_.jitter_latency_ms,
        "drop-on-latency", kDropOnLatency,
        "do-retransmission", kRetransmissionEnabled,
        "do-lost", kLostEventsEnabled,
        "rtx-deadline", kRtxDeadlineMs,
        "rtx-retry-period", kRtxRetryPeriodMs,
        nullptr);
}

void GStreamerMediaReceiver::on_rtp_pad_added(GstElement *, GstPad *pad, gpointer user_data)
{
    auto *receiver = static_cast<GStreamerMediaReceiver *>(user_data);
    const gchar *name = GST_PAD_NAME(pad);
    if (!name || !g_str_has_prefix(name, kRtpSourcePadPrefix)) {
        return;
    }
    GstElement *pipeline = nullptr;
    {
        std::lock_guard<std::mutex> lock(receiver->mutex_);
        pipeline = receiver->pipeline_;
    }
    if (!pipeline) {
        return;
    }
    GstElement *depay = gst_bin_get_by_name(GST_BIN(pipeline), "h264-depay");
    GstPad *depay_sink = depay ? gst_element_get_static_pad(depay, "sink") : nullptr;
    if (depay_sink && !gst_pad_is_linked(depay_sink) &&
        gst_pad_link(pad, depay_sink) != GST_PAD_LINK_OK) {
        receiver->report_pipeline_error("could not link the GStreamer H.264 receiver pad");
    }
    if (depay_sink) {
        gst_object_unref(depay_sink);
    }
    if (depay) {
        gst_object_unref(depay);
    }
}

GstFlowReturn GStreamerMediaReceiver::on_new_sample(GstAppSink *sink, gpointer user_data)
{
    auto *receiver = static_cast<GStreamerMediaReceiver *>(user_data);
    {
        std::lock_guard<std::mutex> lock(receiver->mutex_);
        if (!receiver->session_active_) {
            return GST_FLOW_FLUSHING;
        }
    }
    GstSample *sample = gst_app_sink_pull_sample(sink);
    if (!sample) {
        return GST_FLOW_EOS;
    }
    GstBuffer *buffer = gst_sample_get_buffer(sample);
    const gsize size = buffer ? gst_buffer_get_size(buffer) : 0;
    if (!buffer || size == 0 || size > receiver->config_.maximum_access_unit_bytes) {
        gst_sample_unref(sample);
        receiver->report_pipeline_error("GStreamer appsink produced an invalid H.264 access unit");
        return GST_FLOW_ERROR;
    }
    const GstClockTime pts = GST_BUFFER_PTS(buffer);
    if (!GST_CLOCK_TIME_IS_VALID(pts)) {
        gst_sample_unref(sample);
        receiver->report_pipeline_error("GStreamer appsink produced an H.264 access unit without PTS");
        return GST_FLOW_ERROR;
    }
    GstMapInfo map{};
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        receiver->report_pipeline_error("GStreamer could not map an H.264 access unit");
        return GST_FLOW_ERROR;
    }
    AccessUnit access_unit;
    access_unit.annex_b.assign(map.data, map.data + map.size);
    access_unit.rtp_timestamp = static_cast<std::uint32_t>(
        gst_util_uint64_scale(pts, receiver->config_.clock_rate_hz, GST_SECOND));
    access_unit.receive_time_ns = monotonic_time_ns();
    gst_buffer_unmap(buffer, &map);
    gst_sample_unref(sample);
    receiver->access_units_delivered_.fetch_add(1);
    receiver->access_unit_bytes_delivered_.fetch_add(access_unit.annex_b.size());
    if (receiver->access_unit_callback_) {
        receiver->access_unit_callback_(std::move(access_unit));
    }
    return GST_FLOW_OK;
}

gboolean GStreamerMediaReceiver::on_bus_message(GstBus *, GstMessage *message, gpointer user_data)
{
    auto *receiver = static_cast<GStreamerMediaReceiver *>(user_data);
    if (!receiver) {
        return G_SOURCE_REMOVE;
    }
    switch (GST_MESSAGE_TYPE(message)) {
    case GST_MESSAGE_ERROR:
        receiver->report_pipeline_error(error_message(message, false));
        return G_SOURCE_REMOVE;
    case GST_MESSAGE_WARNING:
        g_printerr("[cambridge] %s\n", error_message(message, true).c_str());
        return G_SOURCE_CONTINUE;
    case GST_MESSAGE_EOS:
        receiver->report_pipeline_error("GStreamer receiver pipeline reached EOS");
        return G_SOURCE_REMOVE;
    case GST_MESSAGE_STATE_CHANGED:
        return G_SOURCE_CONTINUE;
    default:
        return G_SOURCE_CONTINUE;
    }
}

void GStreamerMediaReceiver::report_pipeline_error(const std::string &message)
{
    GMainLoop *loop = nullptr;
    ErrorCallback callback;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!session_active_) {
            return;
        }
        session_active_ = false;
        loop = loop_;
        callback = error_callback_;
    }
    if (callback) {
        callback(message);
    }
    if (loop) {
        g_main_loop_quit(loop);
    }
}

void GStreamerMediaReceiver::signal_startup(bool success, const std::string &error)
{
    std::lock_guard<std::mutex> lock(mutex_);
    startup_success_ = success;
    startup_error_ = error;
    startup_complete_ = true;
    startup_condition_.notify_all();
}

} // namespace cambridge
