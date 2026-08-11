# Linux and macOS OBS Receiver Implementation Contract

Internal status: approved implementation handoff.

This document is deliberately narrow. It defines the complete sequence for
turning the current Linux-shaped CamBridge OBS receiver into one shared Linux
and macOS receiver. It does not authorize a Windows implementation.

The implementation agent must execute the stages in order. It must not invent
fallbacks, optional pipelines, compatibility layers, or alternate transports.

## 1. Outcome

The finished repository has one OBS receiver source tree. Linux and macOS
compile the same control, RTP, H.264 orchestration, session, mailbox, rendering
policy, settings, and diagnostics code. Only these three runtime responsibilities
have platform implementations:

1. Native FFmpeg decoder setup and native-frame export.
2. Native-frame import into an OBS texture.
3. Local-network service advertisement.

Linux uses:

    FFmpeg H.264
      -> VAAPI decoded AVFrame
      -> DRM PRIME descriptor
      -> DMA-BUF import
      -> OBS texture

macOS uses:

    FFmpeg H.264
      -> VideoToolbox decoded AVFrame
      -> retained CVPixelBuffer
      -> Metal NV12-to-BGRA conversion into a bounded IOSurface pool
      -> OBS IOSurface texture

Every platform also supports a separate software path:

    FFmpeg software H.264
      -> CPU NV12
      -> bounded OBS texture upload

The native and software paths never mix within an active session.

## 2. Scope

Required:

- Preserve the existing Linux source IDs and saved scene settings.
- Preserve protocol version 6, TCP control framing, and RFC 6184 RTP/H.264.
- Preserve the single active-session rule.
- Preserve the newest-frame mailbox and bounded decoder queue.
- Preserve Linux VAAPI, DRM PRIME, and DMA-BUF operation.
- Add macOS 12 or later support.
- Produce one macOS universal arm64 and x86_64 plugin bundle.
- Add explicit automatic, native-required, and software decoder modes.
- Make native-path failures visible and testable.
- Keep discovery failure visible while retaining manual addressing.

Not required and not authorized:

- Windows source files, Windows presets, Winsock, D3D11, or Windows DNS-SD.
- A generic socket runtime.
- A generic media context.
- A plugin helper application.
- Swift, Rust, Objective-C application code, or a virtual camera.
- Audio, MPEG-TS, SRT, WebRTC, retransmission, authentication, encryption, or
  reconnect behavior.
- Per-frame hardware-to-software transfer.
- Rebuilding the decoder after a decode or render failure.
- Trying several native import formats until one appears to work.

Windows is a later project. It may reuse the three interfaces proven here.
Windows-specific networking must be designed when Windows work begins, using
the concrete Linux and macOS implementation as evidence.

## 3. Non-negotiable pipeline

The data path is:

    sender
      -> UDP socket
      -> RTP validation and reordering
      -> RFC 6184 H.264 depacketization
      -> complete encoded access unit
      -> bounded decoder queue
      -> exactly one decoder path for the session
      -> VideoFrame
      -> capacity-one latest-frame mailbox
      -> exactly one renderer path for the session
      -> OBS output

The control path is:

    sender
      -> length-prefixed JSON over TCP
      -> shared protocol validation
      -> shared single-session controller
      -> select and start one media path
      -> start RTP acceptance for that generation

The discovery path is:

    shared discovery metadata
      -> Linux Avahi implementation
         or
      -> macOS Bonjour implementation
      -> sender discovers the same TCP control endpoint

Operating-system selection is compile-time CMake source selection. Media-path
selection is once per accepted session. Neither selection is repeated per
frame.

## 4. Media-path state machine

Add these internal values:

    enum class DecoderMode {
        Automatic,
        NativeRequired,
        Software,
    };

    enum class SessionMediaPath {
        Unselected,
        Native,
        Software,
        Failed,
    };

    enum class NativeSetupStatus {
        Ready,
        Unsupported,
        Failed,
    };

NativeSetupStatus has strict meaning:

- Ready means all decoder and importer resources required before decoding are
  initialized.
- Unsupported means the host genuinely lacks a required device or OBS
  capability. It is not used for allocation failures, malformed resources,
  unexpected API failures, or implementation bugs.
- Failed means a required API was expected to work but returned an error.

Selection behavior is fixed:

| Requested mode | Native setup result | Selected path |
| --- | --- | --- |
| Automatic | Ready | Native |
| Automatic | Unsupported | Software |
| Automatic | Failed | Failed; reject START |
| NativeRequired | Ready | Native |
| NativeRequired | Unsupported | Failed; reject START |
| NativeRequired | Failed | Failed; reject START |
| Software | Native setup is not attempted | Software |

The selected path is locked before MediaReceiver begins accepting packets for
the new generation. Once locked, it does not change until STOP, control
disconnect, source destruction, or session failure.

After the path is locked:

- A native decode, export, conversion, or import error fails the active
  session.
- A software decode or upload error fails the active session.
- The receiver clears the mailbox and presents the configured placeholder.
- The failure increments a diagnostic counter and records one stable error
  code plus detail.
- The receiver does not open another decoder.
- The receiver does not transfer the failing native AVFrame to system memory.
- The receiver does not resubmit the access unit to another path.
- The receiver does not keep attempting the failing operation every frame.
- The wire protocol is not extended to disguise or report the local failure.

Automatic mode is capability selection, not error recovery. Its only
native-to-software decision happens before RTP processing and only for
NativeSetupStatus::Unsupported. Log it exactly once as:

    native_unsupported_selecting_software:<reason>

NativeRequired exists for CI and physical acceptance. A native test must set
this mode. A test that accidentally renders through software must fail.

## 5. Saved-setting compatibility

Keep the existing OBS property key:

    decoder_mode

Keep the existing stored values:

- auto maps to DecoderMode::Automatic.
- cpu maps to DecoderMode::Software.

Add one stored value:

- native_required maps to DecoderMode::NativeRequired.

Do not introduce software as a second stored spelling for cpu. Unknown values
produce one warning and resolve to Automatic.

The UI labels are:

- Automatic: native when supported, otherwise software at session start.
- Require native hardware: fail the session if unavailable.
- Software only.

Keep the Linux property key drm_device unchanged. Do not show that property on
macOS. A tiny platform source-property function is allowed for this UI leaf; it
is not a media runtime abstraction.

Keep these OBS source IDs exactly:

- cambridge_android_source
- direct_android_rtp_webcam

The second ID remains deprecated.

## 6. Source-of-truth rules

The authority order is:

1. protocol/cambridge-stream-contract.json for protocol and transport values.
2. protocol/cambridge-stream.schema.json for valid JSON shapes.
3. docs/protocol.md for lifecycle explanation.
4. Existing control and RTP tests for compatibility cases.
5. A recorded Linux baseline for receiver behavior.
6. This document for platform boundaries and media-path failure policy.

Create:

- scripts/development/generate-cambridge-cpp-contract.py
- receiver/obs/cambridge-obs-source/src/protocol_contract.generated.hpp

The generator reads the JSON contract and writes every C++ protocol constant
represented by that JSON. The generated file is marked generated and is never
hand-edited. The repository contract checker must fail if it is stale.

Receiver-only values remain narrowly named where they are owned. Do not add UI
labels, DRM device paths, Metal formats, or OBS texture policy to the wire
contract.

No platform source may copy a port, timeout, dimension, queue limit, protocol
version, service type, or TXT value.

## 7. Final source tree

The final receiver layout is:

    receiver/obs/cambridge-obs-source/
    ├── CMakeLists.txt
    ├── CMakePresets.json
    ├── buildspec.json
    ├── cmake/
    │   ├── CompilerWarnings.cmake
    │   ├── Dependencies.cmake
    │   ├── Platform.cmake
    │   └── macos/
    │       └── Info.plist.in
    ├── data/
    │   └── macos/
    │       └── nv12_to_bgra.metal
    ├── src/
    │   ├── cambridge_source.cpp
    │   ├── cambridge_source.hpp
    │   ├── control_protocol.cpp
    │   ├── control_protocol.hpp
    │   ├── control_server.cpp
    │   ├── control_server.hpp
    │   ├── decoder.cpp
    │   ├── decoder.hpp
    │   ├── diagnostics.cpp
    │   ├── diagnostics.hpp
    │   ├── discovery_metadata.cpp
    │   ├── discovery_metadata.hpp
    │   ├── frame.hpp
    │   ├── latest_frame_mailbox.hpp
    │   ├── media_path.cpp
    │   ├── media_path.hpp
    │   ├── media_receiver.cpp
    │   ├── media_receiver.hpp
    │   ├── module.cpp
    │   ├── network_address_candidates.cpp
    │   ├── network_address_candidates.hpp
    │   ├── protocol_contract.generated.hpp
    │   ├── renderer.cpp
    │   ├── renderer.hpp
    │   ├── rtp.cpp
    │   ├── rtp.hpp
    │   └── platform/
    │       ├── interfaces/
    │       │   ├── discovery_advertiser.hpp
    │       │   ├── native_decoder_adapter.hpp
    │       │   ├── native_frame.hpp
    │       │   ├── native_frame_importer.hpp
    │       │   └── source_properties.hpp
    │       ├── posix/
    │       │   ├── posix_compat.cpp
    │       │   └── posix_compat.hpp
    │       ├── linux/
    │       │   ├── discovery_advertiser_linux.cpp
    │       │   ├── native_decoder_linux.cpp
    │       │   ├── native_frame_importer_linux.cpp
    │       │   ├── native_frame_linux.hpp
    │       │   └── source_properties_linux.cpp
    │       └── macos/
    │           ├── discovery_advertiser_macos.cpp
    │           ├── native_decoder_macos.mm
    │           ├── native_frame_importer_macos.mm
    │           ├── native_frame_macos.hpp
    │           └── source_properties_macos.cpp
    └── tests/
        ├── control_tests.cpp
        ├── decoder_policy_tests.cpp
        ├── discovery_metadata_tests.cpp
        ├── frame_lifetime_tests.cpp
        ├── latest_frame_mailbox_tests.cpp
        ├── linux_baseline.md
        ├── media_path_tests.cpp
        ├── network_address_candidates_tests.cpp
        ├── posix_compat_tests.cpp
        ├── renderer_policy_tests.cpp
        └── rtp_tests.cpp

The final script layout is:

    scripts/receiver/
    ├── common/
    │   ├── cambridge-fixture.py
    │   ├── cambridge-test-profile.ini
    │   └── cambridge-test-scene.json
    ├── linux/
    │   ├── build-cambridge-obs-plugin.sh
    │   ├── setup-cambridge-firewall.sh
    │   └── test-cambridge-fixture.sh
    └── macos/
        ├── build-cambridge-obs-plugin.sh
        └── test-cambridge-fixture.sh

    scripts/release/
    ├── package-linux-plugin.sh
    └── package-macos-plugin.sh

Do not retain receiver/linux/obs/cambridge-obs-source after relocation. Do not
create receiver/macos, receiver/windows, or a copied plugin tree.

## 8. Shared interfaces

The exact responsibility of each interface is fixed. Minor spelling changes
are permitted only to match an already-established local convention.

### 8.1 media_path.hpp

Define DecoderMode, SessionMediaPath, and NativeSetupStatus here.

Also define:

    struct NativeSetupResult {
        NativeSetupStatus status = NativeSetupStatus::Failed;
        std::string reason;
    };

    struct MediaPathDecision {
        SessionMediaPath path = SessionMediaPath::Failed;
        bool accepted = false;
        std::string event;
        std::string error;
    };

    DecoderMode parse_decoder_mode(std::string_view stored_value);

    MediaPathDecision decide_media_path(
        DecoderMode requested_mode,
        const std::optional<NativeSetupResult> &native_setup);

decide_media_path is pure and has exhaustive unit tests for the table in
section 4. Software mode requires std::nullopt. Automatic and NativeRequired
require one result representing the complete importer-then-decoder setup.
CamBridgeSource does not call native setup for Software mode and does not pass
an invented successful result.

### 8.2 native_frame.hpp

This header includes only the standard library. It contains no FFmpeg, VAAPI,
DRM, CoreVideo, IOSurface, or Metal header.

Define:

    class NativeFrame {
    public:
        virtual ~NativeFrame() = default;

        NativeFrame(const NativeFrame &) = delete;
        NativeFrame &operator=(const NativeFrame &) = delete;

    protected:
        NativeFrame() = default;
    };

    using NativeFramePtr = std::shared_ptr<const NativeFrame>;

LinuxNativeFrame and MacosNativeFrame are final subclasses declared only in
their platform-private headers. Compile-time factory pairing guarantees that a
platform importer receives its matching frame subclass.

### 8.3 frame.hpp

Replace the Linux-specific frame fields with:

    enum class FrameStorageKind {
        CpuNv12,
        Native,
    };

    enum class RenderMode {
        CpuNv12,
        Native,
        Placeholder,
    };

    struct CpuNv12Storage {
        std::vector<std::uint8_t> bytes;
        std::uint32_t y_stride = 0;
        std::uint32_t uv_stride = 0;
    };

    struct NativeFrameStorage {
        NativeFramePtr frame;
    };

    using FrameStorage =
        std::variant<CpuNv12Storage, NativeFrameStorage>;

VideoFrame keeps the current session generation, frame generation, coded
geometry, display geometry, rotation, RTP timestamp, color metadata, receive
time, decode time, publish time, and deadline. It contains one FrameStorage.

There is no HardwareCpuTransfer render mode.

The mailbox and renderer texture slot retain VideoFramePtr for as long as the
underlying native object can be sampled. Platform-native destructors release
their resource:

- Linux frees its retained AVFrame with av_frame_free.
- macOS releases its retained CVPixelBuffer with CVPixelBufferRelease.

### 8.4 native_decoder_adapter.hpp

FFmpeg types are allowed because FFmpeg is the common decoder API. Platform
graphics types are not allowed.

Define:

    struct NativeDecoderConfig {
        std::uint32_t width = 0;
        std::uint32_t height = 0;
        std::string device;
    };

    class NativeDecoderAdapter {
    public:
        virtual ~NativeDecoderAdapter() = default;

        virtual NativeSetupResult configure(
            AVCodecContext &codec_context,
            const NativeDecoderConfig &config) = 0;

        virtual AVPixelFormat choose_pixel_format(
            const AVPixelFormat *candidates) const = 0;

        virtual NativeFramePtr export_frame(
            const AVFrame &decoded,
            std::string &error) = 0;

        virtual std::string_view decoder_name() const = 0;
        virtual void reset() = 0;
    };

    std::unique_ptr<NativeDecoderAdapter>
    create_native_decoder_adapter();

Shared Decoder owns avcodec_find_decoder, AVCodecContext allocation, packet
submission, frame receipt, queue policy, timestamps, stale-frame policy,
software decoding, and software NV12 conversion. The adapter owns only native
device setup, hardware pixel-format selection, and native export.

Replace asynchronous decoder reconfiguration with an explicit prepared
lifecycle:

    NativeSetupResult prepare_native_session(
        std::uint64_t stream_generation,
        DecoderConfig config);

    bool prepare_software_session(
        std::uint64_t stream_generation,
        DecoderConfig config,
        std::string &error);

    void activate_prepared_session(SessionMediaPath selected_path);
    void discard_prepared_session();

prepare_native_session reflects adapter configure and avcodec_open2.
prepare_software_session opens a software decoder without calling the adapter.
Neither function accepts access units. activate_prepared_session is called
only after CamBridgeSource commits the locked path. discard_prepared_session
closes partially prepared state without reporting a second failure.

Delete:

- Decoder::request_cpu_fallback.
- DecoderConfig::force_cpu.
- Decoder::hardware_cpu_transfers and its mutable counter.
- Every av_hwframe_transfer_data call used to recover a native frame.

Keep the diagnostics JSON field hardwareCpuTransfers temporarily for tooling
compatibility, but always serialize zero and mark it deprecated in
docs/development.md. Do not retain a dead production counter merely to fill it.

### 8.5 native_frame_importer.hpp

Define:

    enum class ImportedTextureFormat {
        Nv12,
        Bgra,
    };

    class ImportedNativeTexture {
    public:
        virtual ~ImportedNativeTexture() = default;
        virtual ImportedTextureFormat format() const = 0;
        virtual gs_texture_t *primary_texture() const = 0;
        virtual gs_texture_t *chroma_texture() const = 0;
    };

    struct NativeImportResult {
        std::unique_ptr<ImportedNativeTexture> imported_texture;
        std::uint64_t gpu_copy_count = 0;
        std::string error;
    };

    class NativeFrameImporter {
    public:
        virtual ~NativeFrameImporter() = default;

        virtual NativeSetupResult prepare(
            std::uint32_t maximum_width,
            std::uint32_t maximum_height) = 0;

        virtual NativeImportResult import_frame(
            const NativeFramePtr &frame,
            std::uint64_t frame_generation) = 0;

        virtual void reset() = 0;
    };

    std::unique_ptr<NativeFrameImporter>
    create_native_frame_importer();

prepare and import_frame execute only while the OBS graphics context is
entered. prepare receives the session's coded width and height, checks actual
required capability, and allocates fixed resources. It must distinguish
Unsupported from Failed.

Linux returns ImportedTextureFormat::Nv12 with primary and chroma textures.
macOS returns ImportedTextureFormat::Bgra with a primary texture and a null
chroma texture. Renderer selects the matching shared drawing effect from this
format. This format switch is rendering policy, not error recovery.

Renderer owns:

- texture slot rotation;
- CPU NV12 texture upload;
- placeholder resources;
- color effects and drawing;
- output rotation;
- staleness policy;
- common counters;
- retaining the VideoFramePtr in each active slot;
- retaining one ImportedNativeTexture in each native slot.

NativeFrameImporter owns:

- validating the platform-native frame subclass;
- native descriptor or surface inspection;
- importing or rebinding an OBS texture;
- macOS GPU color conversion;
- its bounded platform texture resources.

ImportedNativeTexture is an RAII object. Its destructor releases the OBS
texture binding and any platform pool lease. Renderer destroys it only while
the OBS graphics context is entered. Returning a bare gs_texture_t pointer
without an owning ImportedNativeTexture is forbidden.

Renderer constructor takes the importer and a MediaPathFailureCallback. It has
no HardwareFallbackCallback. Delete:

- Renderer::on_hardware_fallback_;
- Renderer::hardware_fallback_requested_;
- Renderer::update_dmabuf_slot.
- CamBridgeSource::on_renderer_hardware_fallback.

The Linux equivalent of update_dmabuf_slot moves into
native_frame_importer_linux.cpp.

### 8.6 discovery_advertiser.hpp

Define:

    struct DiscoveryConfig {
        std::string instance_name;
        std::uint16_t control_port = 0;
        std::vector<std::string> txt_entries;
    };

    class DiscoveryAdvertiser {
    public:
        virtual ~DiscoveryAdvertiser() = default;
        virtual bool start(
            const DiscoveryConfig &config,
            std::string &error) = 0;
        virtual void stop() = 0;
    };

    std::unique_ptr<DiscoveryAdvertiser>
    create_discovery_advertiser();

discovery_metadata.cpp is the sole builder for service type and TXT content.
Linux and macOS implementations perform registration lifecycle only. They do
not reconstruct metadata or protocol values.

Discovery failure does not change media-path selection. It emits one degraded
status and manual addressing continues to work. Repeated background retries are
not added.

### 8.7 source_properties.hpp

This is a small compile-time UI hook, not an object hierarchy:

    void add_platform_source_properties(
        obs_properties_t *advanced_properties);

    void read_platform_source_settings(
        obs_data_t *settings,
        SourceConfig &config);

Linux adds and reads drm_device. macOS implementations are intentional no-ops.
The shared file adds all other settings, including decoder_mode.

### 8.8 posix_compat.hpp

Linux and macOS remain POSIX implementations. Do not hide poll, getifaddrs,
sockaddr, file descriptors, or clock_gettime behind a generic runtime.

Wrap only proven API differences:

    int create_cloexec_socket(
        int domain,
        int type,
        int protocol,
        std::string &error);

    int accept_cloexec(
        int listener,
        sockaddr *address,
        socklen_t *address_length,
        std::string &error);

    ssize_t send_without_sigpipe(
        int socket,
        const void *data,
        std::size_t size,
        int flags);

    void set_current_thread_name(std::string_view name);

Linux uses SOCK_CLOEXEC, accept4, MSG_NOSIGNAL, and the Linux pthread naming
signature. macOS uses socket or accept followed immediately by F_SETFD with
FD_CLOEXEC, SO_NOSIGPIPE, and the macOS pthread naming signature.

If setting close-on-exec fails, close the new descriptor and return failure.
No descriptor is briefly accepted as successful without the required flag.

### 8.9 Session start order

CamBridgeSource::on_hello performs this exact sequence after validating the
wire message and ending any older session:

1. Read the requested DecoderMode.
2. For Software, call Decoder::prepare_software_session. Do not call the native
   importer or adapter.
3. For Automatic or NativeRequired, enter the OBS graphics context and call
   NativeFrameImporter::prepare with the coded width and height.
4. If importer preparation is Ready, call
   Decoder::prepare_native_session. If importer preparation is Unsupported or
   Failed, do not call the decoder adapter; that importer result is the
   complete native setup result.
5. Pass the complete native setup result to decide_media_path.
6. If the decision is Failed, discard prepared decoder and importer state and
   reject START with the stable error. Do not start RTP acceptance.
7. If Automatic selects Software, discard all prepared native state, then call
   Decoder::prepare_software_session exactly once. A software preparation
   failure rejects START.
8. Under session_mutex_, store the new generation, selected path, and
   mediaPathLocked equal to true.
9. Release session_mutex_. Call Decoder::activate_prepared_session with the
   selected path.
10. Call MediaReceiver::begin_session for the same generation and peer.
11. Report session_accepted with the requested mode and selected path.

If graphics-context entry, importer reset, decoder preparation, or activation
fails unexpectedly, reject START as Failed. Do not reinterpret it as
Unsupported.

## 9. Session failure ownership

CamBridgeSource remains the session owner. Add:

    enum class MediaPathFailureCode {
        Decode,
        NativeExport,
        NativeImport,
        NativeConversion,
        SoftwareUpload,
    };

    struct PendingMediaPathFailure {
        std::uint64_t stream_generation = 0;
        MediaPathFailureCode code = MediaPathFailureCode::Decode;
        std::string detail;
    };

    void CamBridgeSource::post_media_path_failure(
        PendingMediaPathFailure failure);

    void CamBridgeSource::drain_media_path_failure();

Decoder and Renderer may post a failure from their owning threads. Posting is
bounded and idempotent: retain only the first failure for the active
generation. It must not join a worker or destroy graphics resources from the
posting callback.

source_video_tick calls drain_media_path_failure. Draining verifies the
generation, records diagnostics, ends the active session through the normal
lifecycle owner, clears the mailbox, and leaves the placeholder visible.

Do not hold session_mutex_ while joining Decoder or MediaReceiver. Refactor
end_session into:

1. Mark the generation inactive while holding the session mutex.
2. Release the mutex.
3. Stop per-session producers.
4. Clear the mailbox and renderer session state.

This ordering must be covered by repeated start, failure, stop, and destruction
tests.

## 10. Platform implementation requirements

### 10.1 Linux native decoder

native_decoder_linux.cpp:

- Creates AV_HWDEVICE_TYPE_VAAPI using the configured DRM render device.
- Selects AV_PIX_FMT_VAAPI only when FFmpeg offers it.
- Maps a decoded VAAPI AVFrame to AV_PIX_FMT_DRM_PRIME.
- Returns a LinuxNativeFrame retaining the mapped AVFrame.
- Treats absence of a VAAPI device or decoder support as Unsupported during
  setup.
- Treats a mapping failure after setup as a fatal native export error.
- Never calls av_hwframe_transfer_data.

native_frame_linux.hpp is platform-private and owns the AVFrame lifetime. It is
included only by the Linux decoder and importer implementation.

### 10.2 Linux native importer

native_frame_importer_linux.cpp:

- Calls gs_query_dmabuf_capabilities during prepare.
- Returns Unsupported if OBS reports no DMA-BUF import capability.
- Validates plane count, object index, file descriptor, offset, pitch,
  modifier, width, and height before calling OBS.
- Imports the existing NV12 planes with the public OBS DMA-BUF API.
- Keeps all resource counts bounded by contract::kTexturePoolSlots.
- Returns an error on any unsupported layout or failed import.
- Does not request a decoder change.

The first import error fails the session. Later frames for that failed
generation are not imported.

### 10.3 macOS native decoder

native_decoder_macos.mm:

- Creates AV_HWDEVICE_TYPE_VIDEOTOOLBOX.
- Selects AV_PIX_FMT_VIDEOTOOLBOX only when FFmpeg offers it.
- Reads the decoded CVPixelBufferRef from the documented AVFrame hardware
  field.
- Requires an IOSurface-backed bi-planar 4:2:0 pixel buffer.
- Retains that CVPixelBuffer in MacosNativeFrame.
- Treats unavailable VideoToolbox H.264 support as Unsupported during setup.
- Treats a missing pixel buffer, unexpected pixel format, or missing IOSurface
  after setup as a fatal native export error.
- Does not convert or copy pixel bytes on the CPU.

native_frame_macos.hpp is platform-private and owns the CVPixelBuffer lifetime.
It is included only by the macOS decoder and importer implementation.

### 10.4 macOS native importer

native_frame_importer_macos.mm owns:

- one MTLDevice;
- one MTLCommandQueue;
- one CVMetalTextureCache;
- one compiled NV12-to-BGRA compute pipeline;
- a fixed BGRA IOSurface slot array;
- one OBS IOSurface texture per active renderer slot.

Define the pool size by derivation:

    inline constexpr std::size_t kNativeImportPoolSlots =
        contract::kTexturePoolSlots;

Do not write the resulting numeric value elsewhere.

Each pool slot owns:

- a BGRA CVPixelBuffer created with Metal compatibility and IOSurface
  properties;
- the derived IOSurface;
- its CVMetalTexture;
- its OBS texture binding;
- a lease held by ImportedNativeTexture while the renderer slot is active.

The compute shader in data/macos/nv12_to_bgra.metal reads the luma and chroma
planes and writes BGRA. It has explicit coefficient sets for the color spaces
and ranges already allowed by the CamBridge contract. Selecting coefficients
is not a fallback. An unknown color description fails the session.

For every native frame:

1. Destroy the selected renderer slot's previous ImportedNativeTexture, which
   releases its destination lease.
2. Lease one free destination slot.
3. Create Metal plane textures from the source CVPixelBuffer.
4. Encode exactly one NV12-to-BGRA compute pass.
5. Wait for command completion before OBS samples the destination IOSurface.
6. Create or rebind the OBS texture with the public IOSurface API.
7. Return an ImportedNativeTexture owning the lease and gpu_copy_count equal
   to one.

Pool exhaustion is a native conversion failure. Do not allocate an extra
surface, wait without a bound, drop into software, or overwrite a slot still
retained by OBS.

There is no direct-BGRA special path in the first implementation. The expected
VideoToolbox output is validated NV12 and always follows the one documented
GPU conversion. Add optimizations only in a later measured change.

### 10.5 Bonjour

discovery_advertiser_macos.cpp:

- Uses DNSServiceRegister.
- Registers exactly the service type and TXT entries produced by shared code.
- Owns one DNSServiceRef.
- Runs its processing loop on one owned thread with bounded wake and stop.
- Deallocates the reference and joins the thread on stop.
- Does not retry indefinitely or leave a service registered after source
  destruction.

Info.plist.in contains:

- the plugin bundle identifier and version;
- NSLocalNetworkUsageDescription;
- NSBonjourServices with the shared service type.

It contains no camera or microphone usage description because the OBS host is
the receiver.

Permission denial produces one actionable discovery diagnostic. It does not
pretend discovery is active.

## 11. Diagnostics contract

Move diagnostic snapshot construction and JSON serialization into
diagnostics.hpp and diagnostics.cpp. Keep existing field names where possible.

Add:

- requestedDecoderMode
- sessionMediaPath
- mediaPathLocked
- nativeSetupStatus
- nativeSetupReason
- mediaPathFailures
- lastMediaPathFailureCode
- lastMediaPathFailureDetail
- nativeImportFailures
- nativePoolExhaustions
- cpuFrameCopies
- gpuCopies

Requirements:

- NativeRequired acceptance expects sessionMediaPath equal to native.
- NativeRequired acceptance expects cpuFrameCopies equal to zero.
- Linux native acceptance expects gpuCopies equal to zero.
- macOS native acceptance expects gpuCopies equal to decoded native frames
  presented, allowing frames replaced by the mailbox before presentation.
- Software acceptance expects sessionMediaPath equal to software.
- hardwareCpuTransfers remains present with value zero until a separately
  reviewed diagnostics-version change removes it.
- An unsupported automatic selection records the reason; it is not silent.
- No diagnostic claims native merely because VideoToolbox or VAAPI opened.
  Native means a native frame was successfully imported and presented.

## 12. CMake and dependency rules

Use one CMake target. Platform.cmake selects exactly one set of files:

Linux:

- platform/posix/posix_compat.cpp
- platform/linux/native_decoder_linux.cpp
- platform/linux/native_frame_importer_linux.cpp
- platform/linux/discovery_advertiser_linux.cpp
- platform/linux/source_properties_linux.cpp

macOS:

- platform/posix/posix_compat.cpp
- platform/macos/native_decoder_macos.mm
- platform/macos/native_frame_importer_macos.mm
- platform/macos/discovery_advertiser_macos.cpp
- platform/macos/source_properties_macos.cpp
- compiled nv12_to_bgra.metallib

Shared source has no operating-system preprocessor branch. The only
__linux__ or __APPLE__ branches allowed in shared POSIX code are inside
posix_compat.cpp.

Pin the initial OBS build baseline to OBS Studio 32.1.2. Use the official OBS
plugin template conventions for bundle layout and dependency discovery. Record
dependency versions and archive hashes in buildspec.json.

macOS:

- deployment target is 12.0;
- architectures are arm64 and x86_64;
- Objective-C++ is enabled only for the two .mm files;
- link VideoToolbox, CoreVideo, IOSurface, Metal, Foundation, and dns_sd;
- compile the Metal shader during the build;
- do not leave Homebrew paths in the plugin load commands.

No Windows preset, branch, dependency, or workflow is created.

## 13. Agent execution rules

The implementation agent must:

1. Read this entire contract, repository AGENTS.md, the JSON/schema protocol,
   docs/protocol.md, every receiver source/test, and receiver build/fixture
   script before editing production code.
2. Create a plan containing all stages below with only one stage in progress.
3. Execute stages in order.
4. End each stage with its exact gate.
5. Preserve unrelated user changes.
6. Use git mv for tracked moves.
7. Keep path-only relocation separate from behavior changes.
8. Inspect every changed production file for unexplained literals.
9. Report a failing gate with the command and relevant output. Do not work
   around it with sleep, retries, broader queues, disabled tests, or fallback.
10. Never replace required code with TODO, mock success, a no-op backend, or a
    diagnostic string claiming completion.
11. Never weaken native acceptance so software output counts as success.
12. Never add a fourth runtime platform interface without a concrete compile
    error demonstrating the missing boundary.

Temporary checkpoints do not change the definition of done. In particular, a
macOS software-rendered fixture is a porting checkpoint, not macOS support.

## 14. Implementation stages

### Stage 0: Record the current Linux baseline

Create:

- receiver/linux/obs/cambridge-obs-source/tests/linux_baseline.md
- receiver/linux/obs/cambridge-obs-source/tests/latest_frame_mailbox_tests.cpp

Record:

- source IDs and display name;
- property keys and saved values;
- event names;
- diagnostic fields;
- display geometry for every rotation;
- control disconnect and STOP behavior;
- CPU fixture result;
- VAAPI/DMA-BUF fixture result when the host supports it.

Run:

    ./scripts/receiver/linux/build-cambridge-obs-plugin.sh
    CAMBRIDGE_DECODER_MODE=cpu CAMBRIDGE_DURATION_SECONDS=5 \
      ./scripts/receiver/linux/test-cambridge-fixture.sh

Gate:

- All existing unit tests pass.
- The CPU fixture produces changing frames.
- Mailbox tests cover publish replacement, acquire, clear, and generation
  invalidation.
- An unavailable VAAPI host is recorded as unavailable, not passing.

Do not edit production behavior in this stage.

### Stage 1: Generate the native protocol header

Create:

- scripts/development/generate-cambridge-cpp-contract.py
- the generated header at the current receiver path

Modify:

- scripts/development/check-cambridge-stream-contract.py
- receiver includes and CMake inputs

Remove after parity passes:

- src/protocol_contract.hpp

Run:

    python3 scripts/development/generate-cambridge-cpp-contract.py --check
    python3 scripts/development/check-cambridge-stream-contract.py
    ./scripts/receiver/linux/build-cambridge-obs-plugin.sh

Gate:

- Regeneration produces no diff.
- Protocol tests pass.
- No production C++ file contains a duplicated protocol value.

### Stage 2: Relocate without behavior changes

Use:

    git mv receiver/linux/obs/cambridge-obs-source \
      receiver/obs/cambridge-obs-source

Move the Python fixture, test profile, and test scene into
scripts/receiver/common with git mv. Update Linux scripts, workflow path
filters, documentation links, and the generator output path.

Do not rename C++ types, split files, or alter behavior.

Gate:

- Every Stage 1 check passes from the new path.
- The Linux fixture matches Stage 0.
- git diff --check passes.
- rg finds no live reference to the old receiver path.

### Stage 3: Add and enforce immutable media-path policy

Create:

- src/media_path.hpp
- src/media_path.cpp
- tests/media_path_tests.cpp
- tests/decoder_policy_tests.cpp
- tests/renderer_policy_tests.cpp

Modify:

- cambridge_source.hpp and cambridge_source.cpp
- decoder.hpp and decoder.cpp
- renderer.hpp and renderer.cpp
- frame.hpp
- fixture settings support

Actions:

1. Add the enums and pure decision function from section 8.
2. Parse auto, cpu, and native_required into DecoderMode.
3. Add the three UI choices without changing the property key.
4. Implement the prepared decoder lifecycle from section 8.4.
5. Execute the session start order from section 8.9.
6. Lock SessionMediaPath before MediaReceiver::begin_session.
7. Add the bounded pending failure mechanism.
8. Delete request_cpu_fallback and the renderer fallback callback.
9. Delete native-to-CPU AVFrame transfer.
10. Make native export/import failure fail the active generation.
11. Keep software mode fully functional.

Required fake-driven tests:

- every row of the selection table;
- Automatic plus Unsupported selects software once;
- Automatic plus Failed rejects the session;
- NativeRequired never selects software;
- Software never invokes native setup;
- failure after path lock never calls either setup path again;
- only the first failure for a generation is retained;
- failure from an old generation cannot stop a new session;
- decoder export failure and renderer import failure both invalidate the active
  generation;
- repeated stop and destruction cannot deadlock.

Gate:

    ./scripts/receiver/linux/build-cambridge-obs-plugin.sh
    CAMBRIDGE_DECODER_MODE=cpu CAMBRIDGE_DURATION_SECONDS=5 \
      ./scripts/receiver/linux/test-cambridge-fixture.sh

Also run:

    rg -n "request_cpu_fallback|HardwareFallbackCallback|av_hwframe_transfer_data" \
      receiver/obs/cambridge-obs-source/src

The search must return no production fallback implementation. Linux native
testing is deferred until the extraction stages restore its adapters.

### Stage 4: Add only the required POSIX compatibility functions

Create:

- src/platform/posix/posix_compat.hpp
- src/platform/posix/posix_compat.cpp
- tests/posix_compat_tests.cpp

Modify:

- control_server.cpp
- media_receiver.cpp
- decoder.cpp only for thread naming

Move only socket creation, accept-with-close-on-exec, send-without-SIGPIPE, and
thread naming behind the functions in section 8.8. Leave poll, recv, recvfrom,
sockaddr, getifaddrs, and monotonic time shared.

Gate:

- Linux unit tests and CPU fixture pass.
- File descriptors are closed on every failed flag-setting path.
- rg finds accept4, MSG_NOSIGNAL, SO_NOSIGPIPE, and platform pthread naming
  signatures only in posix_compat.cpp.
- No Runtime, SocketManager, NetworkAdapter, or equivalent abstraction exists.

### Stage 5: Make frame ownership platform-neutral

Create:

- src/platform/interfaces/native_frame.hpp
- tests/frame_lifetime_tests.cpp

Modify:

- frame.hpp
- latest_frame_mailbox.hpp
- decoder files
- renderer files

Introduce FrameStorage and move the existing Linux AVFrame ownership into a
temporary LinuxNativeFrame in the existing implementation. Preserve every
timestamp and geometry field.

Gate:

- CPU fixture passes.
- Mailbox replacement releases the replaced native frame.
- Renderer slot replacement releases the old native frame only after its OBS
  texture is no longer used.
- Repeated begin/end cycles show no retained frame growth under ASan or
  Valgrind where available.

### Stage 6: Extract the Linux native decoder adapter

Create:

- src/platform/interfaces/native_decoder_adapter.hpp
- src/platform/linux/native_frame_linux.hpp
- src/platform/linux/native_decoder_linux.cpp

Modify:

- decoder.hpp
- decoder.cpp
- CMake files

Move only VAAPI setup, hardware format choice, and DRM PRIME export. Keep the
FFmpeg packet/decode loop in shared Decoder.

Gate:

- Software mode never calls the fake or real native adapter.
- NativeRequired fails when VAAPI setup is Unsupported or Failed.
- DRM PRIME export failure posts NativeExport and never publishes CPU storage.
- On a capable Linux host, decoded native frames own valid retained AVFrames.
- No VAAPI or DRM header remains in decoder.hpp, decoder.cpp, or frame.hpp.

### Stage 7: Extract the Linux native importer

Create:

- src/platform/interfaces/native_frame_importer.hpp
- src/platform/linux/native_frame_importer_linux.cpp

Modify:

- renderer.hpp
- renderer.cpp
- cambridge_source files
- CMake files

Move DMA-BUF descriptor validation and OBS import out of Renderer. Replace the
old fallback callback with MediaPathFailureCallback.

Gate:

    CAMBRIDGE_DECODER_MODE=native_required CAMBRIDGE_DURATION_SECONDS=5 \
      ./scripts/receiver/linux/test-cambridge-fixture.sh

On a capable host:

- diagnostics show sessionMediaPath native;
- decoderName is h264/VAAPI;
- render mode is native;
- cpuFrameCopies is zero;
- hardwareCpuTransfers is zero;
- a forced import fault fails the fixture instead of rendering CPU frames.

If the current host lacks VAAPI or DMA-BUF, unit fault-injection tests must
still pass and the physical gate remains explicitly pending.

### Stage 8: Extract discovery and platform property glue

Create:

- src/discovery_metadata.hpp
- src/discovery_metadata.cpp
- src/platform/interfaces/discovery_advertiser.hpp
- src/platform/interfaces/source_properties.hpp
- src/platform/linux/discovery_advertiser_linux.cpp
- src/platform/linux/source_properties_linux.cpp
- tests/discovery_metadata_tests.cpp

Remove:

- the old src/discovery_advertiser.cpp and header after migration

Move Avahi lifecycle without changing its observable behavior. Centralize TXT
construction. Move only drm_device UI/read behavior into the Linux property
file.

Gate:

- Linux build with required Avahi succeeds.
- avahi-browse observes exactly one service with the baseline type, port, and
  TXT values.
- Source destruction removes the service.
- Discovery metadata tests prove stable ordering, no duplicate keys, bounded
  address count, and generated protocol values.

### Stage 9: Linux parity checkpoint

Create:

- src/diagnostics.hpp
- src/diagnostics.cpp

Move diagnostics construction without renaming existing fields. Add the fields
from section 11. Run ten CPU sessions and ten native sessions on a capable
host.

Gate:

    ./scripts/receiver/linux/build-cambridge-obs-plugin.sh
    ldd -r build/cambridge-obs-source/cambridge-obs-source.so

Also run both fixture modes.

Required result:

- CPU and native modes satisfy their diagnostic assertions.
- Automatic on a machine with no native support emits exactly one explicit
  selection event and uses software.
- Forced native export/import failures fail the session.
- No source ID, setting key, protocol behavior, rotation, or lifecycle
  regression exists versus linux_baseline.md.
- No unbounded queue, native pool, retry, or allocation was introduced.

Do not start macOS work until this checkpoint passes.

### Stage 10: Establish the macOS build foundation

Create:

- CMakePresets.json
- buildspec.json
- cmake/CompilerWarnings.cmake
- cmake/Dependencies.cmake
- cmake/Platform.cmake
- cmake/macos/Info.plist.in
- a macos-shared-tests CMake preset

Do not link the macOS OBS plugin yet. Build only test targets whose complete
implementations exist: protocol, RTP, media-path policy, mailbox, discovery
metadata, network-address candidates, and POSIX compatibility. The preset sets
CAMBRIDGE_BUILD_PLUGIN to OFF and CAMBRIDGE_BUILD_TESTS to ON. It does not
compile a fake native factory or a no-op platform backend.

Gate on both Apple Silicon and Intel:

- macos-shared-tests configures without Linux dependencies;
- every selected shared test builds and passes;
- POSIX loopback tests prove TCP accept/send, UDP receive, clean shutdown, and
  close-on-exec behavior;
- both architectures resolve the pinned OBS and FFmpeg dependencies;
- no macOS plugin artifact is produced or claimed.

This is a compilation checkpoint, not macOS support.

### Stage 11: Add Bonjour

Create:

- src/platform/macos/discovery_advertiser_macos.cpp
- a macOS Bonjour lifecycle test target

Implement section 10.5 and bundle metadata. Add fixture discovery assertions.

Gate:

- Android and iPhone discovery each observe one receiver.
- Manual addressing still works when local-network permission is denied.
- Denial produces one degraded diagnostic.
- Source destruction and OBS exit remove the registration.
- Ten start/stop cycles do not leak a DNSServiceRef or thread.

### Stage 12: Add VideoToolbox native decode

Create:

- src/platform/macos/native_frame_macos.hpp
- src/platform/macos/native_decoder_macos.mm

Implement section 10.3. Add an adapter integration test that feeds a bounded
H.264 sample generated by the existing fixture tooling and verifies the
returned native frame owns a CVPixelBuffer.

Do not route this output through CPU memory merely to make the end-to-end
fixture pass before the importer exists.

Gate on both architectures:

- VideoToolbox adapter setup is Ready on a supported host.
- NativeRequired setup fails if VideoToolbox is unavailable.
- The exported frame is IOSurface-backed bi-planar 4:2:0.
- Releasing every VideoFrame releases its CVPixelBuffer.
- CPU copy count remains zero.

The OBS native rendering gate remains pending until Stage 13.

### Stage 13: Add Metal conversion and IOSurface import

Create:

- data/macos/nv12_to_bgra.metal
- src/platform/macos/native_frame_importer_macos.mm
- src/platform/macos/source_properties_macos.cpp
- scripts/receiver/macos/build-cambridge-obs-plugin.sh
- scripts/receiver/macos/test-cambridge-fixture.sh

Implement section 10.4. Link the first complete macOS plugin from the shared
sources and all three real macOS platform implementations. Enable Automatic,
NativeRequired, and Software in the normal macOS property UI. There is no
temporary native-unavailable branch to remove because no partial plugin was
linked in earlier stages.

Gate on Apple Silicon and Intel:

    CAMBRIDGE_DECODER_MODE=native_required CAMBRIDGE_DURATION_SECONDS=5 \
      ./scripts/receiver/macos/test-cambridge-fixture.sh

Required:

- the universal plugin bundle contains arm64 and x86_64 slices;
- OBS loads both retained source IDs;
- the Software fixture renders changing frames;
- STOP, disconnect, source deletion, and OBS shutdown are clean;
- otool -L shows no unintended Homebrew path;
- 1080p30, 1080p60, 2K30, and 2K60 render with correct orientation;
- diagnostics show h264/VideoToolbox and native;
- cpuFrameCopies and hardwareCpuTransfers are zero;
- gpuCopies records one conversion per presented native frame;
- faulted export, conversion, import, and pool exhaustion each fail the
  session;
- none selects software;
- a 30-minute 2K60 run has bounded memory and queue occupancy;
- ten source create/destroy and ten session start/stop cycles are clean.

macOS support is not complete until this stage passes on both architectures.

### Stage 14: Package, CI, and documentation

Create:

- scripts/release/package-macos-plugin.sh

Modify:

- native CI workflow
- release workflow
- scripts/development/check-all.sh
- README.md
- docs/architecture.md
- docs/development.md
- docs/installation.md
- docs/known-limitations.md
- CHANGELOG.md
- THIRD_PARTY_NOTICES.md

CI has:

- Linux build and unit-test job;
- macOS arm64 build/test job;
- macOS x86_64 build/test job or a documented Intel runner;
- generated-contract staleness check;
- binary architecture and dependency checks;
- tests that require NativeRequired when asserting native behavior.

Package:

    cambridge-obs-plugin-VERSION-macos-universal.pkg

The release package is Developer ID signed, notarized, stapled, and verified.
Development builds may be ad-hoc signed. Credentials never enter repository
files or logs.

Only after clean-machine installation and the Stage 13 physical matrix pass:

- mark macOS supported in public documentation;
- publish the package and checksum;
- document local-network permission and manual addressing;
- document Automatic, NativeRequired, and Software behavior precisely.

Do not add Windows claims or placeholders.

### Stage 15: Final architectural audit

Run repository checks and search for:

    rg -n "request_cpu_fallback|HardwareFallbackCallback|av_hwframe_transfer_data" \
      receiver/obs/cambridge-obs-source/src

    rg -n "VAAPI|DRM_PRIME|dmabuf|CVPixelBuffer|IOSurface|MTL" \
      receiver/obs/cambridge-obs-source/src \
      -g '!**/platform/**'

    rg -n "__linux__|__APPLE__" \
      receiver/obs/cambridge-obs-source/src \
      -g '!**/platform/posix/posix_compat.cpp'

Inspect every changed production file for:

- unexplained numeric literals;
- duplicated contract values;
- unused variables;
- unbounded queues or pools;
- a native-to-software transition after path lock;
- native APIs in shared files;
- OBS graphics calls outside an entered graphics context;
- blocking joins while session_mutex_ is held;
- stale old receiver paths;
- false support claims;
- signing secrets.

The first native-API search may match diagnostic strings or interface names.
Review each result. It must not match a native header, native object field, or
native API call outside the selected platform implementation.

## 15. Physical verification matrix

| Host | Software | Native | Discovery | Rotation | Lifecycle | Package |
| --- | --- | --- | --- | --- | --- | --- |
| Linux x86_64 | required | VAAPI/DMA-BUF on capable host | Avahi | 0/90/180/270 | ten cycles | existing Linux package |
| macOS arm64 | required | VideoToolbox/Metal/IOSurface | Bonjour | 0/90/180/270 | ten cycles | universal package |
| macOS x86_64 | required | VideoToolbox/Metal/IOSurface | Bonjour | 0/90/180/270 | ten cycles | same universal package |

For every native run record:

- requested decoder mode;
- selected and locked media path;
- decoder name;
- frame storage kind;
- render mode;
- CPU frame copy count;
- GPU copy count;
- native setup result;
- export, conversion, and import failures;
- native pool exhaustion;
- decoder queue and RTP reorder peaks;
- maximum receive-to-decode, receive-to-publish, and receive-to-render latency;
- resident memory at start, 15 minutes, and 30 minutes.

Test both Android and iPhone senders when physical devices are available. A
sender that is unavailable is recorded as an outstanding physical gate, not
silently omitted.

## 16. Definition of done

The work is complete only when:

- one receiver tree contains all shared code;
- the generated C++ protocol header is current in CI;
- Linux behavior matches the recorded baseline except for the intentionally
  stricter failure policy;
- macOS universal builds load in the pinned OBS version;
- all three media modes behave exactly as section 4 specifies;
- a path is locked before RTP acceptance and never changes during a session;
- no production native-to-CPU transfer or decoder rebuild fallback remains;
- Linux native frames reach OBS through DRM PRIME and DMA-BUF;
- macOS native frames reach OBS through VideoToolbox, one Metal conversion,
  IOSurface, and the public OBS import API;
- native-required tests fail on any software rendering;
- queues and native pools remain bounded during soak tests;
- Avahi and Bonjour publish identical shared metadata;
- clean-machine macOS install, uninstall, and reinstall pass;
- documentation makes no Windows support claim;
- no required work remains as a TODO, disabled test, mock, or unverified
  success claim.

## 17. Evidence-based blocker policy

The agent must not ask for routine decisions covered here. It may stop only
with evidence that:

- the pinned OBS public API cannot import the required native resource;
- the pinned FFmpeg build lacks VAAPI or VideoToolbox support;
- the macOS host process cannot perform the documented Bonjour lifecycle;
- required signing or notarization credentials are unavailable at packaging;
- a required physical architecture is unavailable for its final gate;
- preserving the protocol is impossible for a demonstrated reason.

A blocker report contains:

- the exact command;
- operating-system and OBS/FFmpeg version;
- the smallest relevant log excerpt;
- the relevant official API reference;
- bounded alternatives attempted;
- the smallest decision actually required.

The agent must not respond to a blocker by adding a hidden fallback, another
media pipeline, an unbounded retry, or a claim that software output proves the
native implementation.
