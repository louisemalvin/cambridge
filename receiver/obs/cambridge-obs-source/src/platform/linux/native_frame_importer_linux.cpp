#include "../interfaces/native_frame_importer.hpp"

#include "../linux/native_frame_linux.hpp"

#include <drm_fourcc.h>

extern "C" {
#include <libavutil/hwcontext_drm.h>
}

#include <array>
#include <limits>
#include <memory>

namespace cambridge {
namespace {

constexpr std::size_t kNv12PlaneCount = 2;
constexpr std::uint32_t kSingleDmabufPlaneCount = 1;
constexpr std::uint32_t kNv12ChromaColumnsDivisor = 2;
constexpr std::uint32_t kNv12ChromaRowsDivisor = 2;
constexpr std::uint32_t kUnsetDimension = 0;
constexpr std::uint64_t kNoGpuCopies = 0;
constexpr std::array<std::uint32_t, kNv12PlaneCount> kBytesPerTexel = {1, 2};

class LinuxImportedNativeTexture final : public ImportedNativeTexture {
public:
    LinuxImportedNativeTexture(gs_texture_t *primary, gs_texture_t *chroma)
        : primary_(primary), chroma_(chroma)
    {
    }

    ~LinuxImportedNativeTexture() override
    {
        if (primary_) {
            gs_texture_destroy(primary_);
        }
        if (chroma_) {
            gs_texture_destroy(chroma_);
        }
    }

    [[nodiscard]] ImportedTextureFormat format() const override { return ImportedTextureFormat::Nv12; }
    [[nodiscard]] gs_texture_t *primary_texture() const override { return primary_; }
    [[nodiscard]] gs_texture_t *chroma_texture() const override { return chroma_; }

private:
    gs_texture_t *primary_ = nullptr;
    gs_texture_t *chroma_ = nullptr;
};

void destroy_texture_pair(const std::array<gs_texture_t *, kNv12PlaneCount> &textures)
{
    for (gs_texture_t *texture : textures) {
        if (texture) {
            gs_texture_destroy(texture);
        }
    }
}

class LinuxNativeFrameImporter final : public NativeFrameImporter {
public:
    NativeSetupResult prepare(std::uint32_t maximum_width,
                              std::uint32_t maximum_height) override
    {
        prepared_ = false;
        if (maximum_width == kUnsetDimension || maximum_height == kUnsetDimension) {
            return {NativeSetupStatus::Failed, "native importer dimensions are empty"};
        }
        enum gs_dmabuf_flags flags = GS_DMABUF_FLAG_NONE;
        uint32_t *formats = nullptr;
        size_t format_count = 0;
        const bool supported = gs_query_dmabuf_capabilities(&flags, &formats, &format_count);
        if (formats) {
            bfree(formats);
        }
        if (!supported) {
            return {NativeSetupStatus::Unsupported, "OBS DMA-BUF import is unavailable"};
        }
        maximum_width_ = maximum_width;
        maximum_height_ = maximum_height;
        prepared_ = true;
        return {NativeSetupStatus::Ready, {}};
    }

    NativeImportResult import_frame(const NativeFramePtr &frame,
                                    std::uint64_t frame_generation) override
    {
        const auto *linux_frame = frame ? dynamic_cast<const LinuxNativeFrame *>(frame.get()) : nullptr;
        if (!prepared_ || !linux_frame || !linux_frame->drm_frame()) {
            return {nullptr, kNoGpuCopies,
                    "DMA-BUF frame is unavailable:generation=" + std::to_string(frame_generation)};
        }
        const AVFrame *drm_frame = linux_frame->drm_frame();
        if (drm_frame->width <= static_cast<int>(kUnsetDimension) ||
            drm_frame->height <= static_cast<int>(kUnsetDimension) ||
            drm_frame->width > static_cast<int>(maximum_width_) ||
            drm_frame->height > static_cast<int>(maximum_height_)) {
            return {nullptr, kNoGpuCopies,
                    "DMA-BUF frame exceeds prepared dimensions:generation=" +
                        std::to_string(frame_generation)};
        }
        auto *descriptor = reinterpret_cast<const AVDRMFrameDescriptor *>(drm_frame->data[0]);
        if (!descriptor || descriptor->nb_layers == 0 || descriptor->layers[0].nb_planes == 0) {
            return {nullptr, kNoGpuCopies, "DMA-BUF descriptor is empty"};
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
            return {nullptr, kNoGpuCopies, "DMA-BUF format is unsupported"};
        }

        constexpr std::array<std::uint32_t, kNv12PlaneCount> drm_formats = {
            DRM_FORMAT_R8, DRM_FORMAT_GR88};
        constexpr std::array<enum gs_color_format, kNv12PlaneCount> obs_formats = {GS_R8, GS_R8G8};
        const std::array<std::uint32_t, kNv12PlaneCount> plane_widths = {
            static_cast<std::uint32_t>(drm_frame->width),
            (static_cast<std::uint32_t>(drm_frame->width) + kNv12ChromaColumnsDivisor - 1U) /
                kNv12ChromaColumnsDivisor};
        const std::array<std::uint32_t, kNv12PlaneCount> plane_heights = {
            static_cast<std::uint32_t>(drm_frame->height),
            (static_cast<std::uint32_t>(drm_frame->height) + kNv12ChromaRowsDivisor - 1U) /
                kNv12ChromaRowsDivisor};
        std::array<int, kNv12PlaneCount> fds{};
        std::array<std::uint32_t, kNv12PlaneCount> strides{};
        std::array<std::uint32_t, kNv12PlaneCount> offsets{};
        std::array<std::uint64_t, kNv12PlaneCount> modifiers{};
        for (std::size_t plane = 0; plane < kNv12PlaneCount; ++plane) {
            const AVDRMPlaneDescriptor &plane_descriptor = *planes[plane];
            if (plane_descriptor.object_index < 0 || plane_descriptor.object_index >= descriptor->nb_objects) {
                return {nullptr, kNoGpuCopies, "DMA-BUF plane object is out of range"};
            }
            const AVDRMObjectDescriptor &object = descriptor->objects[plane_descriptor.object_index];
            const std::uint64_t plane_width = plane_widths[plane];
            const std::uint64_t plane_height = plane_heights[plane];
            const std::uint64_t bytes_per_texel = kBytesPerTexel[plane];
            if (plane_width > std::numeric_limits<std::uint32_t>::max() / bytes_per_texel) {
                return {nullptr, kNoGpuCopies, "DMA-BUF plane row size is invalid"};
            }
            const std::uint64_t minimum_pitch = plane_width * bytes_per_texel;
            if (object.fd < 0 || plane_descriptor.pitch <= 0 || plane_descriptor.offset < 0 ||
                object.format_modifier == DRM_FORMAT_MOD_INVALID ||
                static_cast<std::uint64_t>(plane_descriptor.pitch) < minimum_pitch ||
                static_cast<std::uint64_t>(plane_descriptor.pitch) > std::numeric_limits<std::uint32_t>::max() ||
                static_cast<std::uint64_t>(plane_descriptor.offset) > std::numeric_limits<std::uint32_t>::max()) {
                return {nullptr, kNoGpuCopies, "DMA-BUF plane metadata is invalid"};
            }
            const std::uint64_t pitch = static_cast<std::uint64_t>(plane_descriptor.pitch);
            const std::uint64_t offset = static_cast<std::uint64_t>(plane_descriptor.offset);
            const std::uint64_t rows_after_first = plane_height - 1U;
            if (rows_after_first >
                (std::numeric_limits<std::uint64_t>::max() - minimum_pitch) / pitch) {
                return {nullptr, kNoGpuCopies, "DMA-BUF plane extent overflows"};
            }
            const std::uint64_t plane_extent = rows_after_first * pitch + minimum_pitch;
            const std::uint64_t object_size = static_cast<std::uint64_t>(object.size);
            if (object_size == 0 || offset > object_size || plane_extent > object_size - offset) {
                return {nullptr, kNoGpuCopies, "DMA-BUF plane exceeds its object"};
            }
            fds[plane] = object.fd;
            strides[plane] = static_cast<std::uint32_t>(plane_descriptor.pitch);
            offsets[plane] = static_cast<std::uint32_t>(plane_descriptor.offset);
            modifiers[plane] = object.format_modifier;
        }

        std::array<gs_texture_t *, kNv12PlaneCount> textures{};
        for (std::size_t plane = 0; plane < kNv12PlaneCount; ++plane) {
            textures[plane] = gs_texture_create_from_dmabuf(
                plane_widths[plane], plane_heights[plane], drm_formats[plane], obs_formats[plane],
                kSingleDmabufPlaneCount, &fds[plane], &strides[plane], &offsets[plane], &modifiers[plane]);
            if (!textures[plane]) {
                destroy_texture_pair(textures);
                return {nullptr, kNoGpuCopies, "DMA-BUF texture import failed"};
            }
        }
        return {std::make_unique<LinuxImportedNativeTexture>(textures[0], textures[1]), kNoGpuCopies, {}};
    }

    void reset() override
    {
        prepared_ = false;
        maximum_width_ = kUnsetDimension;
        maximum_height_ = kUnsetDimension;
    }

private:
    std::uint32_t maximum_width_ = kUnsetDimension;
    std::uint32_t maximum_height_ = kUnsetDimension;
    bool prepared_ = false;
};

} // namespace

std::unique_ptr<NativeFrameImporter> create_native_frame_importer()
{
    return std::make_unique<LinuxNativeFrameImporter>();
}

} // namespace cambridge
