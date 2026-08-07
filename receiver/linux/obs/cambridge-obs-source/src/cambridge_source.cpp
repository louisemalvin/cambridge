#include "cambridge_source.hpp"

#include "control_protocol.hpp"
#include "protocol_contract.hpp"

#include <obs/obs-data.h>
#include <obs/obs-properties.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <fstream>
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
constexpr std::uint32_t kMaximumLongEdge = contract::kDefaultMaximumLongEdge;
constexpr std::uint32_t kMaximumShortEdge = contract::kDefaultMaximumShortEdge;
constexpr std::uint32_t kMinimumDeadlineMs = 1;
constexpr std::uint32_t kMaximumDeadlineMs = 5000;
constexpr std::uint32_t kMinimumQueueAgeMs = 1;
constexpr std::uint32_t kMaximumQueueAgeMs = 2000;
constexpr std::uint32_t kMinimumLiveAgeMs = 33;
constexpr std::uint32_t kMaximumLiveAgeMs = 5000;
constexpr std::size_t kMinimumSocketBufferBytes = 64 * 1024;
constexpr std::size_t kMaximumSocketBufferBytes = 32 * 1024 * 1024;
constexpr std::size_t kSocketBufferStepBytes = 4096;
constexpr std::uint64_t kNanosecondsPerMillisecond = 1'000'000ULL;
constexpr std::uint64_t kNanosecondsPerSecond = 1'000'000'000ULL;
constexpr std::uint32_t kInvalidPacketLogInterval = 32;
constexpr std::uint32_t kPortStep = 1;
constexpr std::uint32_t kDeadlineStep = 1;
constexpr std::uint32_t kQueueAgeStep = 1;
constexpr std::uint32_t kLiveAgeStep = 1;
constexpr std::uint32_t kMailboxCapacity = contract::kMailboxCapacity;
constexpr std::uint32_t kModuleProtocolVersion = contract::kProtocolVersion;
constexpr char kModuleVersion[] = CAMBRIDGE_VERSION;
constexpr char kPropertyControlPort[] = "control_port";
constexpr char kPropertyMediaPort[] = "media_port";
constexpr char kPropertyMaximumLongEdge[] = "maximum_long_edge";
constexpr char kPropertyMaximumShortEdge[] = "maximum_short_edge";
constexpr char kPropertyReorderDeadline[] = "reorder_deadline_ms";
constexpr char kPropertyQueueAge[] = "maximum_decoder_queue_age_ms";
constexpr char kPropertyLiveAge[] = "maximum_live_frame_age_ms";
constexpr char kPropertySocketBuffer[] = "receive_buffer_bytes";
constexpr char kPropertyDrmDevice[] = "drm_device";
constexpr char kPropertyDecoderMode[] = "decoder_mode";
constexpr char kPropertyTransparentPlaceholder[] = "transparent_placeholder";
constexpr char kPropertyDiagnosticsPath[] = "diagnostics_path";
constexpr char kPropertyDumpDiagnostics[] = "dump_diagnostics";
constexpr char kPropertySetupInfo[] = "setup_info";
constexpr char kPropertyAdvancedSettings[] = "advanced_settings";

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

} // namespace

CamBridgeSource::CamBridgeSource(SourceConfig config, obs_source_t *source)
    : config_(std::move(config)), source_(source),
      renderer_(RendererConfig{config_.transparent_placeholder}, [this](const std::string &event) {
          report(event);
      }, [this] { on_renderer_hardware_fallback(); })
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
    if (config.control_port < contract::kMinimumPort || config.media_port < contract::kMinimumPort ||
        config.control_port > contract::kMaximumPort || config.media_port > contract::kMaximumPort ||
        config.control_port == config.media_port) {
        error = "control and media ports must be distinct non-zero ports";
        return false;
    }
    decoder_ = std::make_unique<Decoder>(
        [this](VideoFramePtr frame) { on_decoder_frame(std::move(frame)); },
        [this](const std::string &event) { on_decoder_event(event); });
    decoder_->start();

    MediaReceiverConfig media_config;
    media_config.media_port = config.media_port;
    media_config.receive_buffer_bytes = config.receive_buffer_bytes;
    media_config.reorder_deadline_ms = config.reorder_deadline_ms;
    media_config.maximum_datagram_bytes = contract::kMaximumRtpDatagramBytes;
    media_config.maximum_access_unit_bytes = contract::kMaximumAccessUnitBytes;
    media_config.payload_type = static_cast<std::uint8_t>(contract::kRtpPayloadType);
    media_receiver_ = std::make_unique<MediaReceiver>(
        media_config,
        [this](AccessUnit access_unit) { on_access_unit(std::move(access_unit)); },
        [this](std::size_t lost) { on_packet_loss(lost); },
        [this](const std::string &reason) { on_invalid_packet(reason); });
    if (!media_receiver_->start(error)) {
        decoder_->stop();
        decoder_.reset();
        media_receiver_.reset();
        return false;
    }

    control_server_ = std::make_unique<ControlServer>(
        config.control_port, config.media_port, config.maximum_long_edge, config.maximum_short_edge,
        [this](const HelloMessage &hello, const std::string &peer, std::string &reason) {
            return on_hello(hello, peer, reason);
        },
        [this](const std::string &request_id) { return on_probe(request_id); },
        [this](const ControlMessage &message) { on_control_message(message); },
        [this] { on_control_disconnect(); });
    if (!control_server_->start(error)) {
        media_receiver_->stop();
        media_receiver_.reset();
        decoder_->stop();
        decoder_.reset();
        control_server_.reset();
        return false;
    }
    discovery_advertiser_ = std::make_unique<DiscoveryAdvertiser>(config.control_port);
    std::string discovery_error;
    if (!discovery_advertiser_->start(discovery_error)) {
        report("discovery_unavailable:" + discovery_error);
        discovery_advertiser_.reset();
    } else {
        report("discovery:service_type=" + std::string(contract::kDiscoveryServiceType));
    }
    started_ = true;
    report("identity:module=cambridge-obs-plugin version=" + std::string(kModuleVersion) +
           " commit=" + std::string(CAMBRIDGE_GIT_COMMIT) + " build=plugin protocol=" +
           std::to_string(kModuleProtocolVersion) + " config=obs-properties");
    report("listening:control=" + std::to_string(config.control_port) +
           ":media=" + std::to_string(config.media_port) + ":drm=" + config.drm_device);
    report("bounds:rtp_datagram=" + std::to_string(contract::kMaximumRtpDatagramBytes) +
           " mtu=" + std::to_string(contract::kRtpMtuBytes) +
           " au_bytes=" + std::to_string(contract::kMaximumAccessUnitBytes) +
           " au_count=" + std::to_string(contract::kMaximumInFlightAccessUnits) +
           " mailbox=" + std::to_string(kMailboxCapacity) +
           " texture_slots=" + std::to_string(contract::kTexturePoolSlots));
    return true;
}

void CamBridgeSource::stop()
{
    if (discovery_advertiser_) {
        discovery_advertiser_->stop();
        discovery_advertiser_.reset();
    }
    if (control_server_) {
        control_server_->stop();
        control_server_.reset();
    }
    if (media_receiver_) {
        media_receiver_->stop();
        media_receiver_.reset();
    }
    if (decoder_) {
        decoder_->end_session();
        decoder_->stop();
        decoder_.reset();
    }
    end_session();
    started_ = false;
}

void CamBridgeSource::update(obs_data_t *settings)
{
    const SourceConfig next = source_config_from_settings(settings);
    bool network_changed = false;
    {
        std::lock_guard<std::mutex> lock(configuration_mutex_);
        network_changed = next.control_port != config_.control_port || next.media_port != config_.media_port;
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
    // Media loss is handled by dropping late or incomplete access units.
}

void CamBridgeSource::write_diagnostics()
{
    const SourceConfig config = configuration();
    bool session_active = false;
    std::uint32_t coded_width = 0;
    std::uint32_t coded_height = 0;
    std::uint32_t display_width = 0;
    std::uint32_t display_height = 0;
    std::uint32_t rotation_degrees = 0;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        session_active = session_active_;
        coded_width = active_width_;
        coded_height = active_height_;
        display_width = active_display_width_;
        display_height = active_display_height_;
        rotation_degrees = active_rotation_degrees_;
    }
    std::ofstream output(config.diagnostics_path, std::ios::trunc);
    if (!output) {
        report("diagnostics_write_failed:path=" + config.diagnostics_path);
        return;
    }
    output << "{\n"
           << "  \"module\": \"cambridge-obs-plugin\",\n"
           << "  \"version\": \"" << kModuleVersion << "\",\n"
           << "  \"gitCommit\": \"" << CAMBRIDGE_GIT_COMMIT << "\",\n"
           << "  \"protocolVersion\": " << kModuleProtocolVersion << ",\n"
           << "  \"state\": \"" << (session_active ? "presenting" : "listening") << "\",\n"
           << "  \"codedWidth\": " << coded_width << ",\n"
           << "  \"codedHeight\": " << coded_height << ",\n"
           << "  \"displayWidth\": " << display_width << ",\n"
           << "  \"displayHeight\": " << display_height << ",\n"
           << "  \"rotationDegrees\": " << rotation_degrees << ",\n"
           << "  \"decoder\": \"" << (decoder_ ? decoder_->decoder_name() : "uninitialized") << "\",\n"
           << "  \"render\": \"" << renderer_.render_mode() << "\",\n"
           << "  \"mailboxOccupancy\": " << mailbox_.occupancy() << ",\n"
           << "  \"mailboxMaximum\": " << kMailboxCapacity << ",\n"
           << "  \"framesReplaced\": " << mailbox_.replaced_count() << ",\n"
           << "  \"framesStale\": " << stale_transitions_.load() << ",\n"
           << "  \"framesDecoded\": " << (decoder_ ? decoder_->frames_decoded() : 0) << ",\n"
           << "  \"framesRendered\": " << frames_rendered_.load() << ",\n"
           << "  \"cpuFrameCopies\": " << renderer_.cpu_uploads() << ",\n"
           << "  \"gpuCopies\": " << renderer_.gpu_copies() << ",\n"
           << "  \"hardwareCpuTransfers\": " << (decoder_ ? decoder_->hardware_cpu_transfers() : 0)
           << ",\n"
           << "  \"dmaBufImportFailures\": " << renderer_.import_failures() << ",\n"
           << "  \"packetsReceived\": " << (media_receiver_ ? media_receiver_->packets_received() : 0) << ",\n"
           << "  \"bytesReceived\": " << (media_receiver_ ? media_receiver_->bytes_received() : 0) << ",\n"
           << "  \"packetsLost\": " << (media_receiver_ ? media_receiver_->packets_lost() : 0) << ",\n"
           << "  \"malformedPackets\": " << (media_receiver_ ? media_receiver_->malformed_packets() : 0) << ",\n"
           << "  \"invalidSourcePackets\": "
           << (media_receiver_ ? media_receiver_->invalid_source_packets() : 0) << ",\n"
           << "  \"decodeFailures\": " << (decoder_ ? decoder_->decode_failures() : 0) << ",\n"
           << "  \"decoderQueueDrops\": " << (decoder_ ? decoder_->queue_drops() : 0) << ",\n"
           << "  \"decoderQueueOccupancy\": " << (decoder_ ? decoder_->queue_occupancy() : 0) << ",\n"
           << "  \"reorderOccupancy\": " << (media_receiver_ ? media_receiver_->reorder_occupancy() : 0)
           << ",\n"
           << "  \"reorderPeak\": " << (media_receiver_ ? media_receiver_->reorder_peak() : 0) << ",\n"
           << "  \"reorderDeadlineDrops\": "
           << (media_receiver_ ? media_receiver_->reorder_deadline_drops() : 0) << ",\n"
           << "  \"maxReceiveToDecodeMs\": " << milliseconds(max_receive_to_decode_ns_.load()) << ",\n"
           << "  \"maxReceiveToPublishMs\": " << milliseconds(max_receive_to_publish_ns_.load()) << ",\n"
           << "  \"maxReceiveToRenderMs\": " << milliseconds(max_receive_to_render_ns_.load()) << ",\n"
           << "  \"configured\": {\"controlPort\": " << config.control_port
           << ", \"mediaPort\": " << config.media_port
           << ", \"reorderDeadlineMs\": " << config.reorder_deadline_ms
           << ", \"maximumDecoderQueueAgeMs\": " << config.maximum_decoder_queue_age_ms
           << ", \"maximumLiveFrameAgeMs\": " << config.maximum_live_frame_age_ms << "}\n"
           << "}\n";
    report("diagnostics_written:path=" + config.diagnostics_path);
}

std::uint32_t CamBridgeSource::width() const
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    return active_display_width_ == 0 ? contract::kDefaultCodedWidth : active_display_width_;
}

std::uint32_t CamBridgeSource::height() const
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    return active_display_height_ == 0 ? contract::kDefaultCodedHeight : active_display_height_;
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
    const bool swaps_geometry = hello.rotation_degrees == 90 || hello.rotation_degrees == 270;
    const std::uint32_t display_width = swaps_geometry ? hello.coded_height : hello.coded_width;
    const std::uint32_t display_height = swaps_geometry ? hello.coded_width : hello.coded_height;
    const contract::ProfileContract *profile = contract::find_profile(hello.profile_id);
    if (profile == nullptr || hello.codec != contract::kCodecH264 ||
        hello.coded_width != profile->width || hello.coded_height != profile->height ||
        hello.fps != profile->fps || hello.bitrate_bps != profile->bitrate_bps ||
        long_edge(hello.coded_width, hello.coded_height) > config.maximum_long_edge ||
        short_edge(hello.coded_width, hello.coded_height) > config.maximum_short_edge ||
        long_edge(display_width, display_height) > config.maximum_long_edge ||
        short_edge(display_width, display_height) > config.maximum_short_edge) {
        error = "only bounded H.264 sessions are accepted";
        return false;
    }
    end_session();
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
        active_bitrate_bps_ = hello.bitrate_bps;
        session_active_ = true;
        stale_state_ = true;
    }
    mailbox_.clear();
    if (media_receiver_) {
        media_receiver_->begin_session(hello.generation, peer_address);
    }
    if (decoder_) {
        DecoderConfig decoder_config;
        decoder_config.width = hello.coded_width;
        decoder_config.height = hello.coded_height;
        decoder_config.rotation_degrees = hello.rotation_degrees;
        decoder_config.fps = hello.fps;
        decoder_config.maximum_queue_age_ms = config.maximum_decoder_queue_age_ms;
        decoder_config.maximum_live_frame_age_ms = config.maximum_live_frame_age_ms;
        decoder_config.drm_device = config.drm_device;
        decoder_config.force_cpu = config.decoder_mode == "cpu";
        decoder_->begin_session(hello.generation, std::move(decoder_config));
    }
    report("session_accepted:id=" + hello.session_id + ":generation=" + std::to_string(hello.generation) +
           ":profile=" + hello.profile_id +
           ":coded=" + std::to_string(hello.coded_width) + "x" + std::to_string(hello.coded_height) +
           ":display=" + std::to_string(display_width) + "x" +
           std::to_string(display_height) + ":rotation=" + std::to_string(hello.rotation_degrees) +
           "@" + std::to_string(hello.fps) + ":bitrate=" + std::to_string(hello.bitrate_bps));
    return true;
}

std::string CamBridgeSource::on_probe(const std::string &request_id) const
{
    const SourceConfig config = configuration();
    std::vector<std::string> profile_ids;
    profile_ids.reserve(contract::kProfiles.size());
    for (const contract::ProfileContract &profile : contract::kProfiles) {
        profile_ids.emplace_back(profile.id);
    }
    return encode_capabilities_message(
        request_id,
        contract::kDefaultReceiverId,
        contract::kDefaultReceiverDisplayName,
        profile_ids,
        config.maximum_long_edge,
        config.maximum_short_edge);
}

void CamBridgeSource::on_control_message(const ControlMessage &message)
{
    bool should_stop = false;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        if (!session_active_ || message.session_id != session_id_ || message.generation != stream_generation_) {
            return;
        }
        should_stop = message.type == contract::kMessageStop;
    }
    if (should_stop) {
        end_session();
    }
}

void CamBridgeSource::on_control_disconnect()
{
    end_session();
    report("control_disconnected_session_invalidated");
}

void CamBridgeSource::on_access_unit(AccessUnit access_unit)
{
    if (decoder_) {
        decoder_->submit(std::move(access_unit));
    }
}

void CamBridgeSource::on_packet_loss(std::size_t lost)
{
    packet_loss_events_.fetch_add(1);
    report("rtp_loss:packets=" + std::to_string(lost));
}

void CamBridgeSource::on_invalid_packet(const std::string &reason)
{
    malformed_events_.fetch_add(1);
    if (malformed_events_.load() % kInvalidPacketLogInterval == 1) {
        report("rtp_invalid:" + reason);
    }
}

void CamBridgeSource::on_decoder_frame(VideoFramePtr frame)
{
    if (!frame) {
        return;
    }
    if (frame->decode_time_ns >= frame->receive_time_ns) {
        observe_maximum(max_receive_to_decode_ns_, frame->decode_time_ns - frame->receive_time_ns);
    }
    if (frame->publish_time_ns >= frame->receive_time_ns) {
        observe_maximum(max_receive_to_publish_ns_, frame->publish_time_ns - frame->receive_time_ns);
    }
    if (!first_frame_reported_.exchange(true)) {
        report("first_frame_published:mode=" + std::to_string(static_cast<int>(frame->render_mode)) +
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

void CamBridgeSource::on_renderer_hardware_fallback()
{
    mailbox_.clear();
    if (decoder_) {
        decoder_->request_cpu_fallback();
    }
}

void CamBridgeSource::end_session()
{
    std::lock_guard<std::mutex> lock(session_mutex_);
    session_active_ = false;
    session_id_.clear();
    peer_address_.clear();
    stream_generation_ = 0;
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
    mailbox_.clear();
    if (media_receiver_) {
        media_receiver_->end_session();
    }
    if (decoder_) {
        decoder_->end_session();
    }
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
    config.media_port = static_cast<std::uint16_t>(bounded_setting(
        settings, kPropertyMediaPort, contract::kDefaultMediaPort, contract::kMinimumPort, contract::kMaximumPort));
    config.maximum_long_edge = bounded_setting(settings, kPropertyMaximumLongEdge, contract::kDefaultMaximumLongEdge,
                                               contract::kMinimumDimension, kMaximumLongEdge);
    config.maximum_short_edge = bounded_setting(settings, kPropertyMaximumShortEdge,
                                                contract::kDefaultMaximumShortEdge,
                                                contract::kMinimumDimension, kMaximumShortEdge);
    config.reorder_deadline_ms = bounded_setting(settings, kPropertyReorderDeadline,
                                                 contract::kDefaultReorderDeadlineMs, kMinimumDeadlineMs,
                                                 kMaximumDeadlineMs);
    config.maximum_decoder_queue_age_ms = bounded_setting(settings, kPropertyQueueAge,
                                                          contract::kDefaultMaximumDecoderQueueAgeMs,
                                                          kMinimumQueueAgeMs, kMaximumQueueAgeMs);
    config.maximum_live_frame_age_ms = bounded_setting(settings, kPropertyLiveAge,
                                                       contract::kDefaultMaximumLiveFrameAgeMs,
                                                       kMinimumLiveAgeMs, kMaximumLiveAgeMs);
    config.receive_buffer_bytes = static_cast<std::size_t>(bounded_setting(
        settings, kPropertySocketBuffer, contract::kDefaultReceiveBufferBytes, kMinimumSocketBufferBytes,
        kMaximumSocketBufferBytes));
    config.drm_device = setting_string(settings, kPropertyDrmDevice, contract::kDefaultDrmDevice);
    config.decoder_mode = setting_string(settings, kPropertyDecoderMode, contract::kDefaultDecoderMode);
    config.diagnostics_path = setting_string(settings, kPropertyDiagnosticsPath, contract::kDefaultDiagnosticsPath);
    config.transparent_placeholder = obs_data_get_bool(settings, kPropertyTransparentPlaceholder);
    return config;
}

void source_get_defaults(obs_data_t *settings)
{
    obs_data_set_default_int(settings, kPropertyControlPort, contract::kDefaultControlPort);
    obs_data_set_default_int(settings, kPropertyMediaPort, contract::kDefaultMediaPort);
    obs_data_set_default_int(settings, kPropertyMaximumLongEdge, contract::kDefaultMaximumLongEdge);
    obs_data_set_default_int(settings, kPropertyMaximumShortEdge, contract::kDefaultMaximumShortEdge);
    obs_data_set_default_int(settings, kPropertyReorderDeadline, contract::kDefaultReorderDeadlineMs);
    obs_data_set_default_int(settings, kPropertyQueueAge, contract::kDefaultMaximumDecoderQueueAgeMs);
    obs_data_set_default_int(settings, kPropertyLiveAge, contract::kDefaultMaximumLiveFrameAgeMs);
    obs_data_set_default_int(settings, kPropertySocketBuffer, contract::kDefaultReceiveBufferBytes);
    obs_data_set_default_string(settings, kPropertyDrmDevice, contract::kDefaultDrmDevice);
    obs_data_set_default_string(settings, kPropertyDecoderMode, contract::kDefaultDecoderMode);
    obs_data_set_default_string(settings, kPropertyDiagnosticsPath, contract::kDefaultDiagnosticsPath);
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
    obs_properties_add_int(advanced_properties, kPropertyMediaPort, "RTP media port", contract::kMinimumPort,
                           contract::kMaximumPort, kPortStep);
    obs_properties_add_int(advanced_properties, kPropertyMaximumLongEdge, "Maximum long edge",
                           contract::kMinimumDimension, kMaximumLongEdge, kDimensionStep);
    obs_properties_add_int(advanced_properties, kPropertyMaximumShortEdge, "Maximum short edge",
                           contract::kMinimumDimension, kMaximumShortEdge, kDimensionStep);
    obs_properties_add_int(advanced_properties, kPropertyReorderDeadline, "RTP reorder deadline (ms)", kMinimumDeadlineMs,
                           kMaximumDeadlineMs, kDeadlineStep);
    obs_properties_add_int(advanced_properties, kPropertyQueueAge, "Maximum decoder queue age (ms)", kMinimumQueueAgeMs,
                           kMaximumQueueAgeMs, kQueueAgeStep);
    obs_properties_add_int(advanced_properties, kPropertyLiveAge, "Maximum live frame age (ms)", kMinimumLiveAgeMs,
                           kMaximumLiveAgeMs, kLiveAgeStep);
    obs_properties_add_int(advanced_properties, kPropertySocketBuffer, "UDP receive buffer (bytes)", kMinimumSocketBufferBytes,
                           kMaximumSocketBufferBytes, kSocketBufferStepBytes);
    obs_properties_add_path(advanced_properties, kPropertyDrmDevice, "DRM render device", OBS_PATH_FILE, "DRM device (*)",
                             contract::kDefaultDrmDevice);
    obs_property_t *decoder_mode = obs_properties_add_list(advanced_properties, kPropertyDecoderMode, "Decoder mode",
                                                            OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(decoder_mode, "Automatic VA-API then CPU", contract::kDefaultDecoderMode);
    obs_property_list_add_string(decoder_mode, "CPU fallback", "cpu");
    obs_properties_add_bool(advanced_properties, kPropertyTransparentPlaceholder, "Transparent placeholder");
    obs_properties_add_path(advanced_properties, kPropertyDiagnosticsPath, "Diagnostics JSON path", OBS_PATH_FILE,
                             "JSON (*.json)", contract::kDefaultDiagnosticsPath);
    obs_properties_add_button2(advanced_properties, kPropertyDumpDiagnostics, "Write diagnostics now", dump_button_clicked, source);
    obs_properties_add_group(properties, kPropertyAdvancedSettings, "Advanced settings", OBS_GROUP_NORMAL,
                             advanced_properties);
    return properties;
}

void *source_create(obs_data_t *settings, obs_source_t *source)
{
    auto instance = std::make_unique<CamBridgeSource>(source_config_from_settings(settings), source);
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
