#include "renderer.hpp"

#include "protocol_contract.hpp"

#include <drm_fourcc.h>

#include <libavutil/hwcontext_drm.h>

#include <obs/obs.h>

#include <array>
#include <cstring>
#include <limits>
#include <time.h>

namespace direct_webcam {
namespace {

constexpr std::size_t kTextureSlotCount = contract::kTexturePoolSlots;
constexpr std::uint32_t kPlaceholderWidth = 2;
constexpr std::uint32_t kPlaceholderHeight = 2;
constexpr std::uint32_t kPlaceholderTextureLevels = 1;
constexpr std::uint32_t kNv12TextureLevels = 1;
constexpr std::uint32_t kNoTextureFlags = 0;
constexpr std::uint32_t kDynamicTextureFlags = GS_DYNAMIC;
constexpr std::uint32_t kNoFlip = 0;
constexpr std::uint32_t kSingleDmabufPlaneCount = 1;
constexpr std::uint32_t kNv12ChromaColumnsDivisor = 2;
constexpr std::uint32_t kNv12ChromaRowsDivisor = 2;
constexpr std::uint32_t kNv12PlaneCount = 2;
constexpr char kNv12EffectName[] = "direct-webcam-nv12.effect";
constexpr char kNv12Effect[] = R"(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform texture2d image1;
uniform bool full_range;
uniform int rotation_quarter_turn;

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
    vert_out.pos = mul(float4(vert_in.pos.xyz, 1.0), ViewProj);
    vert_out.uv = vert_in.uv;
    return vert_out;
}

float4 PSNv12(VertInOut vert_in) : TARGET
{
    float2 sample_uv = vert_in.uv;
    if (rotation_quarter_turn == 1) {
        sample_uv = float2(vert_in.uv.y, 1.0 - vert_in.uv.x);
    } else if (rotation_quarter_turn == 2) {
        sample_uv = float2(1.0 - vert_in.uv.x, 1.0 - vert_in.uv.y);
    } else if (rotation_quarter_turn == 3) {
        sample_uv = float2(1.0 - vert_in.uv.y, vert_in.uv.x);
    }
    float y = image.Sample(def_sampler, sample_uv).r;
    float2 uv = image1.Sample(def_sampler, sample_uv).rg;
    if (!full_range) {
        y = (y - (16.0 / 255.0)) * 1.16438356;
    }
    uv -= float2(0.5, 0.5);
    float3 rgb;
    rgb.r = y + 1.5748 * uv.y;
    rgb.g = y - 0.1873 * uv.x - 0.4681 * uv.y;
    rgb.b = y + 1.8556 * uv.x;
    return float4(rgb, 1.0);
}

technique Draw {
    pass {
        vertex_shader = VSDefault(vert_in);
        pixel_shader = PSNv12(vert_in);
    }
}
)";

constexpr char kOpaqueRenderMode[] = "DMA-BUF direct";
constexpr char kCpuRenderMode[] = "CPU NV12 upload";
constexpr std::uint32_t kQuarterTurnDegrees = 90;
constexpr std::uint32_t kQuarterTurnCount = 4;
constexpr std::uint64_t kNanosecondsPerSecond = 1'000'000'000ULL;

std::uint64_t monotonic_time_ns()
{
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<std::uint64_t>(time.tv_sec) * kNanosecondsPerSecond +
           static_cast<std::uint64_t>(time.tv_nsec);
}

} // namespace

Renderer::Renderer(RendererConfig config, EventCallback on_event, HardwareFallbackCallback on_hardware_fallback)
    : config_(config), on_event_(std::move(on_event)), on_hardware_fallback_(std::move(on_hardware_fallback))
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

bool Renderer::update_cpu_slot(TextureSlot &slot, const VideoFramePtr &frame)
{
    if (!frame || frame->nv12.empty() || !nv12_effect_) {
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
    const std::size_t y_bytes = static_cast<std::size_t>(frame->nv12_y_stride) * frame->height;
    const std::size_t uv_bytes = static_cast<std::size_t>(frame->nv12_uv_stride) *
                                 ((frame->height + kNv12ChromaRowsDivisor - 1U) /
                                  kNv12ChromaRowsDivisor);
    if (frame->nv12_y_stride == 0 || frame->nv12_uv_stride == 0 ||
        y_bytes > frame->nv12.size() || uv_bytes > frame->nv12.size() - y_bytes) {
        gs_texture_destroy(y_texture);
        gs_texture_destroy(uv_texture);
        return false;
    }
    gs_texture_set_image(y_texture, frame->nv12.data(), frame->nv12_y_stride, false);
    gs_texture_set_image(uv_texture, frame->nv12.data() + y_bytes, frame->nv12_uv_stride, false);
    destroy_slot(slot);
    slot.texture = y_texture;
    slot.uv_texture = uv_texture;
    slot.frame = frame;
    slot.generation = frame->frame_generation;
    cpu_uploads_.fetch_add(1);
    return true;
}

bool Renderer::update_dmabuf_slot(TextureSlot &slot, const VideoFramePtr &frame)
{
    if (!frame || !frame->drm_frame || !dma_buf_supported_) {
        report("dma_buf_frame_unavailable");
        return false;
    }
    auto *descriptor = reinterpret_cast<AVDRMFrameDescriptor *>(frame->drm_frame->data[0]);
    if (!descriptor || descriptor->nb_layers == 0 || descriptor->layers[0].nb_planes == 0) {
        report("dma_buf_descriptor_empty");
        return false;
    }
    std::array<const AVDRMPlaneDescriptor *, kNv12PlaneCount> planes{};
    if (descriptor->nb_layers == 1 && descriptor->layers[0].format == DRM_FORMAT_NV12 &&
        descriptor->layers[0].nb_planes == kNv12PlaneCount) {
        planes[0] = &descriptor->layers[0].planes[0];
        planes[1] = &descriptor->layers[0].planes[1];
    } else if (descriptor->nb_layers == kNv12PlaneCount && descriptor->layers[0].nb_planes == 1 &&
               descriptor->layers[1].nb_planes == 1 && descriptor->layers[0].format == DRM_FORMAT_R8 &&
               descriptor->layers[1].format == DRM_FORMAT_GR88) {
        planes[0] = &descriptor->layers[0].planes[0];
        planes[1] = &descriptor->layers[1].planes[0];
    } else {
        report("dma_buf_format_unsupported:layers=" + std::to_string(descriptor->nb_layers) +
               ":first_planes=" + std::to_string(descriptor->layers[0].nb_planes) +
               ":first_format=" + std::to_string(descriptor->layers[0].format));
        return false;
    }
    constexpr std::array<std::uint32_t, kNv12PlaneCount> drm_formats = {DRM_FORMAT_R8, DRM_FORMAT_GR88};
    constexpr std::array<enum gs_color_format, kNv12PlaneCount> obs_formats = {GS_R8, GS_R8G8};
    const std::array<std::uint32_t, kNv12PlaneCount> plane_widths = {
        frame->width, (frame->width + kNv12ChromaColumnsDivisor - 1U) / kNv12ChromaColumnsDivisor};
    const std::array<std::uint32_t, kNv12PlaneCount> plane_heights = {
        frame->height, (frame->height + kNv12ChromaRowsDivisor - 1U) / kNv12ChromaRowsDivisor};
    std::array<int, kNv12PlaneCount> fds{};
    std::array<std::uint32_t, kNv12PlaneCount> strides{};
    std::array<std::uint32_t, kNv12PlaneCount> offsets{};
    std::array<std::uint64_t, kNv12PlaneCount> modifiers{};
    for (std::size_t plane = 0; plane < kNv12PlaneCount; ++plane) {
        const AVDRMPlaneDescriptor &plane_descriptor = *planes[plane];
        if (plane_descriptor.object_index >= descriptor->nb_objects) {
            report("dma_buf_plane_object_out_of_range");
            return false;
        }
        const AVDRMObjectDescriptor &object = descriptor->objects[plane_descriptor.object_index];
        if (object.fd < 0 || plane_descriptor.pitch <= 0 || plane_descriptor.offset < 0 ||
            static_cast<std::uint64_t>(plane_descriptor.pitch) > std::numeric_limits<std::uint32_t>::max() ||
            static_cast<std::uint64_t>(plane_descriptor.offset) > std::numeric_limits<std::uint32_t>::max()) {
            report("dma_buf_plane_metadata_invalid");
            return false;
        }
        fds[plane] = object.fd;
        strides[plane] = static_cast<std::uint32_t>(plane_descriptor.pitch);
        offsets[plane] = static_cast<std::uint32_t>(plane_descriptor.offset);
        modifiers[plane] = object.format_modifier;
    }
    if (!dma_buf_layout_reported_) {
        report("dma_buf_layout:fourcc=" + std::to_string(descriptor->layers[0].format) +
               ":planes=" + std::to_string(descriptor->layers[0].nb_planes) +
               ":stride0=" + std::to_string(strides[0]) + ":stride1=" + std::to_string(strides[1]) +
               ":offset0=" + std::to_string(offsets[0]) + ":offset1=" + std::to_string(offsets[1]) +
               ":modifier0=" + std::to_string(modifiers[0]) + ":modifier1=" +
               std::to_string(modifiers[1]) + ":sync=implicit");
        dma_buf_layout_reported_ = true;
    }
    std::array<gs_texture_t *, kNv12PlaneCount> textures{};
    for (std::size_t plane = 0; plane < kNv12PlaneCount; ++plane) {
        textures[plane] = gs_texture_create_from_dmabuf(
            plane_widths[plane], plane_heights[plane], drm_formats[plane], obs_formats[plane],
            kSingleDmabufPlaneCount, &fds[plane], &strides[plane], &offsets[plane], &modifiers[plane]);
        if (!textures[plane]) {
            for (gs_texture_t *texture : textures) {
                if (texture) {
                    gs_texture_destroy(texture);
                }
            }
            import_failures_.fetch_add(1);
            report("dma_buf_texture_import_failed:format=" + std::to_string(drm_formats[plane]) +
                   ":modifier=" + std::to_string(modifiers[plane]));
            return false;
        }
    }
    destroy_slot(slot);
    slot.texture = textures[0];
    slot.uv_texture = textures[1];
    slot.frame = frame;
    slot.generation = frame->frame_generation;
    return true;
}

bool Renderer::update_slot(TextureSlot &slot, const VideoFramePtr &frame)
{
    if (frame->render_mode == RenderMode::HardwareDmaBuf) {
        if (hardware_fallback_requested_) {
            return false;
        }
        if (update_dmabuf_slot(slot, frame)) {
            if (active_render_mode_ != kOpaqueRenderMode) {
                active_render_mode_ = kOpaqueRenderMode;
                report("render_mode=dma_buf_direct");
            }
            return true;
        }
        if (!hardware_fallback_requested_) {
            hardware_fallback_requested_ = true;
            if (on_hardware_fallback_) {
                on_hardware_fallback_();
            }
        }
        return false;
    }
    if (update_cpu_slot(slot, frame)) {
        if (active_render_mode_ != kCpuRenderMode) {
            active_render_mode_ = kCpuRenderMode;
            report("render_mode=cpu_nv12_upload");
        }
        return true;
    }
    return false;
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

void Renderer::draw_dmabuf(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height)
{
    if (!slot.frame) {
        draw_placeholder(output_width, output_height);
        return;
    }
    draw_cpu(slot, output_width, output_height, slot.frame->color_range == "full",
             slot.frame->rotation_degrees);
}

bool Renderer::render(const VideoFramePtr &frame, std::uint32_t output_width, std::uint32_t output_height)
{
    ensure_graphics_resources();
    if (!dma_buf_capabilities_checked_) {
        enum gs_dmabuf_flags flags = GS_DMABUF_FLAG_NONE;
        uint32_t *formats = nullptr;
        size_t format_count = 0;
        dma_buf_supported_ = gs_query_dmabuf_capabilities(&flags, &formats, &format_count);
        if (formats) {
            bfree(formats);
        }
        dma_buf_capabilities_checked_ = true;
        report((dma_buf_supported_ ? "obs_dma_buf_capabilities_available" :
                                    "obs_dma_buf_capabilities_unavailable") +
               std::string(":flags=") + std::to_string(static_cast<std::uint32_t>(flags)) +
               ":format_count=" + std::to_string(format_count));
    }
    if (!frame || frame->frame_generation == 0) {
        draw_placeholder(output_width, output_height);
        return false;
    }
    const std::uint64_t now = monotonic_time_ns();
    if (frame->publish_time_ns == 0 || frame->stale_deadline_ns == 0 || now > frame->stale_deadline_ns) {
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
    if (frame->render_mode == RenderMode::HardwareDmaBuf && slot->texture) {
        draw_dmabuf(*slot, output_width, output_height);
    } else {
        draw_cpu(*slot, output_width, output_height, frame->color_range == "full", frame->rotation_degrees);
    }
    return true;
}

void Renderer::destroy_slot(TextureSlot &slot)
{
    if (slot.texture) {
        gs_texture_destroy(slot.texture);
    }
    if (slot.uv_texture) {
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
    graphics_resources_ready_ = false;
    dma_buf_capabilities_checked_ = false;
    hardware_fallback_requested_ = false;
    dma_buf_layout_reported_ = false;
}

} // namespace direct_webcam
