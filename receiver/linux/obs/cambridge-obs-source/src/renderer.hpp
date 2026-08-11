#pragma once

#include "frame.hpp"
#include "protocol_contract.generated.hpp"

#include <atomic>
#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

extern "C" {
#include <obs/obs-module.h>
}

namespace cambridge {

struct RendererConfig {
    bool transparent_placeholder = false;
};

class Renderer {
public:
    using EventCallback = std::function<void(const std::string &)>;
    using HardwareFallbackCallback = std::function<void()>;

    explicit Renderer(RendererConfig config, EventCallback on_event, HardwareFallbackCallback on_hardware_fallback);
    ~Renderer();

    Renderer(const Renderer &) = delete;
    Renderer &operator=(const Renderer &) = delete;

    bool render(const VideoFramePtr &frame, std::uint32_t output_width, std::uint32_t output_height);
    void reset();

    [[nodiscard]] std::uint64_t import_failures() const { return import_failures_.load(); }
    [[nodiscard]] std::uint64_t gpu_copies() const { return gpu_copies_.load(); }
    [[nodiscard]] std::uint64_t cpu_uploads() const { return cpu_uploads_.load(); }
    [[nodiscard]] std::string render_mode() const;

private:
    struct TextureSlot {
        gs_texture_t *texture = nullptr;
        gs_texture_t *uv_texture = nullptr;
        VideoFramePtr frame;
        std::uint64_t generation = 0;
    };

    void ensure_graphics_resources();
    bool update_slot(TextureSlot &slot, const VideoFramePtr &frame);
    bool update_cpu_slot(TextureSlot &slot, const VideoFramePtr &frame);
    bool update_dmabuf_slot(TextureSlot &slot, const VideoFramePtr &frame);
    void draw_placeholder(std::uint32_t output_width, std::uint32_t output_height);
    void draw_cpu(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height,
                  bool full_range, std::uint32_t rotation_degrees);
    void draw_dmabuf(const TextureSlot &slot, std::uint32_t output_width, std::uint32_t output_height);
    void destroy_slot(TextureSlot &slot);
    void report(const std::string &event);

    RendererConfig config_;
    EventCallback on_event_;
    HardwareFallbackCallback on_hardware_fallback_;
    std::array<TextureSlot, contract::kTexturePoolSlots> slots_{};
    std::size_t next_slot_ = 0;
    gs_texture_t *placeholder_ = nullptr;
    gs_effect_t *nv12_effect_ = nullptr;
    bool graphics_resources_ready_ = false;
    bool dma_buf_capabilities_checked_ = false;
    bool dma_buf_supported_ = false;
    bool hardware_fallback_requested_ = false;
    bool dma_buf_layout_reported_ = false;
    std::string active_render_mode_ = "placeholder";
    std::atomic<std::uint64_t> import_failures_{0};
    std::atomic<std::uint64_t> gpu_copies_{0};
    std::atomic<std::uint64_t> cpu_uploads_{0};
};

} // namespace cambridge
