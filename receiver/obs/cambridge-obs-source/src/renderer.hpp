#pragma once

#include "frame.hpp"
#include "media_path.hpp"
#include "platform/interfaces/native_frame_importer.hpp"
#include "protocol_contract.generated.hpp"

#include <atomic>
#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>

extern "C" {
#if defined(__APPLE__)
#include <obs-module.h>
#else
#include <obs/obs-module.h>
#endif
}

namespace cambridge {

struct RendererConfig {
    bool transparent_placeholder = false;
};

class Renderer {
public:
    using EventCallback = std::function<void(const std::string &)>;

    explicit Renderer(RendererConfig config, std::unique_ptr<NativeFrameImporter> importer,
                      EventCallback on_event, MediaPathFailureCallback on_failure);
    ~Renderer();

    Renderer(const Renderer &) = delete;
    Renderer &operator=(const Renderer &) = delete;

    bool render(const VideoFramePtr &frame, std::uint32_t output_width, std::uint32_t output_height);
    NativeSetupResult prepare_native_session(std::uint32_t maximum_width, std::uint32_t maximum_height);
    void discard_prepared_native_session();
    void activate_session_media_path(SessionMediaPath path);
    void end_session();
    void reset();

    [[nodiscard]] std::uint64_t import_failures() const { return import_failures_.load(); }
    [[nodiscard]] std::uint64_t gpu_copies() const { return gpu_copies_.load(); }
    [[nodiscard]] std::uint64_t cpu_uploads() const { return cpu_uploads_.load(); }
    [[nodiscard]] std::string render_mode() const;

private:
    struct TextureSlot {
        gs_texture_t *texture = nullptr;
        gs_texture_t *uv_texture = nullptr;
        std::unique_ptr<ImportedNativeTexture> imported_texture;
        VideoFramePtr frame;
        std::uint64_t generation = 0;
    };

    void ensure_graphics_resources();
    bool update_slot(TextureSlot &slot, const VideoFramePtr &frame);
    bool update_cpu_slot(TextureSlot &slot, const VideoFramePtr &frame);
    bool update_native_slot(TextureSlot &slot, const VideoFramePtr &frame);
    void draw_placeholder(std::uint32_t output_width, std::uint32_t output_height);
    void draw_cpu(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height,
                  bool full_range, std::uint32_t rotation_degrees);
    void draw_bgra(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height,
                   std::uint32_t rotation_degrees);
    void draw_native(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height);
    void destroy_slot(TextureSlot &slot);
    void fail(const VideoFramePtr &frame, MediaPathFailureCode code, const std::string &detail);
    void report(const std::string &event);

    RendererConfig config_;
    std::unique_ptr<NativeFrameImporter> importer_;
    EventCallback on_event_;
    MediaPathFailureCallback on_failure_;
    std::array<TextureSlot, contract::kTexturePoolSlots> slots_{};
    std::size_t next_slot_ = 0;
    gs_texture_t *placeholder_ = nullptr;
    gs_effect_t *nv12_effect_ = nullptr;
    bool graphics_resources_ready_ = false;
    std::atomic<SessionMediaPath> active_media_path_{SessionMediaPath::Unselected};
    std::atomic<std::uint64_t> failed_generation_{kInactiveStreamGeneration};
    mutable std::mutex render_mode_mutex_;
    std::string active_render_mode_ = "placeholder";
    std::atomic<std::uint64_t> import_failures_{0};
    std::atomic<std::uint64_t> gpu_copies_{0};
    std::atomic<std::uint64_t> cpu_uploads_{0};
};

} // namespace cambridge
