#pragma once

#include "../../media_path.hpp"
#include "native_frame.hpp"

#include <cstdint>
#include <memory>
#include <string>

extern "C" {
#include <obs/graphics/graphics.h>
}

namespace cambridge {

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

    virtual NativeSetupResult prepare(std::uint32_t maximum_width,
                                      std::uint32_t maximum_height) = 0;
    virtual NativeImportResult import_frame(const NativeFramePtr &frame,
                                            std::uint64_t frame_generation) = 0;
    virtual void reset() = 0;
};

std::unique_ptr<NativeFrameImporter> create_native_frame_importer();

} // namespace cambridge
