#include "renderer.hpp"

#include "protocol_contract.generated.hpp"

#if defined(__APPLE__)
#include <obs.h>
#else
#include <obs/obs.h>
#endif

namespace cambridge {
namespace {

constexpr std::size_t kTextureSlotCount = contract::kTexturePoolSlots;
constexpr std::uint32_t kUnsetDimension = 0;
constexpr std::uint32_t kPlaceholderWidth = 2;
constexpr std::uint32_t kPlaceholderHeight = 2;
constexpr std::uint32_t kPlaceholderTextureLevels = 1;
constexpr std::uint32_t kNv12TextureLevels = 1;
constexpr std::uint32_t kNoTextureFlags = 0;
constexpr std::uint32_t kDynamicTextureFlags = GS_DYNAMIC;
constexpr std::uint32_t kNoFlip = 0;
constexpr std::uint32_t kNv12ChromaColumnsDivisor = 2;
constexpr std::uint32_t kNv12ChromaRowsDivisor = 2;
constexpr char kNv12EffectName[] = "cambridge-nv12.effect";
constexpr char kNv12Effect[] = R"(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform texture2d image1;
uniform bool full_range;
uniform int rotation_quarter_turn;

// OBS effect shaders accept simple named preprocessor constants, not global const declarations.
#define kQuarterTurnOne 1
#define kQuarterTurnTwo 2
#define kQuarterTurnThree 3
#define kUnitCoordinate 1.0
#define kFullRangeChromaCenter 0.5
#define kVideoRangeChromaCenter 0.5019607843
#define kVideoRangeLumaOffset 0.0627450980
#define kVideoRangeLumaScale 1.16438356
#define kBt709RedCoefficient 1.5748
#define kBt709GreenBlueCoefficient 0.1873
#define kBt709GreenRedCoefficient 0.4681
#define kBt709BlueCoefficient 1.8556
#define kBt709LimitedRedCoefficient 1.79274107
#define kBt709LimitedGreenBlueCoefficient 0.21324866
#define kBt709LimitedGreenRedCoefficient 0.53290960
#define kBt709LimitedBlueCoefficient 2.11240179


sampler_state def_sampler {
    Filter = Linear;
    AddressU = Clamp;
    AddressV = Clamp;
};

struct VertInOut {
    float4 pos : POSITION;
    float2 uv : TEXCOORD0;
};

VertInOut VSDefault(VertInOut vert_in)
{
    VertInOut vert_out;
    vert_out.pos = mul(float4(vert_in.pos.xyz, kUnitCoordinate), ViewProj);
    vert_out.uv = vert_in.uv;
    return vert_out;
}

float4 PSNv12(VertInOut vert_in) : TARGET
{
    float2 sample_uv = vert_in.uv;
    if (rotation_quarter_turn == kQuarterTurnOne) {
        sample_uv = float2(vert_in.uv.y, kUnitCoordinate - vert_in.uv.x);
    } else if (rotation_quarter_turn == kQuarterTurnTwo) {
        sample_uv = float2(kUnitCoordinate - vert_in.uv.x, kUnitCoordinate - vert_in.uv.y);
    } else if (rotation_quarter_turn == kQuarterTurnThree) {
        sample_uv = float2(kUnitCoordinate - vert_in.uv.y, vert_in.uv.x);
    }
    float y = image.Sample(def_sampler, sample_uv).r;
    float2 uv = image1.Sample(def_sampler, sample_uv).rg;
    if (!full_range) {
        y = (y - kVideoRangeLumaOffset) * kVideoRangeLumaScale;
        uv -= float2(kVideoRangeChromaCenter, kVideoRangeChromaCenter);
    } else {
        uv -= float2(kFullRangeChromaCenter, kFullRangeChromaCenter);
    }
    float3 rgb;
    if (full_range) {
        rgb.r = y + kBt709RedCoefficient * uv.y;
        rgb.g = y - kBt709GreenBlueCoefficient * uv.x - kBt709GreenRedCoefficient * uv.y;
        rgb.b = y + kBt709BlueCoefficient * uv.x;
    } else {
        rgb.r = y + kBt709LimitedRedCoefficient * uv.y;
        rgb.g = y - kBt709LimitedGreenBlueCoefficient * uv.x -
            kBt709LimitedGreenRedCoefficient * uv.y;
        rgb.b = y + kBt709LimitedBlueCoefficient * uv.x;
    }
    return float4(rgb, kUnitCoordinate);
}

float4 PSBgra(VertInOut vert_in) : TARGET
{
    float2 sample_uv = vert_in.uv;
    if (rotation_quarter_turn == kQuarterTurnOne) {
        sample_uv = float2(vert_in.uv.y, kUnitCoordinate - vert_in.uv.x);
    } else if (rotation_quarter_turn == kQuarterTurnTwo) {
        sample_uv = float2(kUnitCoordinate - vert_in.uv.x, kUnitCoordinate - vert_in.uv.y);
    } else if (rotation_quarter_turn == kQuarterTurnThree) {
        sample_uv = float2(kUnitCoordinate - vert_in.uv.y, vert_in.uv.x);
    }
    return image.Sample(def_sampler, sample_uv);
}

technique Draw {
    pass {
        vertex_shader = VSDefault(vert_in);
        pixel_shader = PSNv12(vert_in);
    }
}

technique DrawBgra {
    pass {
        vertex_shader = VSDefault(vert_in);
        pixel_shader = PSBgra(vert_in);
    }
}
)";

constexpr char kNativeRenderMode[] = "native";
constexpr char kSoftwareRenderMode[] = "software";
constexpr std::uint32_t kQuarterTurnDegrees = 90;
constexpr std::uint32_t kQuarterTurnCount = 4;
} // namespace

Renderer::Renderer(RendererConfig config, std::unique_ptr<NativeFrameImporter> importer,
                   EventCallback on_event, MediaPathFailureCallback on_failure)
    : config_(config), importer_(std::move(importer)), on_event_(std::move(on_event)),
      on_failure_(std::move(on_failure))
{
    static_assert(kTextureSlotCount == contract::kTexturePoolSlots);
}

Renderer::~Renderer()
{
    if (graphics_resources_ready_) {
        obs_enter_graphics();
        reset();
        obs_leave_graphics();
    }
}

void Renderer::report(const std::string &event)
{
    if (on_event_) {
        on_event_(event);
    }
}

std::string Renderer::render_mode() const
{
    std::lock_guard<std::mutex> lock(render_mode_mutex_);
    return active_render_mode_;
}

void Renderer::ensure_graphics_resources()
{
    if (graphics_resources_ready_) {
        return;
    }
    const std::uint8_t placeholder_alpha = config_.transparent_placeholder ? 0U : 255U;
    const std::uint8_t black_pixels[] = {
        0, 0, 0, placeholder_alpha,
        0, 0, 0, placeholder_alpha,
        0, 0, 0, placeholder_alpha,
        0, 0, 0, placeholder_alpha,
    };
    const std::uint8_t *placeholder_data[] = {black_pixels};
    placeholder_ = gs_texture_create(kPlaceholderWidth, kPlaceholderHeight, GS_RGBA, kPlaceholderTextureLevels,
                                     placeholder_data, kNoTextureFlags);
    if (!placeholder_) {
        report("placeholder_texture_create_failed");
    }
    char *errors = nullptr;
    nv12_effect_ = gs_effect_create(kNv12Effect, kNv12EffectName, &errors);
    if (!nv12_effect_) {
        report(std::string("nv12_effect_create_failed:") + (errors ? errors : "unknown"));
    }
    if (errors) {
        bfree(errors);
    }
    graphics_resources_ready_ = true;
}

NativeSetupResult Renderer::prepare_native_session(std::uint32_t maximum_width,
                                                   std::uint32_t maximum_height)
{
    if (maximum_width == kUnsetDimension || maximum_height == kUnsetDimension) {
        return {NativeSetupStatus::Failed, "native importer dimensions are empty"};
    }
    ensure_graphics_resources();
    if (!placeholder_ || !nv12_effect_) {
        return {NativeSetupStatus::Failed, "OBS graphics resources are unavailable"};
    }
    if (!importer_) {
        return {NativeSetupStatus::Unsupported, "native frame importer is unavailable"};
    }
    return importer_->prepare(maximum_width, maximum_height);
}

void Renderer::discard_prepared_native_session()
{
    if (importer_) {
        importer_->reset();
    }
}

void Renderer::activate_session_media_path(SessionMediaPath path)
{
    active_media_path_.store(path);
    failed_generation_.store(kInactiveStreamGeneration);
    std::lock_guard<std::mutex> lock(render_mode_mutex_);
    active_render_mode_ = "placeholder";
}

void Renderer::end_session()
{
    if (graphics_resources_ready_) {
        obs_enter_graphics();
        for (TextureSlot &slot : slots_) {
            destroy_slot(slot);
        }
        if (importer_) {
            importer_->reset();
        }
        obs_leave_graphics();
    }
    next_slot_ = 0;
    active_media_path_.store(SessionMediaPath::Unselected);
    failed_generation_.store(kInactiveStreamGeneration);
    std::lock_guard<std::mutex> lock(render_mode_mutex_);
    active_render_mode_ = "placeholder";
}

bool Renderer::update_cpu_slot(TextureSlot &slot, const VideoFramePtr &frame)
{
    const auto *storage = frame ? std::get_if<CpuNv12Storage>(&frame->storage) : nullptr;
    if (!storage || storage->bytes.empty() || !nv12_effect_) {
        return false;
    }
    gs_texture_t *y_texture = nullptr;
    gs_texture_t *uv_texture = nullptr;
    const std::uint32_t uv_width =
        (frame->width + kNv12ChromaColumnsDivisor - 1U) / kNv12ChromaColumnsDivisor;
    const std::uint32_t uv_height =
        (frame->height + kNv12ChromaRowsDivisor - 1U) / kNv12ChromaRowsDivisor;
    const std::uint8_t *no_initial_data[] = {nullptr};
    y_texture = gs_texture_create(frame->width, frame->height, GS_R8, kNv12TextureLevels, no_initial_data,
                                   kDynamicTextureFlags);
    uv_texture = gs_texture_create(uv_width, uv_height, GS_R8G8, kNv12TextureLevels, no_initial_data,
                                   kDynamicTextureFlags);
    if (!y_texture || !uv_texture) {
        if (y_texture) {
            gs_texture_destroy(y_texture);
        }
        if (uv_texture) {
            gs_texture_destroy(uv_texture);
        }
        return false;
    }
    const std::size_t y_bytes = static_cast<std::size_t>(storage->y_stride) * frame->height;
    const std::size_t uv_bytes = static_cast<std::size_t>(storage->uv_stride) *
                                 ((frame->height + kNv12ChromaRowsDivisor - 1U) /
                                  kNv12ChromaRowsDivisor);
    if (storage->y_stride == 0 || storage->uv_stride == 0 ||
        y_bytes > storage->bytes.size() || uv_bytes > storage->bytes.size() - y_bytes) {
        gs_texture_destroy(y_texture);
        gs_texture_destroy(uv_texture);
        return false;
    }
    gs_texture_set_image(y_texture, storage->bytes.data(), storage->y_stride, false);
    gs_texture_set_image(uv_texture, storage->bytes.data() + y_bytes, storage->uv_stride, false);
    destroy_slot(slot);
    slot.texture = y_texture;
    slot.uv_texture = uv_texture;
    slot.frame = frame;
    slot.generation = frame->frame_generation;
    cpu_uploads_.fetch_add(1);
    return true;
}

bool Renderer::update_native_slot(TextureSlot &slot, const VideoFramePtr &frame)
{
    const auto *storage = frame ? std::get_if<NativeFrameStorage>(&frame->storage) : nullptr;
    if (!storage || !storage->frame || !importer_) {
        report("native_frame_unavailable");
        fail(frame, MediaPathFailureCode::NativeImport, "native frame is unavailable");
        return false;
    }
    destroy_slot(slot);
    NativeImportResult result = importer_->import_frame(storage->frame, frame->frame_generation);
    if (!result.imported_texture) {
        if (!result.error.empty()) {
            report(result.error);
        }
        const bool conversion_failure = result.error.rfind("native_conversion:", 0) == 0 ||
                                        result.error.rfind("native_pool_exhaustion:", 0) == 0;
        fail(frame, conversion_failure ? MediaPathFailureCode::NativeConversion
                                       : MediaPathFailureCode::NativeImport,
             result.error.empty() ? "native texture import failed" : result.error);
        return false;
    }
    const bool valid_nv12 = result.imported_texture->format() == ImportedTextureFormat::Nv12 &&
                            result.imported_texture->primary_texture() &&
                            result.imported_texture->chroma_texture();
    const bool valid_bgra = result.imported_texture->format() == ImportedTextureFormat::Bgra &&
                            result.imported_texture->primary_texture() &&
                            !result.imported_texture->chroma_texture();
    if (!valid_nv12 && !valid_bgra) {
        report("native_texture_format_unsupported");
        fail(frame, MediaPathFailureCode::NativeImport, "native texture format is unsupported");
        return false;
    }
    destroy_slot(slot);
    slot.texture = result.imported_texture->primary_texture();
    slot.uv_texture = result.imported_texture->chroma_texture();
    slot.imported_texture = std::move(result.imported_texture);
    slot.frame = frame;
    slot.generation = frame->frame_generation;
    gpu_copies_.fetch_add(result.gpu_copy_count);
    return true;
}

bool Renderer::update_slot(TextureSlot &slot, const VideoFramePtr &frame)
{
    const SessionMediaPath active_media_path = active_media_path_.load();
    if (!frame || active_media_path == SessionMediaPath::Unselected ||
        active_media_path == SessionMediaPath::Failed) {
        return false;
    }
    if (failed_generation_.load() == frame->stream_generation) {
        return false;
    }
    if (!frame_storage_matches_media_path(active_media_path, frame_storage_kind(frame->storage))) {
        if (active_media_path == SessionMediaPath::Native) {
            fail(frame, MediaPathFailureCode::NativeImport, "native session received CPU frame storage");
        } else {
            fail(frame, MediaPathFailureCode::SoftwareUpload,
                 "software session received native frame storage");
        }
        return false;
    }
    if (active_media_path == SessionMediaPath::Native) {
        if (update_native_slot(slot, frame)) {
            bool mode_changed = false;
            {
                std::lock_guard<std::mutex> lock(render_mode_mutex_);
                mode_changed = active_render_mode_ != kNativeRenderMode;
                active_render_mode_ = kNativeRenderMode;
            }
            if (mode_changed) {
                report("render_mode=native");
            }
            return true;
        }
        return false;
    }
    if (update_cpu_slot(slot, frame)) {
        bool mode_changed = false;
        {
            std::lock_guard<std::mutex> lock(render_mode_mutex_);
            mode_changed = active_render_mode_ != kSoftwareRenderMode;
            active_render_mode_ = kSoftwareRenderMode;
        }
        if (mode_changed) {
            report("render_mode=software");
        }
        return true;
    }
    fail(frame, MediaPathFailureCode::SoftwareUpload, "CPU NV12 texture upload failed");
    return false;
}

void Renderer::fail(const VideoFramePtr &frame, MediaPathFailureCode code, const std::string &detail)
{
    if (!frame) {
        return;
    }
    std::uint64_t failed_generation = failed_generation_.load();
    while (failed_generation != frame->stream_generation) {
        if (failed_generation_.compare_exchange_weak(failed_generation,
                                                     frame->stream_generation)) {
            break;
        }
    }
    if (failed_generation == frame->stream_generation) {
        return;
    }
    import_failures_.fetch_add(code == MediaPathFailureCode::NativeImport ? 1U : 0U);
    if (on_failure_) {
        on_failure_(frame->stream_generation, code, detail);
    }
}

void Renderer::draw_placeholder(std::uint32_t output_width, std::uint32_t output_height)
{
    if (!placeholder_) {
        return;
    }
    gs_effect_t *effect = obs_get_base_effect(OBS_EFFECT_DEFAULT);
    if (!effect) {
        return;
    }
    gs_eparam_t *image = gs_effect_get_param_by_name(effect, "image");
    gs_effect_set_texture(image, placeholder_);
    while (gs_effect_loop(effect, "Draw")) {
        gs_draw_sprite(placeholder_, kNoFlip, output_width, output_height);
    }
    std::lock_guard<std::mutex> lock(render_mode_mutex_);
    active_render_mode_ = "placeholder";
}

void Renderer::draw_cpu(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height,
                        bool full_range, std::uint32_t rotation_degrees)
{
    if (!slot.texture || !slot.uv_texture || !nv12_effect_) {
        draw_placeholder(output_width, output_height);
        return;
    }
    gs_eparam_t *image = gs_effect_get_param_by_name(nv12_effect_, "image");
    gs_eparam_t *image1 = gs_effect_get_param_by_name(nv12_effect_, "image1");
    gs_eparam_t *range = gs_effect_get_param_by_name(nv12_effect_, "full_range");
    gs_eparam_t *rotation = gs_effect_get_param_by_name(nv12_effect_, "rotation_quarter_turn");
    gs_effect_set_texture(image, slot.texture);
    gs_effect_set_texture(image1, slot.uv_texture);
    gs_effect_set_bool(range, full_range);
    const auto quarter_turn = static_cast<int>((rotation_degrees / kQuarterTurnDegrees) % kQuarterTurnCount);
    gs_effect_set_int(rotation, quarter_turn);
    while (gs_effect_loop(nv12_effect_, "Draw")) {
        gs_draw_sprite(slot.texture, kNoFlip, output_width, output_height);
    }
}

void Renderer::draw_native(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height)
{
    if (!slot.frame || !slot.imported_texture) {
        draw_placeholder(output_width, output_height);
        return;
    }
    if (slot.imported_texture->format() == ImportedTextureFormat::Nv12) {
        if (!slot.imported_texture->chroma_texture()) {
            draw_placeholder(output_width, output_height);
            return;
        }
        draw_cpu(slot, output_width, output_height, slot.frame->color_range == "full",
                 slot.frame->rotation_degrees);
    } else if (slot.imported_texture->format() == ImportedTextureFormat::Bgra) {
        draw_bgra(slot, output_width, output_height, slot.frame->rotation_degrees);
    } else {
        draw_placeholder(output_width, output_height);
    }
}

void Renderer::draw_bgra(const TextureSlot &slot, std::uint32_t output_width,
                         std::uint32_t output_height, std::uint32_t rotation_degrees)
{
    if (!slot.texture || !nv12_effect_) {
        draw_placeholder(output_width, output_height);
        return;
    }
    gs_eparam_t *image = gs_effect_get_param_by_name(nv12_effect_, "image");
    gs_eparam_t *rotation = gs_effect_get_param_by_name(nv12_effect_, "rotation_quarter_turn");
    gs_effect_set_texture(image, slot.texture);
    const auto quarter_turn = static_cast<int>((rotation_degrees / kQuarterTurnDegrees) % kQuarterTurnCount);
    gs_effect_set_int(rotation, quarter_turn);
    while (gs_effect_loop(nv12_effect_, "DrawBgra")) {
        gs_draw_sprite(slot.texture, kNoFlip, output_width, output_height);
    }
}

bool Renderer::render(const VideoFramePtr &frame, std::uint32_t output_width, std::uint32_t output_height)
{
    ensure_graphics_resources();
    const SessionMediaPath active_media_path = active_media_path_.load();
    if (!frame || frame->frame_generation == 0 || active_media_path == SessionMediaPath::Unselected ||
        active_media_path == SessionMediaPath::Failed) {
        draw_placeholder(output_width, output_height);
        return false;
    }
    if (frame->publish_time_ns == 0) {
        draw_placeholder(output_width, output_height);
        return false;
    }
    TextureSlot *slot = nullptr;
    for (TextureSlot &candidate : slots_) {
        if (candidate.generation == frame->frame_generation) {
            slot = &candidate;
            break;
        }
    }
    if (!slot) {
        slot = &slots_[next_slot_++ % slots_.size()];
        if (!update_slot(*slot, frame)) {
            draw_placeholder(output_width, output_height);
            return false;
        }
    }
    if (active_media_path == SessionMediaPath::Native && slot->imported_texture) {
        draw_native(*slot, output_width, output_height);
    } else {
        draw_cpu(*slot, output_width, output_height, frame->color_range == "full", frame->rotation_degrees);
    }
    return true;
}

void Renderer::destroy_slot(TextureSlot &slot)
{
    const bool has_imported_texture = static_cast<bool>(slot.imported_texture);
    slot.imported_texture.reset();
    if (!has_imported_texture && slot.texture) {
        gs_texture_destroy(slot.texture);
    }
    if (!has_imported_texture && slot.uv_texture) {
        gs_texture_destroy(slot.uv_texture);
    }
    slot = TextureSlot{};
}

void Renderer::reset()
{
    if (!graphics_resources_ready_) {
        return;
    }
    for (TextureSlot &slot : slots_) {
        destroy_slot(slot);
    }
    if (placeholder_) {
        gs_texture_destroy(placeholder_);
        placeholder_ = nullptr;
    }
    if (nv12_effect_) {
        gs_effect_destroy(nv12_effect_);
        nv12_effect_ = nullptr;
    }
    if (importer_) {
        importer_->reset();
    }
    graphics_resources_ready_ = false;
    active_media_path_.store(SessionMediaPath::Unselected);
    failed_generation_.store(kInactiveStreamGeneration);
    std::lock_guard<std::mutex> lock(render_mode_mutex_);
    active_render_mode_ = "placeholder";
}

} // namespace cambridge
