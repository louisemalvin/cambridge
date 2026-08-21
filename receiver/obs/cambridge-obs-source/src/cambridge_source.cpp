#include "cambridge_source.hpp"

#include "control_protocol.hpp"
#include "diagnostics.hpp"
#include "discovery_metadata.hpp"
#include "gstreamer_runtime.hpp"
#include "platform/interfaces/source_properties.hpp"
#include "protocol_contract.generated.hpp"

#if defined(__APPLE__)
#include <obs-data.h>
#include <obs-properties.h>
#else
#include <obs/obs-data.h>
#include <obs/obs-properties.h>
#endif

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <sstream>
#include <time.h>
#include <vector>

namespace cambridge {
namespace {

#ifndef CAMBRIDGE_VERSION
#define CAMBRIDGE_VERSION "unknown"
#endif
#ifndef CAMBRIDGE_GIT_COMMIT
#define CAMBRIDGE_GIT_COMMIT "unknown"
#endif

constexpr std::uint32_t kDimensionStep = 16;
constexpr std::uint32_t kUnsetDimension = 0;
constexpr std::uint32_t kMaximumLongEdge = contract::kDefaultMaximumLongEdge;
constexpr std::uint32_t kMaximumShortEdge = contract::kDefaultMaximumShortEdge;
constexpr std::uint32_t kMinimumQueueAgeMs = 1;
constexpr std::uint32_t kMaximumQueueAgeMs = 2000;
constexpr std::uint32_t kMinimumLiveAgeMs = 33;
constexpr std::uint32_t kMaximumLiveAgeMs = 5000;
constexpr std::uint64_t kNanosecondsPerMillisecond = 1'000'000ULL;
constexpr std::uint64_t kNanosecondsPerSecond = 1'000'000'000ULL;
constexpr std::uint32_t kPortStep = 1;
constexpr std::uint32_t kQueueAgeStep = 1;
constexpr std::uint32_t kLiveAgeStep = 1;
constexpr std::uint32_t kMailboxCapacity = contract::kMailboxCapacity;
constexpr std::uint32_t kQuarterTurnDegrees = 90;
constexpr std::uint32_t kThreeQuarterTurnDegrees = 270;
constexpr std::uint32_t kModuleProtocolVersion = contract::kProtocolVersion;
constexpr char kModuleVersion[] = CAMBRIDGE_VERSION;
constexpr char kPropertyControlPort[] = "control_port";
constexpr char kPropertyMediaRtpPort[] = "media_rtp_port";
constexpr char kPropertyMediaRtcpPort[] = "media_rtcp_port";
constexpr char kPropertyMaximumLongEdge[] = "maximum_long_edge";
constexpr char kPropertyMaximumShortEdge[] = "maximum_short_edge";
constexpr char kPropertyQueueAge[] = "maximum_decoder_queue_age_ms";
constexpr char kPropertyLiveAge[] = "maximum_live_frame_age_ms";
constexpr char kPropertyDecoderMode[] = "decoder_mode";
constexpr char kPropertyTransparentPlaceholder[] = "transparent_placeholder";
constexpr char kPropertyDiagnosticsPath[] = "diagnostics_path";
constexpr char kPropertyDumpDiagnostics[] = "dump_diagnostics";
constexpr char kPropertySetupInfo[] = "setup_info";
constexpr char kPropertyAdvancedSettings[] = "advanced_settings";
constexpr char kDiagnosticsOnSessionEndEnvironment[] =
    "CAMBRIDGE_TEST_WRITE_DIAGNOSTICS_ON_SESSION_END";

std::uint64_t monotonic_time_ns()
{
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<std::uint64_t>(time.tv_sec) * kNanosecondsPerSecond +
           static_cast<std::uint64_t>(time.tv_nsec);
}

void observe_maximum(std::atomic<std::uint64_t> &maximum, std::uint64_t value)
{
    auto current = maximum.load();
    while (value > current && !maximum.compare_exchange_weak(current, value)) {
    }
}

std::uint64_t milliseconds(std::uint64_t nanoseconds)
{
    return nanoseconds / kNanosecondsPerMillisecond;
}

std::uint32_t bounded_setting(obs_data_t *settings, const char *name, std::uint32_t fallback,
                              std::uint32_t minimum, std::uint32_t maximum)
{
    const long long stored = obs_data_get_int(settings, name);
    const auto value = static_cast<std::uint64_t>(stored == 0 ? fallback : std::max<long long>(stored, 0));
    return static_cast<std::uint32_t>(std::clamp<std::uint64_t>(value, minimum, maximum));
}

std::string setting_string(obs_data_t *settings, const char *name, const char *fallback)
{
    const char *value = obs_data_get_string(settings, name);
    return value && value[0] != '\0' ? value : fallback;
}

bool dump_button_clicked(obs_properties_t *, obs_property_t *, void *data)
{
    auto *source = static_cast<CamBridgeSource *>(data);
    if (source) {
        source->write_diagnostics();
    }
    return false;
}

bool test_diagnostics_on_session_end()
{
    const char *value = std::getenv(kDiagnosticsOnSessionEndEnvironment);
    return value && std::strcmp(value, "1") == 0;
}

} // namespace

CamBridgeSource::CamBridgeSource(SourceConfig config)
    : config_(std::move(config)),
      renderer_(RendererConfig{config_.transparent_placeholder}, create_native_frame_importer(),
                [this](const std::string &event) {
                    report(event);
                },
                [this](std::uint64_t generation, MediaPathFailureCode code,
                       const std::string &detail) {
                    post_media_path_failure({generation, code, detail});
                })
{
}

CamBridgeSource::~CamBridgeSource()
{
    stop();
}

SourceConfig CamBridgeSource::configuration() const
{
    std::lock_guard<std::mutex> lock(configuration_mutex_);
    return config_;
}

bool CamBridgeSource::start(std::string &error)
{
    if (started_) {
        return true;
    }
    const SourceConfig config = configuration();
    if (config.control_port < contract::kMinimumPort || config.media_rtp_port < contract::kMinimumPort ||
        config.media_rtcp_port < contract::kMinimumPort || config.control_port > contract::kMaximumPort ||
        config.media_rtp_port > contract::kMaximumPort || config.media_rtcp_port > contract::kMaximumPort ||
        config.control_port == config.media_rtp_port || config.control_port == config.media_rtcp_port ||
        config.media_rtp_port == config.media_rtcp_port) {
        error = "control, RTP, and RTCP ports must be distinct non-zero ports";
        return false;
    }
    if (!initialize_gstreamer(error)) {
        return false;
    }
    decoder_ = std::make_unique<Decoder>(
        [this](VideoFramePtr frame) { on_decoder_frame(std::move(frame)); },
        [this](const std::string &event) { on_decoder_event(event); },
        [this](std::uint64_t generation, MediaPathFailureCode code, const std::string &detail) {
            post_media_path_failure({generation, code, detail});
        });
    decoder_->start();

    GStreamerMediaReceiverConfig media_config;
    media_config.rtp_port = config.media_rtp_port;
    media_config.rtcp_port = config.media_rtcp_port;
    media_config.maximum_access_unit_bytes = contract::kMaximumAccessUnitBytes;
    media_config.payload_type = static_cast<std::uint8_t>(contract::kRtpPayloadType);
    media_config.rtx_payload_type = static_cast<std::uint8_t>(contract::kRtxPayloadType);
    media_config.clock_rate_hz = contract::kRtpClockRateHz;
    media_config.jitter_latency_ms = contract::kJitterLatencyMs;
    media_receiver_ = std::make_unique<GStreamerMediaReceiver>(
        media_config,
        [this](AccessUnit access_unit) { on_access_unit(std::move(access_unit)); },
        [this](const std::string &reason) { on_transport_error(reason); });

    control_server_ = std::make_unique<ControlServer>(
        config.control_port, config.media_rtp_port, config.media_rtcp_port, config.maximum_long_edge,
        config.maximum_short_edge,
        [this](const HelloMessage &hello, const std::string &peer, std::string &reason) {
            return on_hello(hello, peer, reason);
        },
        [this](const std::string &request_id) { return on_probe(request_id); },
        [this](const ControlMessage &message) { on_control_message(message); },
        [this] { on_control_disconnect(); });
    if (!control_server_->start(error)) {
        media_receiver_.reset();
        decoder_->stop();
        decoder_.reset();
        control_server_.reset();
        return false;
    }
    discovery_advertiser_ = create_discovery_advertiser();
    const DiscoveryConfig discovery_config = build_discovery_config(config.control_port);
    std::string discovery_error;
    if (!discovery_advertiser_->start(discovery_config, discovery_error)) {
        report("discovery_unavailable:" + discovery_error);
        discovery_advertiser_.reset();
    } else {
        report("discovery:service_type=" + std::string(discovery_service_type()));
    }
    started_ = true;
    report("identity:module=cambridge-obs-plugin version=" + std::string(kModuleVersion) +
           " commit=" + std::string(CAMBRIDGE_GIT_COMMIT) + " build=plugin protocol=" +
           std::to_string(kModuleProtocolVersion) + " config=obs-properties");
    report("listening:control=" + std::to_string(config.control_port) +
           ":rtp=" + std::to_string(config.media_rtp_port) + ":rtcp=" +
           std::to_string(config.media_rtcp_port) + ":drm=" + config.drm_device);
    report("bounds:mtu=" + std::to_string(contract::kRtpMtuBytes) +
           " au_bytes=" + std::to_string(contract::kMaximumAccessUnitBytes) +
           " au_count=" + std::to_string(contract::kMaximumInFlightAccessUnits) +
           " mailbox=" + std::to_string(kMailboxCapacity) +
           " texture_slots=" + std::to_string(contract::kTexturePoolSlots));
    return true;
}

void CamBridgeSource::stop()
{
    end_session();
    if (discovery_advertiser_) {
        discovery_advertiser_->stop();
        discovery_advertiser_.reset();
    }
    if (control_server_) {
        control_server_->stop();
        control_server_.reset();
    }
    if (media_receiver_) {
        media_receiver_->stop_session();
        media_receiver_.reset();
    }
    if (decoder_) {
        decoder_->stop();
        decoder_.reset();
    }
    started_ = false;
}

void CamBridgeSource::update(obs_data_t *settings)
{
    const SourceConfig next = source_config_from_settings(settings);
    bool network_changed = false;
    {
        std::lock_guard<std::mutex> lock(configuration_mutex_);
        network_changed = next.control_port != config_.control_port ||
                          next.media_rtp_port != config_.media_rtp_port ||
                          next.media_rtcp_port != config_.media_rtcp_port;
        config_ = next;
    }
    if (network_changed && started_) {
        stop();
        std::string error;
        if (!start(error)) {
            report("settings_network_restart_failed:" + error);
        }
    }
}

void CamBridgeSource::render(gs_effect_t *)
{
    const VideoFramePtr frame = mailbox_.acquire();
    const std::uint32_t output_width = width();
    const std::uint32_t output_height = height();
    std::uint64_t active_generation = 0;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        active_generation = stream_generation_;
    }
    const std::uint64_t now = monotonic_time_ns();
    const bool stale = !frame || frame->stream_generation != active_generation || frame->stale_deadline_ns < now;
    bool stale_changed = false;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        stale_changed = stale != stale_state_;
        stale_state_ = stale;
    }
    if (stale_changed) {
        if (stale) {
            stale_transitions_.fetch_add(1);
            report("live_frame_stale_placeholder");
        }
    }
    if (stale) {
        renderer_.render(nullptr, output_width, output_height);
        return;
    }
    const bool presented = renderer_.render(frame, output_width, output_height);
    if (presented && frame->frame_generation != last_rendered_frame_generation_.load()) {
        last_rendered_frame_generation_.store(frame->frame_generation);
        frames_rendered_.fetch_add(1);
        if (now >= frame->receive_time_ns) {
            observe_maximum(max_receive_to_render_ns_, now - frame->receive_time_ns);
        }
    }
}

void CamBridgeSource::tick(float)
{
    drain_media_path_failure();
    drain_transport_failure();
}

void CamBridgeSource::write_diagnostics()
{
    const SourceConfig config = configuration();
    DiagnosticsSnapshot snapshot;
    snapshot.version = kModuleVersion;
    snapshot.git_commit = CAMBRIDGE_GIT_COMMIT;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        snapshot.state = session_active_ ? "presenting" : "listening";
        snapshot.coded_width = active_width_;
        snapshot.coded_height = active_height_;
        snapshot.display_width = active_display_width_;
        snapshot.display_height = active_display_height_;
        snapshot.rotation_degrees = active_rotation_degrees_;
        snapshot.requested_decoder_mode = decoder_mode_name(requested_decoder_mode_);
        snapshot.session_media_path = session_media_path_name(active_media_path_);
        snapshot.native_setup_status = native_setup_attempted_ ? native_setup_status_name(native_setup_status_)
                                                               : "not_attempted";
        snapshot.native_setup_reason = native_setup_reason_;
        snapshot.media_path_locked = media_path_locked_;
        snapshot.last_media_path_failure_code = media_path_failure_code_name(last_media_path_failure_code_);
        snapshot.last_media_path_failure_detail = last_media_path_failure_detail_;
    }
    snapshot.decoder = decoder_ ? decoder_->decoder_name() : "uninitialized";
    snapshot.render = renderer_.render_mode();
    snapshot.mailbox_occupancy = mailbox_.occupancy();
    snapshot.frames_replaced = mailbox_.replaced_count();
    snapshot.frames_stale = stale_transitions_.load();
    snapshot.frames_decoded = decoder_ ? decoder_->frames_decoded() : 0;
    snapshot.frames_rendered = frames_rendered_.load();
    snapshot.media_path_failures = media_path_failures_.load();
    snapshot.native_import_failures = native_import_failures_.load();
    snapshot.native_pool_exhaustions = native_pool_exhaustions_.load();
    snapshot.cpu_frame_copies = renderer_.cpu_uploads();
    snapshot.gpu_copies = renderer_.gpu_copies();
    snapshot.dma_buf_import_failures = renderer_.import_failures();
    snapshot.access_units_delivered = media_receiver_ ? media_receiver_->access_units_delivered() : 0;
    snapshot.access_unit_bytes_delivered = media_receiver_ ? media_receiver_->access_unit_bytes_delivered() : 0;
    snapshot.transport_errors = transport_errors_.load();
    snapshot.decode_failures = decoder_ ? decoder_->decode_failures() : 0;
    snapshot.decoder_queue_drops = decoder_ ? decoder_->queue_drops() : 0;
    snapshot.decoder_queue_occupancy = decoder_ ? decoder_->queue_occupancy() : 0;
    snapshot.max_receive_to_decode_ms = milliseconds(max_receive_to_decode_ns_.load());
    snapshot.max_receive_to_publish_ms = milliseconds(max_receive_to_publish_ns_.load());
    snapshot.max_receive_to_render_ms = milliseconds(max_receive_to_render_ns_.load());
    snapshot.configured_control_port = config.control_port;
    snapshot.configured_media_rtp_port = config.media_rtp_port;
    snapshot.configured_media_rtcp_port = config.media_rtcp_port;
    snapshot.configured_maximum_decoder_queue_age_ms = config.maximum_decoder_queue_age_ms;
    snapshot.configured_maximum_live_frame_age_ms = config.maximum_live_frame_age_ms;
    std::string error;
    if (!::cambridge::write_diagnostics(snapshot, config.diagnostics_path, error)) {
        report("diagnostics_write_failed:path=" + config.diagnostics_path + ":reason=" + error);
        return;
    }
    report("diagnostics_written:path=" + config.diagnostics_path);
}

std::uint32_t CamBridgeSource::width() const
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    return active_display_width_ == kUnsetDimension ? contract::kMinimumDimension : active_display_width_;
}

std::uint32_t CamBridgeSource::height() const
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    return active_display_height_ == kUnsetDimension ? contract::kMinimumDimension : active_display_height_;
}

bool CamBridgeSource::on_hello(const HelloMessage &hello, const std::string &peer_address, std::string &error)
{
    const SourceConfig config = configuration();
    const auto long_edge = [](std::uint32_t width, std::uint32_t height) {
        return std::max(width, height);
    };
    const auto short_edge = [](std::uint32_t width, std::uint32_t height) {
        return std::min(width, height);
    };
    const bool swaps_geometry = hello.rotation_degrees == kQuarterTurnDegrees ||
                                hello.rotation_degrees == kThreeQuarterTurnDegrees;
    const std::uint32_t display_width = swaps_geometry ? hello.coded_height : hello.coded_width;
    const std::uint32_t display_height = swaps_geometry ? hello.coded_width : hello.coded_height;
    if (hello.codec != contract::kCodecH264 ||
        long_edge(hello.coded_width, hello.coded_height) > config.maximum_long_edge ||
        short_edge(hello.coded_width, hello.coded_height) > config.maximum_short_edge ||
        long_edge(display_width, display_height) > config.maximum_long_edge ||
        short_edge(display_width, display_height) > config.maximum_short_edge) {
        error = "only bounded H.264 sessions are accepted";
        return false;
    }
    std::lock_guard<std::mutex> lifecycle_lock(session_lifecycle_mutex_);
    end_session_locked();

    DecoderMode requested_mode = parse_decoder_mode(config.decoder_mode);
    if (!is_known_decoder_mode(config.decoder_mode)) {
        report("decoder_mode_unknown_selecting_automatic:" + config.decoder_mode);
        requested_mode = DecoderMode::Automatic;
    }
    if (!decoder_) {
        error = "decoder is not available";
        return false;
    }

    DecoderConfig decoder_config;
    decoder_config.width = hello.coded_width;
    decoder_config.height = hello.coded_height;
    decoder_config.rotation_degrees = hello.rotation_degrees;
    decoder_config.fps = hello.fps;
    decoder_config.maximum_queue_age_ms = config.maximum_decoder_queue_age_ms;
    decoder_config.maximum_live_frame_age_ms = config.maximum_live_frame_age_ms;
    decoder_config.drm_device = config.drm_device;

    std::optional<NativeSetupResult> native_setup;
    bool native_resources_prepared = false;
    auto discard_native_resources = [&] {
        if (!native_resources_prepared) {
            return;
        }
        obs_enter_graphics();
        renderer_.discard_prepared_native_session();
        obs_leave_graphics();
        native_resources_prepared = false;
    };

    if (requested_mode == DecoderMode::Software) {
        if (!decoder_->prepare_software_session(hello.generation, decoder_config, error)) {
            decoder_->discard_prepared_session();
            error = "software_decoder_setup_failed:" + error;
            return false;
        }
    } else {
        obs_enter_graphics();
        native_resources_prepared = true;
        const NativeSetupResult importer_setup =
            renderer_.prepare_native_session(hello.coded_width, hello.coded_height);
        obs_leave_graphics();
        if (importer_setup.status == NativeSetupStatus::Ready) {
            native_setup = decoder_->prepare_native_session(hello.generation, decoder_config);
        } else {
            native_setup = importer_setup;
        }

        const MediaPathDecision decision = decide_media_path(requested_mode, native_setup);
        if (!decision.accepted) {
            decoder_->discard_prepared_session();
            discard_native_resources();
            error = decision.error;
            return false;
        }
        if (decision.path == SessionMediaPath::Software) {
            decoder_->discard_prepared_session();
            discard_native_resources();
            if (!decoder_->prepare_software_session(hello.generation, decoder_config, error)) {
                decoder_->discard_prepared_session();
                error = "software_decoder_setup_failed:" + error;
                return false;
            }
        }
        if (!decision.event.empty()) {
            report(decision.event);
        }
    }

    const MediaPathDecision decision = requested_mode == DecoderMode::Software
                                           ? decide_media_path(requested_mode, std::nullopt)
                                           : decide_media_path(requested_mode, native_setup);
    if (!decision.accepted) {
        decoder_->discard_prepared_session();
        discard_native_resources();
        error = decision.error;
        return false;
    }
    if (!decoder_->prepared_session_ready()) {
        decoder_->discard_prepared_session();
        discard_native_resources();
        error = "decoder activation preparation is missing";
        return false;
    }
    if (!media_receiver_) {
        decoder_->discard_prepared_session();
        discard_native_resources();
        error = "GStreamer receiver is not available";
        return false;
    }
    GStreamerSessionConfig media_session;
    media_session.generation = hello.generation;
    media_session.sender_address = peer_address;
    media_session.sender_rtcp_port = hello.sender_rtcp_port;
    media_session.target_bitrate_bps = hello.target_bitrate_bps;
    if (!media_receiver_->start_session(media_session, error)) {
        decoder_->discard_prepared_session();
        discard_native_resources();
        error = "GStreamer receiver setup failed: " + error;
        return false;
    }
    transport_failure_pending_.store(false);
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        session_id_ = hello.session_id;
        peer_address_ = peer_address;
        stream_generation_ = hello.generation;
        active_width_ = hello.coded_width;
        active_height_ = hello.coded_height;
        active_display_width_ = display_width;
        active_display_height_ = display_height;
        active_rotation_degrees_ = hello.rotation_degrees;
        active_fps_ = hello.fps;
        active_bitrate_bps_ = hello.target_bitrate_bps;
        requested_decoder_mode_ = requested_mode;
        active_media_path_ = decision.path;
        media_path_locked_ = true;
        native_setup_status_ = native_setup ? native_setup->status : NativeSetupStatus::Failed;
        native_setup_reason_ = native_setup ? native_setup->reason : "not_attempted";
        native_setup_attempted_ = native_setup.has_value();
        session_active_ = true;
        stale_state_ = true;
    }
    media_path_failures_pending_.activate(hello.generation);
    mailbox_.clear();
    obs_enter_graphics();
    renderer_.activate_session_media_path(decision.path);
    obs_leave_graphics();
    decoder_->activate_prepared_session(decision.path);
    report("session_accepted:id=" + hello.session_id + ":generation=" + std::to_string(hello.generation) +
           ":profile=" + hello.profile_id +
           ":coded=" + std::to_string(hello.coded_width) + "x" + std::to_string(hello.coded_height) +
           ":display=" + std::to_string(display_width) + "x" +
           std::to_string(display_height) + ":rotation=" + std::to_string(hello.rotation_degrees) +
           "@" + std::to_string(hello.fps) + ":target_bitrate=" +
           std::to_string(hello.target_bitrate_bps) +
           ":requested=" + std::string(decoder_mode_name(requested_mode)) +
           ":path=" + std::string(session_media_path_name(decision.path)));
    return true;
}

std::string CamBridgeSource::on_probe(const std::string &request_id) const
{
    const SourceConfig config = configuration();
    return encode_capabilities_message(
        request_id,
        contract::kDefaultReceiverId,
        contract::kDefaultReceiverDisplayName,
        config.maximum_long_edge,
        config.maximum_short_edge);
}

void CamBridgeSource::on_control_message(const ControlMessage &message)
{
    std::lock_guard<std::mutex> lifecycle_lock(session_lifecycle_mutex_);
    bool should_stop = false;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        if (!session_active_ || message.session_id != session_id_ || message.generation != stream_generation_) {
            return;
        }
        should_stop = message.type == contract::kMessageStop;
    }
    if (should_stop) {
        end_session_locked();
    }
}

void CamBridgeSource::on_control_disconnect()
{
    std::lock_guard<std::mutex> lifecycle_lock(session_lifecycle_mutex_);
    end_session_locked();
    report("control_disconnected_session_invalidated");
}

void CamBridgeSource::on_access_unit(AccessUnit access_unit)
{
    if (decoder_) {
        decoder_->submit(std::move(access_unit));
    }
}

void CamBridgeSource::on_transport_error(const std::string &reason)
{
    transport_errors_.fetch_add(1);
    report("gstreamer_transport_error:" + reason);
    transport_failure_pending_.store(true);
}

void CamBridgeSource::on_decoder_frame(VideoFramePtr frame)
{
    if (!frame) {
        return;
    }
    SessionMediaPath active_path = SessionMediaPath::Unselected;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        if (!session_active_ || !media_path_locked_ || frame->stream_generation != stream_generation_) {
            return;
        }
        active_path = active_media_path_;
    }
    if (!frame_storage_matches_media_path(active_path, frame_storage_kind(frame->storage))) {
        post_media_path_failure({frame->stream_generation, MediaPathFailureCode::Decode,
                                 "decoder published storage for the wrong locked media path"});
        return;
    }
    if (frame->decode_time_ns >= frame->receive_time_ns) {
        observe_maximum(max_receive_to_decode_ns_, frame->decode_time_ns - frame->receive_time_ns);
    }
    if (frame->publish_time_ns >= frame->receive_time_ns) {
        observe_maximum(max_receive_to_publish_ns_, frame->publish_time_ns - frame->receive_time_ns);
    }
    if (!first_frame_reported_.exchange(true)) {
        report("first_frame_published:mode=" + std::string(session_media_path_name(active_path)) +
               ":profile=" + std::to_string(frame->width) + "x" + std::to_string(frame->height) +
               ":pixel_format=" + frame->pixel_format + ":color_space=" + frame->color_space +
               ":color_range=" + frame->color_range);
    }
    mailbox_.publish(std::move(frame));
}

void CamBridgeSource::on_decoder_event(const std::string &event)
{
    report(event);
}

void CamBridgeSource::post_media_path_failure(PendingMediaPathFailure failure)
{
    media_path_failures_pending_.post(std::move(failure));
}

void CamBridgeSource::drain_media_path_failure()
{
    std::optional<PendingMediaPathFailure> pending = media_path_failures_pending_.take();
    if (!pending) {
        return;
    }
    std::lock_guard<std::mutex> lifecycle_lock(session_lifecycle_mutex_);
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        if (!session_active_ || stream_generation_ != pending->stream_generation) {
            return;
        }
        ++media_path_failures_;
        if (pending->code == MediaPathFailureCode::NativeImport) {
            ++native_import_failures_;
        }
        if (pending->code == MediaPathFailureCode::NativeConversion &&
            pending->detail.rfind("native_pool_exhaustion:", 0) == 0) {
            ++native_pool_exhaustions_;
        }
        last_media_path_failure_code_ = pending->code;
        last_media_path_failure_detail_ = pending->detail;
    }
    report("media_path_failure:code=" +
           std::string(media_path_failure_code_name(pending->code)) + ":detail=" + pending->detail);
    if (control_server_) {
        const std::string reason = "media path failure: " + pending->detail;
        if (!control_server_->send_json_and_close(encode_error_message(reason))) {
            report("control_media_failure_delivery_failed");
        }
    }
    end_session_locked();
}

void CamBridgeSource::drain_transport_failure()
{
    if (!transport_failure_pending_.exchange(false)) {
        return;
    }
    std::lock_guard<std::mutex> lifecycle_lock(session_lifecycle_mutex_);
    end_session_locked();
}

void CamBridgeSource::end_session()
{
    std::lock_guard<std::mutex> lifecycle_lock(session_lifecycle_mutex_);
    end_session_locked();
}

void CamBridgeSource::end_session_locked()
{
    media_path_failures_pending_.deactivate();
    if (test_diagnostics_on_session_end()) {
        write_diagnostics();
    }
    if (media_receiver_) {
        media_receiver_->stop_session();
    }
    if (decoder_) {
        decoder_->end_session();
    }
    transport_failure_pending_.store(false);
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        session_active_ = false;
        media_path_locked_ = false;
        active_media_path_ = SessionMediaPath::Unselected;
        session_id_.clear();
        peer_address_.clear();
        stream_generation_ = kInactiveStreamGeneration;
        active_width_ = 0;
        active_height_ = 0;
        active_display_width_ = 0;
        active_display_height_ = 0;
        active_rotation_degrees_ = 0;
        active_fps_ = 0;
        active_bitrate_bps_ = 0;
        stale_state_ = false;
        first_frame_reported_.store(false);
        last_rendered_frame_generation_.store(0);
    }
    mailbox_.clear();
    renderer_.end_session();
}

void CamBridgeSource::report(const std::string &event) const
{
    blog(LOG_INFO, "[cambridge] %s", event.c_str());
}

SourceConfig source_config_from_settings(obs_data_t *settings)
{
    SourceConfig config;
    config.control_port = static_cast<std::uint16_t>(bounded_setting(
        settings, kPropertyControlPort, contract::kDefaultControlPort, contract::kMinimumPort, contract::kMaximumPort));
    config.media_rtp_port = static_cast<std::uint16_t>(bounded_setting(
        settings, kPropertyMediaRtpPort, contract::kDefaultMediaRtpPort, contract::kMinimumPort,
        contract::kMaximumPort));
    config.media_rtcp_port = static_cast<std::uint16_t>(bounded_setting(
        settings, kPropertyMediaRtcpPort, contract::kDefaultMediaRtcpPort, contract::kMinimumPort,
        contract::kMaximumPort));
    config.maximum_long_edge = bounded_setting(settings, kPropertyMaximumLongEdge, contract::kDefaultMaximumLongEdge,
                                               contract::kMinimumDimension, kMaximumLongEdge);
    config.maximum_short_edge = bounded_setting(settings, kPropertyMaximumShortEdge,
                                                contract::kDefaultMaximumShortEdge,
                                                contract::kMinimumDimension, kMaximumShortEdge);
    config.maximum_decoder_queue_age_ms = bounded_setting(settings, kPropertyQueueAge,
                                                          contract::kDefaultMaximumDecoderQueueAgeMs,
                                                          kMinimumQueueAgeMs, kMaximumQueueAgeMs);
    config.maximum_live_frame_age_ms = bounded_setting(settings, kPropertyLiveAge,
                                                       contract::kDefaultMaximumLiveFrameAgeMs,
                                                       kMinimumLiveAgeMs, kMaximumLiveAgeMs);
    read_platform_source_settings(settings, config);
    config.decoder_mode = setting_string(settings, kPropertyDecoderMode, receiver::kDefaultDecoderMode);
    config.diagnostics_path = setting_string(settings, kPropertyDiagnosticsPath, receiver::kDefaultDiagnosticsPath);
    config.transparent_placeholder = obs_data_get_bool(settings, kPropertyTransparentPlaceholder);
    return config;
}

void source_get_defaults(obs_data_t *settings)
{
    obs_data_set_default_int(settings, kPropertyControlPort, contract::kDefaultControlPort);
    obs_data_set_default_int(settings, kPropertyMediaRtpPort, contract::kDefaultMediaRtpPort);
    obs_data_set_default_int(settings, kPropertyMediaRtcpPort, contract::kDefaultMediaRtcpPort);
    obs_data_set_default_int(settings, kPropertyMaximumLongEdge, contract::kDefaultMaximumLongEdge);
    obs_data_set_default_int(settings, kPropertyMaximumShortEdge, contract::kDefaultMaximumShortEdge);
    obs_data_set_default_int(settings, kPropertyQueueAge, contract::kDefaultMaximumDecoderQueueAgeMs);
    obs_data_set_default_int(settings, kPropertyLiveAge, contract::kDefaultMaximumLiveFrameAgeMs);
    obs_data_set_default_string(settings, kPropertyDecoderMode, receiver::kDefaultDecoderMode);
    obs_data_set_default_string(settings, kPropertyDiagnosticsPath, receiver::kDefaultDiagnosticsPath);
    obs_data_set_default_bool(settings, kPropertyTransparentPlaceholder, false);
}

obs_properties_t *source_get_properties(void *data)
{
    auto *source = static_cast<CamBridgeSource *>(data);
    obs_properties_t *properties = obs_properties_create();
    obs_properties_add_text(properties, kPropertySetupInfo,
                             "Open the CamBridge app on your phone and start video. No OBS settings are required.",
                             OBS_TEXT_INFO);
    obs_properties_t *advanced_properties = obs_properties_create();
    obs_properties_add_int(advanced_properties, kPropertyControlPort, "Control port", contract::kMinimumPort,
                           contract::kMaximumPort, kPortStep);
    obs_properties_add_int(advanced_properties, kPropertyMediaRtpPort, "RTP media port", contract::kMinimumPort,
                           contract::kMaximumPort, kPortStep);
    obs_properties_add_int(advanced_properties, kPropertyMediaRtcpPort, "RTCP media port", contract::kMinimumPort,
                           contract::kMaximumPort, kPortStep);
    obs_properties_add_int(advanced_properties, kPropertyMaximumLongEdge, "Maximum long edge",
                           contract::kMinimumDimension, kMaximumLongEdge, kDimensionStep);
    obs_properties_add_int(advanced_properties, kPropertyMaximumShortEdge, "Maximum short edge",
                           contract::kMinimumDimension, kMaximumShortEdge, kDimensionStep);
    obs_properties_add_int(advanced_properties, kPropertyQueueAge, "Maximum decoder queue age (ms)", kMinimumQueueAgeMs,
                           kMaximumQueueAgeMs, kQueueAgeStep);
    obs_properties_add_int(advanced_properties, kPropertyLiveAge, "Maximum live frame age (ms)", kMinimumLiveAgeMs,
                           kMaximumLiveAgeMs, kLiveAgeStep);
    add_platform_source_properties(advanced_properties);
    obs_property_t *decoder_mode = obs_properties_add_list(advanced_properties, kPropertyDecoderMode, "Decoder mode",
                                                            OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(decoder_mode, "Automatic: native when supported, otherwise software at session start",
                                 receiver::kDefaultDecoderMode);
    obs_property_list_add_string(decoder_mode, "Require native hardware: fail the session if unavailable",
                                 decoder_mode_name(DecoderMode::NativeRequired).data());
    obs_property_list_add_string(decoder_mode, "Software only", decoder_mode_name(DecoderMode::Software).data());
    obs_properties_add_bool(advanced_properties, kPropertyTransparentPlaceholder, "Transparent placeholder");
    obs_properties_add_path(advanced_properties, kPropertyDiagnosticsPath, "Diagnostics JSON path", OBS_PATH_FILE,
                             "JSON (*.json)", receiver::kDefaultDiagnosticsPath);
    obs_properties_add_button2(advanced_properties, kPropertyDumpDiagnostics, "Write diagnostics now", dump_button_clicked, source);
    obs_properties_add_group(properties, kPropertyAdvancedSettings, "Advanced settings", OBS_GROUP_NORMAL,
                             advanced_properties);
    return properties;
}

void *source_create(obs_data_t *settings, obs_source_t *)
{
    auto instance = std::make_unique<CamBridgeSource>(source_config_from_settings(settings));
    std::string error;
    if (!instance->start(error)) {
        blog(LOG_ERROR, "[cambridge] source failed to start: %s", error.c_str());
        return nullptr;
    }
    return instance.release();
}

void source_destroy(void *data)
{
    delete static_cast<CamBridgeSource *>(data);
}

std::uint32_t source_get_width(void *data)
{
    return static_cast<CamBridgeSource *>(data)->width();
}

std::uint32_t source_get_height(void *data)
{
    return static_cast<CamBridgeSource *>(data)->height();
}

void source_update(void *data, obs_data_t *settings)
{
    static_cast<CamBridgeSource *>(data)->update(settings);
}

void source_video_render(void *data, gs_effect_t *effect)
{
    static_cast<CamBridgeSource *>(data)->render(effect);
}

void source_video_tick(void *data, float seconds)
{
    static_cast<CamBridgeSource *>(data)->tick(seconds);
}

} // namespace cambridge
