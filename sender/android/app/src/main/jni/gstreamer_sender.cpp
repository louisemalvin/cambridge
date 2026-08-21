#include "gstreamer_sender.hpp"

#include "protocol_contract.generated.hpp"

#include <gst/app/gstappsrc.h>
#include <gst/gst.h>
#include <gst/rtp/gstrtpdefs.h>
#include <gst/rtp/gstrtphdrext.h>
#include <gst/video/video-event.h>

#include <array>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <thread>
#include <utility>

namespace contract = cambridge::contract;

namespace {

constexpr guint kVideoSession = static_cast<guint>(contract::kRtpSessionIndex);
constexpr gboolean kSynchronizedSink = FALSE;
constexpr gboolean kAsynchronousSink = FALSE;
constexpr gboolean kLiveSource = TRUE;
constexpr gboolean kDoTimestampSourceBuffers = FALSE;
constexpr gboolean kBlockingAppsrc = TRUE;
constexpr gboolean kEmitAppsrcSignals = FALSE;
constexpr gint kAppsrcLeakyTypeNone = GST_APP_LEAKY_TYPE_NONE;
constexpr guint kAppsrcMaximumBuffers = 1;
constexpr guint64 kAppsrcUnlimitedBytes = 0;
constexpr GstClockTime kAppsrcUnlimitedTime = 0;
constexpr GstRTPProfile kRtpProfileFeedback = GST_RTP_PROFILE_AVPF;
constexpr guint kRtxCacheRetentionMs = 500;
constexpr gint kPayloaderConfigIntervalEveryIdr = -1;
constexpr gint kPayloaderAggregateModeNone = 0;
constexpr guint kDiagnosticsIntervalMs = 1'000;
constexpr char kRtpAddress[] = "0.0.0.0";
constexpr char kAppsrcName[] = "h264-appsrc";
constexpr char kRtpBinName[] = "rtpbin";
constexpr char kRtxSenderName[] = "rtx-sender";
constexpr char kGccName[] = "gcc-bwe";
constexpr char kCurrentLevelBuffersProperty[] = "current-level-buffers";
constexpr char kCurrentLevelTimeProperty[] = "current-level-time";
constexpr char kDroppedBuffersProperty[] = "dropped";
constexpr char kEstimatedBitrateProperty[] = "estimated-bitrate";
constexpr char kRtxRequestsProperty[] = "num-rtx-requests";
constexpr char kRtxPacketsProperty[] = "num-rtx-packets";
constexpr char kSessionStatsProperty[] = "stats";
constexpr char kRtcpCapsName[] = "application/x-rtcp";
constexpr char kH264CapsName[] = "video/x-h264";
constexpr char kByteStreamFormat[] = "byte-stream";
constexpr char kAccessUnitAlignment[] = "au";
constexpr char kRtxPayloadMapName[] = "application/x-rtp-pt-map";
constexpr char kRtcpFeedbackNackPliField[] = "rtcp-fb-nack-pli";
constexpr char kRtcpFeedbackCcmFirField[] = "rtcp-fb-ccm-fir";
constexpr char kEmptyRtcpFeedbackValue[] = "";

struct RequiredFactory {
    const char *name;
    const char *description;
};

constexpr std::array<RequiredFactory, 9> kRequiredFactories = {{
    {"appsrc", "application source"},
    {"h264parse", "H.264 parser"},
    {"rtph264pay", "H.264 RTP payloader"},
    {"rtpbin", "RTP session manager"},
    {"udpsink", "UDP sink"},
    {"udpsrc", "UDP source"},
    {"rtprtxsend", "RTP retransmission sender"},
    {"rtpgccbwe", "GCC bandwidth estimator"},
    {"rtphdrexttwcc", "TWCC RTP header extension"},
}};

std::string gst_message_error(GstMessage *message, bool warning)
{
    GError *error = nullptr;
    gchar *debug = nullptr;
    if (warning) {
        gst_message_parse_warning(message, &error, &debug);
    } else {
        gst_message_parse_error(message, &error, &debug);
    }
    std::ostringstream output;
    output << (warning ? "GStreamer warning" : "GStreamer error");
    GstObject *source = GST_MESSAGE_SRC(message);
    if (source) {
        output << " element=" << GST_OBJECT_NAME(source);
    }
    if (error) {
        output << " domain=" << g_quark_to_string(error->domain)
               << " code=" << error->code << " message=" << error->message;
        g_error_free(error);
    }
    if (debug) {
        output << " debug=" << debug;
        g_free(debug);
    }
    return output.str();
}

bool valid_port(std::uint16_t port)
{
    return port >= contract::kMinimumPort && port <= contract::kMaximumPort;
}

GstCaps *make_h264_caps()
{
    return gst_caps_new_simple(
        kH264CapsName,
        "stream-format", G_TYPE_STRING, kByteStreamFormat,
        "alignment", G_TYPE_STRING, kAccessUnitAlignment,
        nullptr);
}

GstCaps *make_rtcp_caps()
{
    return gst_caps_new_empty_simple(kRtcpCapsName);
}

GstStructure *make_payload_type_map(std::uint8_t payload_type, std::uint8_t rtx_payload_type)
{
    return gst_structure_new(
        kRtxPayloadMapName,
        std::to_string(payload_type).c_str(), G_TYPE_UINT,
        static_cast<guint>(rtx_payload_type),
        nullptr);
}

std::string session_pad_name(const char *prefix)
{
    return std::string(prefix) + std::to_string(kVideoSession);
}

std::uint64_t structure_uint64(const GstStructure *structure, const char *field)
{
    guint64 value = 0;
    if (structure && gst_structure_get_uint64(structure, field, &value)) {
        return value;
    }
    guint value32 = 0;
    if (structure && gst_structure_get_uint(structure, field, &value32)) {
        return value32;
    }
    return 0;
}

GstPadProbeReturn add_rtcp_feedback_caps(GstPad *, GstPadProbeInfo *info, gpointer)
{
    GstEvent *event = info ? GST_PAD_PROBE_INFO_EVENT(info) : nullptr;
    if (!event || GST_EVENT_TYPE(event) != GST_EVENT_CAPS) {
        return GST_PAD_PROBE_OK;
    }

    GstCaps *caps = nullptr;
    gst_event_parse_caps(event, &caps);
    if (!caps || gst_caps_is_empty(caps)) {
        return GST_PAD_PROBE_OK;
    }
    GstCaps *feedback_caps = gst_caps_copy(caps);
    gst_caps_set_simple(
        feedback_caps,
        kRtcpFeedbackNackPliField, G_TYPE_STRING, kEmptyRtcpFeedbackValue,
        kRtcpFeedbackCcmFirField, G_TYPE_STRING, kEmptyRtcpFeedbackValue,
        nullptr);
    GstEvent *replacement = gst_event_new_caps(feedback_caps);
    gst_event_set_seqnum(replacement, gst_event_get_seqnum(event));
    gst_caps_unref(feedback_caps);
    gst_event_unref(event);
    GST_PAD_PROBE_INFO_DATA(info) = replacement;
    return GST_PAD_PROBE_OK;
}

} // namespace

struct GStreamerSender::Impl {
    explicit Impl(Callbacks callback_set) : callbacks(std::move(callback_set)) {}

    ~Impl()
    {
        stop();
    }

    bool start(const Config &sender_config, std::string &error);
    bool push_access_unit(const std::uint8_t *data, std::size_t size,
                          std::int64_t presentation_time_us, bool keyframe);
    void stop();

    static GstElement *request_aux_sender(GstElement *, guint session, gpointer user_data);
    static void on_estimated_bitrate_changed(GObject *, GParamSpec *, gpointer user_data);
    static GstPadProbeReturn on_upstream_event(GstPad *, GstPadProbeInfo *info, gpointer user_data);
    static gboolean on_bus_message(GstBus *, GstMessage *message, gpointer user_data);
    static gboolean on_diagnostics(gpointer user_data);

    GstElement *make_aux_sender(std::string &error);
    bool build_pipeline(std::string &error);
    bool check_required_factories(std::string &error) const;
    void report_error(const std::string &message);
    void emit_diagnostics();
    void run_main_loop();

    mutable std::mutex mutex;
    Callbacks callbacks;
    Config config;
    GstElement *pipeline = nullptr;
    GstElement *appsrc = nullptr;
    GMainContext *context = nullptr;
    GMainLoop *loop = nullptr;
    GSource *diagnostics_source = nullptr;
    std::thread main_loop_thread;
    bool active = false;
    bool stopping = false;
    bool error_reported = false;
};

GStreamerSender::GStreamerSender(Callbacks callbacks)
    : impl_(std::make_unique<Impl>(std::move(callbacks)))
{
}

GStreamerSender::~GStreamerSender() = default;

bool GStreamerSender::start(const Config &config, std::string &error)
{
    return impl_->start(config, error);
}

bool GStreamerSender::push_access_unit(const std::uint8_t *data, std::size_t size,
                                       std::int64_t presentation_time_us, bool keyframe)
{
    return impl_->push_access_unit(data, size, presentation_time_us, keyframe);
}

void GStreamerSender::stop()
{
    impl_->stop();
}

bool GStreamerSender::Impl::check_required_factories(std::string &error) const
{
    for (const RequiredFactory &factory : kRequiredFactories) {
        GstElementFactory *found = gst_element_factory_find(factory.name);
        if (!found) {
            error = std::string("required GStreamer factory is unavailable: ") + factory.name +
                    " (" + factory.description + ")";
            return false;
        }
        gst_object_unref(found);
    }
    return true;
}

bool GStreamerSender::Impl::start(const Config &sender_config, std::string &error)
{
    stop();
    error.clear();
    if (sender_config.remote_host.empty() || !valid_port(sender_config.remote_rtp_port) ||
        !valid_port(sender_config.remote_rtcp_port) || !valid_port(sender_config.local_rtcp_port) ||
        sender_config.remote_rtp_port == sender_config.remote_rtcp_port ||
        sender_config.target_bitrate_bps < contract::kMinimumBitrateBps ||
        sender_config.mtu_bytes < contract::kRtpMtuBytes) {
        error = "invalid GStreamer sender configuration";
        return false;
    }
    if (!gst_is_initialized()) {
        error = "GStreamer runtime was not initialized";
        return false;
    }
    if (!check_required_factories(error)) {
        return false;
    }
    config = sender_config;
    context = g_main_context_new();
    loop = g_main_loop_new(context, FALSE);
    if (!context || !loop || !build_pipeline(error)) {
        if (pipeline) {
            gst_element_set_state(pipeline, GST_STATE_NULL);
            gst_object_unref(pipeline);
            pipeline = nullptr;
        }
        if (loop) {
            g_main_loop_unref(loop);
            loop = nullptr;
        }
        if (context) {
            g_main_context_unref(context);
            context = nullptr;
        }
        return false;
    }

    GstBus *bus = gst_element_get_bus(pipeline);
    g_main_context_push_thread_default(context);
    gst_bus_add_watch(bus, &GStreamerSender::Impl::on_bus_message, this);
    diagnostics_source = g_timeout_source_new(kDiagnosticsIntervalMs);
    if (diagnostics_source) {
        g_source_set_callback(diagnostics_source, &GStreamerSender::Impl::on_diagnostics, this, nullptr);
        g_source_attach(diagnostics_source, context);
    }
    g_main_context_pop_thread_default(context);
    gst_object_unref(bus);

    {
        std::lock_guard<std::mutex> lock(mutex);
        active = true;
        stopping = false;
        error_reported = false;
    }
    main_loop_thread = std::thread(&GStreamerSender::Impl::run_main_loop, this);
    const GstStateChangeReturn result = gst_element_set_state(pipeline, GST_STATE_PLAYING);
    if (result == GST_STATE_CHANGE_FAILURE) {
        error = "GStreamer sender pipeline could not enter PLAYING";
        report_error(error);
        stop();
        return false;
    }
    return true;
}

bool GStreamerSender::Impl::build_pipeline(std::string &error)
{
    GstElement *new_pipeline = gst_pipeline_new("cambridge-gstreamer-sender");
    GstElement *new_appsrc = gst_element_factory_make("appsrc", kAppsrcName);
    GstElement *parser = gst_element_factory_make("h264parse", "h264-parse");
    GstElement *payloader = gst_element_factory_make("rtph264pay", "h264-payloader");
    GstElement *rtpbin = gst_element_factory_make("rtpbin", kRtpBinName);
    GstElement *rtp_sink = gst_element_factory_make("udpsink", "rtp-sink");
    GstElement *rtcp_sink = gst_element_factory_make("udpsink", "rtcp-sink");
    GstElement *rtcp_source = gst_element_factory_make("udpsrc", "rtcp-source");
    GstElement *twcc = gst_element_factory_make("rtphdrexttwcc", "twcc");
    if (!new_pipeline || !new_appsrc || !parser || !payloader || !rtpbin || !rtp_sink ||
        !rtcp_sink || !rtcp_source || !twcc) {
        error = "required GStreamer sender element could not be created";
        const std::array<GstElement *, 8> standalone_elements = {
            new_appsrc, parser, payloader, rtpbin, rtp_sink, rtcp_sink, rtcp_source, twcc,
        };
        for (GstElement *element : standalone_elements) {
            if (element) {
                gst_object_unref(element);
            }
        }
        if (new_pipeline) {
            gst_object_unref(new_pipeline);
        }
        return false;
    }
    GstCaps *h264_caps = make_h264_caps();
    GstCaps *rtcp_caps = make_rtcp_caps();
    g_object_set(
        new_appsrc,
        "caps", h264_caps,
        "is-live", kLiveSource,
        "format", GST_FORMAT_TIME,
        "do-timestamp", kDoTimestampSourceBuffers,
        "leaky-type", kAppsrcLeakyTypeNone,
        "block", kBlockingAppsrc,
        "max-buffers", kAppsrcMaximumBuffers,
        "max-bytes", kAppsrcUnlimitedBytes,
        "max-time", kAppsrcUnlimitedTime,
        "emit-signals", kEmitAppsrcSignals,
        nullptr);
    g_object_set(
        payloader,
        "pt", static_cast<guint>(contract::kRtpPayloadType),
        "mtu", static_cast<guint>(config.mtu_bytes),
        "config-interval", kPayloaderConfigIntervalEveryIdr,
        "aggregate-mode", kPayloaderAggregateModeNone,
        nullptr);
    g_object_set(
        rtpbin,
        "rtp-profile", kRtpProfileFeedback,
        nullptr);
    g_object_set(
        rtp_sink,
        "host", config.remote_host.c_str(),
        "port", static_cast<gint>(config.remote_rtp_port),
        "sync", kSynchronizedSink,
        "async", kAsynchronousSink,
        nullptr);
    g_object_set(
        rtcp_sink,
        "host", config.remote_host.c_str(),
        "port", static_cast<gint>(config.remote_rtcp_port),
        "sync", kSynchronizedSink,
        "async", kAsynchronousSink,
        nullptr);
    g_object_set(
        rtcp_source,
        "address", kRtpAddress,
        "port", static_cast<gint>(config.local_rtcp_port),
        "caps", rtcp_caps,
        nullptr);
    gst_caps_unref(h264_caps);
    gst_caps_unref(rtcp_caps);

    gst_rtp_header_extension_set_id(
        GST_RTP_HEADER_EXTENSION(twcc), static_cast<guint>(contract::kTwccExtensionId));
    g_signal_emit_by_name(payloader, "add-extension", twcc);
    gst_object_unref(twcc);

    gst_bin_add_many(
        GST_BIN(new_pipeline), new_appsrc, parser, payloader, rtpbin, rtp_sink, rtcp_sink,
        rtcp_source, nullptr);
    if (!gst_element_link_many(new_appsrc, parser, payloader, nullptr)) {
        error = "could not link the GStreamer H.264 sender path";
        gst_element_set_state(new_pipeline, GST_STATE_NULL);
        gst_object_unref(new_pipeline);
        return false;
    }

    g_signal_connect(
        rtpbin, "request-aux-sender", G_CALLBACK(&GStreamerSender::Impl::request_aux_sender), this);
    GstPad *payloader_src = gst_element_get_static_pad(payloader, "src");
    if (payloader_src) {
        gst_pad_add_probe(
            payloader_src, GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM,
            &add_rtcp_feedback_caps, nullptr, nullptr);
    }
    const std::string send_rtp_sink_name = session_pad_name("send_rtp_sink_");
    GstPad *rtp_sink_pad = gst_element_request_pad_simple(rtpbin, send_rtp_sink_name.c_str());
    if (!payloader_src || !rtp_sink_pad || gst_pad_link(payloader_src, rtp_sink_pad) != GST_PAD_LINK_OK) {
        error = "could not link the GStreamer RTP sender path";
        if (payloader_src) {
            gst_object_unref(payloader_src);
        }
        if (rtp_sink_pad) {
            gst_object_unref(rtp_sink_pad);
        }
        gst_element_set_state(new_pipeline, GST_STATE_NULL);
        gst_object_unref(new_pipeline);
        return false;
    }
    gst_object_unref(payloader_src);
    gst_object_unref(rtp_sink_pad);

    const std::string send_rtp_src_name = session_pad_name("send_rtp_src_");
    GstPad *rtp_src_pad = gst_element_get_static_pad(rtpbin, send_rtp_src_name.c_str());
    GstPad *rtp_output_sink = gst_element_get_static_pad(rtp_sink, "sink");
    if (!rtp_src_pad || !rtp_output_sink || gst_pad_link(rtp_src_pad, rtp_output_sink) != GST_PAD_LINK_OK) {
        error = "could not link the GStreamer RTP UDP sink";
        if (rtp_src_pad) {
            gst_object_unref(rtp_src_pad);
        }
        if (rtp_output_sink) {
            gst_object_unref(rtp_output_sink);
        }
        gst_element_set_state(new_pipeline, GST_STATE_NULL);
        gst_object_unref(new_pipeline);
        return false;
    }
    gst_object_unref(rtp_src_pad);
    gst_object_unref(rtp_output_sink);

    GstPad *rtcp_src_pad = gst_element_get_static_pad(rtcp_source, "src");
    const std::string recv_rtcp_sink_name = session_pad_name("recv_rtcp_sink_");
    GstPad *rtcp_input_sink = gst_element_request_pad_simple(rtpbin, recv_rtcp_sink_name.c_str());
    if (!rtcp_src_pad || !rtcp_input_sink || gst_pad_link(rtcp_src_pad, rtcp_input_sink) != GST_PAD_LINK_OK) {
        error = "could not link the GStreamer incoming RTCP path";
        if (rtcp_src_pad) {
            gst_object_unref(rtcp_src_pad);
        }
        if (rtcp_input_sink) {
            gst_object_unref(rtcp_input_sink);
        }
        gst_element_set_state(new_pipeline, GST_STATE_NULL);
        gst_object_unref(new_pipeline);
        return false;
    }
    gst_object_unref(rtcp_src_pad);
    gst_object_unref(rtcp_input_sink);

    const std::string send_rtcp_src_name = session_pad_name("send_rtcp_src_");
    GstPad *rtcp_output_src = gst_element_request_pad_simple(rtpbin, send_rtcp_src_name.c_str());
    GstPad *rtcp_output_sink = gst_element_get_static_pad(rtcp_sink, "sink");
    if (!rtcp_output_src || !rtcp_output_sink ||
        gst_pad_link(rtcp_output_src, rtcp_output_sink) != GST_PAD_LINK_OK) {
        error = "could not link the GStreamer outgoing RTCP path";
        if (rtcp_output_src) {
            gst_object_unref(rtcp_output_src);
        }
        if (rtcp_output_sink) {
            gst_object_unref(rtcp_output_sink);
        }
        gst_element_set_state(new_pipeline, GST_STATE_NULL);
        gst_object_unref(new_pipeline);
        return false;
    }
    gst_object_unref(rtcp_output_src);
    gst_object_unref(rtcp_output_sink);

    GstPad *appsrc_src = gst_element_get_static_pad(new_appsrc, "src");
    if (!appsrc_src) {
        error = "could not inspect the GStreamer H.264 sender path";
        gst_element_set_state(new_pipeline, GST_STATE_NULL);
        gst_object_unref(new_pipeline);
        return false;
    }
    gst_pad_add_probe(
        appsrc_src, GST_PAD_PROBE_TYPE_EVENT_UPSTREAM, &GStreamerSender::Impl::on_upstream_event, this,
        nullptr);
    gst_object_unref(appsrc_src);

    {
        std::lock_guard<std::mutex> lock(mutex);
        pipeline = new_pipeline;
        appsrc = new_appsrc;
    }
    return true;
}

GstElement *GStreamerSender::Impl::make_aux_sender(std::string &error)
{
    GstBin *aux_bin = GST_BIN(gst_bin_new("cambridge-rtp-aux-sender"));
    GstElement *rtx_sender = gst_element_factory_make("rtprtxsend", kRtxSenderName);
    GstElement *gcc = gst_element_factory_make("rtpgccbwe", kGccName);
    if (!aux_bin || !rtx_sender || !gcc) {
        error = "required GStreamer RTP auxiliary sender element could not be created";
        if (aux_bin) {
            gst_object_unref(aux_bin);
        }
        return nullptr;
    }
    GstStructure *payload_map = make_payload_type_map(
        static_cast<std::uint8_t>(contract::kRtpPayloadType),
        static_cast<std::uint8_t>(contract::kRtxPayloadType));
    g_object_set(
        rtx_sender,
        "payload-type-map", payload_map,
        "max-size-packets", 0u,
        "max-size-time", kRtxCacheRetentionMs,
        nullptr);
    g_object_set(
        gcc,
        "max-bitrate", static_cast<guint>(config.target_bitrate_bps),
        "estimated-bitrate", static_cast<guint>(config.target_bitrate_bps),
        nullptr);
    gst_structure_free(payload_map);
    g_signal_connect(gcc, "notify::estimated-bitrate",
                     G_CALLBACK(&GStreamerSender::Impl::on_estimated_bitrate_changed), this);
    gst_bin_add_many(aux_bin, rtx_sender, gcc, nullptr);
    if (!gst_element_link(rtx_sender, gcc)) {
        error = "could not link the GStreamer RTP auxiliary sender";
        gst_object_unref(aux_bin);
        return nullptr;
    }
    GstPad *aux_sink = gst_element_get_static_pad(rtx_sender, "sink");
    GstPad *aux_src = gst_element_get_static_pad(gcc, "src");
    if (!aux_sink || !aux_src) {
        if (aux_sink) {
            gst_object_unref(aux_sink);
        }
        if (aux_src) {
            gst_object_unref(aux_src);
        }
        gst_object_unref(aux_bin);
        error = "could not inspect the GStreamer RTP auxiliary sender pads";
        return nullptr;
    }
    GstPad *sink_ghost = gst_ghost_pad_new("sink_0", aux_sink);
    GstPad *src_ghost = gst_ghost_pad_new("src_0", aux_src);
    gst_object_unref(aux_sink);
    gst_object_unref(aux_src);
    if (!sink_ghost || !src_ghost || !gst_element_add_pad(GST_ELEMENT(aux_bin), sink_ghost) ||
        !gst_element_add_pad(GST_ELEMENT(aux_bin), src_ghost)) {
        if (sink_ghost && GST_PAD_PARENT(sink_ghost) == nullptr) {
            gst_object_unref(sink_ghost);
        }
        if (src_ghost && GST_PAD_PARENT(src_ghost) == nullptr) {
            gst_object_unref(src_ghost);
        }
        gst_object_unref(aux_bin);
        error = "could not expose the GStreamer RTP auxiliary sender pads";
        return nullptr;
    }
    return GST_ELEMENT(aux_bin);
}

GstElement *GStreamerSender::Impl::request_aux_sender(GstElement *, guint session, gpointer user_data)
{
    auto *sender = static_cast<GStreamerSender::Impl *>(user_data);
    if (!sender || session != kVideoSession) {
        return nullptr;
    }
    std::string error;
    GstElement *aux = sender->make_aux_sender(error);
    if (!aux && !error.empty()) {
        sender->report_error(error);
    }
    return aux;
}

void GStreamerSender::Impl::on_estimated_bitrate_changed(GObject *object, GParamSpec *, gpointer user_data)
{
    auto *sender = static_cast<GStreamerSender::Impl *>(user_data);
    if (!sender) {
        return;
    }
    guint bitrate = 0;
    g_object_get(object, "estimated-bitrate", &bitrate, nullptr);
    std::function<void(std::uint32_t)> callback;
    {
        std::lock_guard<std::mutex> lock(sender->mutex);
        if (!sender->active || sender->stopping) {
            return;
        }
        callback = sender->callbacks.estimated_bitrate_changed;
    }
    if (callback) {
        callback(bitrate);
    }
}

gboolean GStreamerSender::Impl::on_diagnostics(gpointer user_data)
{
    auto *sender = static_cast<GStreamerSender::Impl *>(user_data);
    if (!sender) {
        return G_SOURCE_REMOVE;
    }
    sender->emit_diagnostics();
    return G_SOURCE_CONTINUE;
}

void GStreamerSender::Impl::emit_diagnostics()
{
    GstElement *sender_pipeline = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (!active || stopping || !pipeline) {
            return;
        }
        sender_pipeline = pipeline;
    }

    GstElement *sender_appsrc = gst_bin_get_by_name(GST_BIN(sender_pipeline), kAppsrcName);
    GstElement *rtpbin = gst_bin_get_by_name(GST_BIN(sender_pipeline), kRtpBinName);
    GstElement *rtx_sender = gst_bin_get_by_name(GST_BIN(sender_pipeline), kRtxSenderName);
    GstElement *gcc = gst_bin_get_by_name(GST_BIN(sender_pipeline), kGccName);

    guint64 appsrc_queued_buffers = 0;
    guint64 appsrc_queued_time = 0;
    guint64 appsrc_dropped_buffers = 0;
    if (sender_appsrc) {
        g_object_get(
            sender_appsrc,
            kCurrentLevelBuffersProperty, &appsrc_queued_buffers,
            kCurrentLevelTimeProperty, &appsrc_queued_time,
            kDroppedBuffersProperty, &appsrc_dropped_buffers,
            nullptr);
    }

    guint estimated_bitrate = 0;
    guint rtx_requests = 0;
    guint rtx_packets = 0;
    if (gcc) {
        g_object_get(gcc, kEstimatedBitrateProperty, &estimated_bitrate, nullptr);
    }
    if (rtx_sender) {
        g_object_get(
            rtx_sender,
            kRtxRequestsProperty, &rtx_requests,
            kRtxPacketsProperty, &rtx_packets,
            nullptr);
    }

    guint session_received_nacks = 0;
    guint session_rtx_count = 0;
    GstStructure *session_stats = nullptr;
    GstElement *internal_session = nullptr;
    if (rtpbin) {
        g_signal_emit_by_name(rtpbin, "get-internal-session", kVideoSession, &internal_session);
    }
    if (internal_session) {
        g_object_get(internal_session, kSessionStatsProperty, &session_stats, nullptr);
        session_received_nacks = static_cast<guint>(
            structure_uint64(session_stats, "recv-nack-count"));
        session_rtx_count = static_cast<guint>(structure_uint64(session_stats, "rtx-count"));
        gst_object_unref(internal_session);
    }

    std::ostringstream output;
    output << "[cambridge] sender_summary targetBitrateBps=" << config.target_bitrate_bps
           << " gccEstimateBps=" << estimated_bitrate
           << " appsrcQueuedBuffers=" << appsrc_queued_buffers
           << " appsrcQueuedTimeNs=" << appsrc_queued_time
           << " appsrcDroppedBuffers=" << appsrc_dropped_buffers
           << " rtxRequests=" << rtx_requests
           << " rtxPackets=" << rtx_packets
           << " sessionReceivedNacks=" << session_received_nacks
           << " sessionRtxCount=" << session_rtx_count;
    g_message("%s", output.str().c_str());

    if (session_stats) {
        gst_structure_free(session_stats);
    }
    if (gcc) {
        gst_object_unref(gcc);
    }
    if (rtx_sender) {
        gst_object_unref(rtx_sender);
    }
    if (rtpbin) {
        gst_object_unref(rtpbin);
    }
    if (sender_appsrc) {
        gst_object_unref(sender_appsrc);
    }
}

GstPadProbeReturn GStreamerSender::Impl::on_upstream_event(GstPad *, GstPadProbeInfo *info, gpointer user_data)
{
    auto *sender = static_cast<GStreamerSender::Impl *>(user_data);
    GstEvent *event = info ? GST_PAD_PROBE_INFO_EVENT(info) : nullptr;
    if (!sender || !event || !gst_video_event_is_force_key_unit(event)) {
        return GST_PAD_PROBE_OK;
    }
    std::function<void()> callback;
    {
        std::lock_guard<std::mutex> lock(sender->mutex);
        if (!sender->active || sender->stopping) {
            return GST_PAD_PROBE_OK;
        }
        callback = sender->callbacks.keyframe_requested;
    }
    if (callback) {
        callback();
    }
    return GST_PAD_PROBE_OK;
}

gboolean GStreamerSender::Impl::on_bus_message(GstBus *, GstMessage *message, gpointer user_data)
{
    auto *sender = static_cast<GStreamerSender::Impl *>(user_data);
    if (!sender) {
        return G_SOURCE_REMOVE;
    }
    switch (GST_MESSAGE_TYPE(message)) {
    case GST_MESSAGE_ERROR:
        sender->report_error(gst_message_error(message, false));
        return G_SOURCE_REMOVE;
    case GST_MESSAGE_WARNING:
        g_printerr("[cambridge] %s\n", gst_message_error(message, true).c_str());
        return G_SOURCE_CONTINUE;
    case GST_MESSAGE_EOS:
        sender->report_error("GStreamer sender pipeline reached EOS");
        return G_SOURCE_REMOVE;
    case GST_MESSAGE_STATE_CHANGED:
        return G_SOURCE_CONTINUE;
    default:
        return G_SOURCE_CONTINUE;
    }
}

bool GStreamerSender::Impl::push_access_unit(const std::uint8_t *data, std::size_t size,
                                             std::int64_t presentation_time_us, bool keyframe)
{
    if (!data || size == 0 || size > contract::kMaximumAccessUnitBytes || presentation_time_us < 0) {
        report_error("invalid H.264 access unit supplied to GStreamer");
        return false;
    }
    GstElement *source = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (!active || stopping || !appsrc) {
            return false;
        }
        source = GST_ELEMENT(gst_object_ref(appsrc));
    }
    GstBuffer *buffer = gst_buffer_new_allocate(nullptr, size, nullptr);
    if (!buffer) {
        gst_object_unref(source);
        report_error("GStreamer could not allocate an H.264 access unit buffer");
        return false;
    }
    gst_buffer_fill(buffer, 0, data, size);
    const GstClockTime timestamp = static_cast<GstClockTime>(presentation_time_us) * GST_USECOND;
    GST_BUFFER_PTS(buffer) = timestamp;
    GST_BUFFER_DTS(buffer) = timestamp;
    if (!keyframe) {
        GST_BUFFER_FLAG_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);
    }
    const GstFlowReturn result = gst_app_src_push_buffer(GST_APP_SRC(source), buffer);
    gst_object_unref(source);
    if (result != GST_FLOW_OK) {
        report_error("GStreamer appsrc rejected an H.264 access unit");
        return false;
    }
    return true;
}

void GStreamerSender::Impl::report_error(const std::string &message)
{
    std::function<void(const std::string &)> callback;
    GMainLoop *main_loop = nullptr;
    GstElement *failed_pipeline = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (stopping || error_reported) {
            return;
        }
        error_reported = true;
        active = false;
        callback = callbacks.transport_error;
        main_loop = loop;
        if (pipeline) {
            failed_pipeline = GST_ELEMENT(gst_object_ref(pipeline));
        }
    }
    if (callback) {
        callback(message);
    }
    if (failed_pipeline) {
        gst_element_set_state(failed_pipeline, GST_STATE_NULL);
        gst_object_unref(failed_pipeline);
    }
    if (main_loop) {
        g_main_loop_quit(main_loop);
    }
}

void GStreamerSender::Impl::run_main_loop()
{
    GMainLoop *main_loop = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex);
        main_loop = loop;
    }
    if (main_loop) {
        g_main_loop_run(main_loop);
    }
}

void GStreamerSender::Impl::stop()
{
    GMainLoop *main_loop = nullptr;
    GstElement *old_appsrc = nullptr;
    GstElement *old_pipeline = nullptr;
    GMainContext *old_context = nullptr;
    GSource *old_diagnostics_source = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex);
        stopping = true;
        active = false;
        main_loop = loop;
        if (appsrc) {
            old_appsrc = GST_ELEMENT(gst_object_ref(appsrc));
        }
    }
    if (old_appsrc) {
        gst_app_src_end_of_stream(GST_APP_SRC(old_appsrc));
        gst_object_unref(old_appsrc);
    }
    if (main_loop) {
        g_main_loop_quit(main_loop);
    }
    if (main_loop_thread.joinable()) {
        main_loop_thread.join();
    }
    {
        std::lock_guard<std::mutex> lock(mutex);
        old_pipeline = pipeline;
        old_context = context;
        old_diagnostics_source = diagnostics_source;
        pipeline = nullptr;
        appsrc = nullptr;
        loop = nullptr;
        context = nullptr;
        diagnostics_source = nullptr;
        error_reported = false;
    }
    if (old_pipeline) {
        gst_element_set_state(old_pipeline, GST_STATE_NULL);
        gst_object_unref(old_pipeline);
    }
    if (main_loop) {
        g_main_loop_unref(main_loop);
    }
    if (old_diagnostics_source) {
        g_source_destroy(old_diagnostics_source);
        g_source_unref(old_diagnostics_source);
    }
    if (old_context) {
        g_main_context_unref(old_context);
    }
}
