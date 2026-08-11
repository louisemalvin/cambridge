#include "../interfaces/native_frame_importer.hpp"

#include "native_frame_macos.hpp"
#include "../../protocol_contract.generated.hpp"

#include <obs-module.h>

#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>

namespace cambridge {
namespace {

inline constexpr std::size_t kNativeImportPoolSlots = contract::kTexturePoolSlots;
constexpr std::size_t kNv12PlaneCount = 2;
constexpr std::size_t kLumaPlaneIndex = 0;
constexpr std::size_t kChromaPlaneIndex = 1;
constexpr std::size_t kDestinationPlaneIndex = 0;
constexpr std::size_t kLumaTextureIndex = 0;
constexpr std::size_t kChromaTextureIndex = 1;
constexpr std::size_t kDestinationTextureIndex = 2;
constexpr std::size_t kParametersBufferIndex = 0;
constexpr std::uint32_t kColorMatrixBt709 = 0;
constexpr std::uint32_t kColorMatrixBt601 = 1;
constexpr std::uint32_t kFullRange = 1;
constexpr std::uint32_t kLimitedRange = 0;
constexpr std::uint32_t kNv12ChromaColumnsDivisor = 2;
constexpr std::uint32_t kNv12ChromaRowsDivisor = 2;
constexpr std::uint32_t kUnsetDimension = 0;
constexpr std::uint64_t kOneGpuCopy = 1;
constexpr std::uint64_t kNoGpuCopies = 0;
constexpr CFIndex kPixelBufferAttributeCapacity = 2;
constexpr CFIndex kEmptyDictionaryCapacity = 0;
constexpr NSUInteger kMetalTextureDepth = 1;
constexpr char kMetallibFileName[] = "nv12_to_bgra.metallib";
constexpr char kMetalFunctionName[] = "nv12_to_bgra";
constexpr OSType kNv12VideoRangePixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
constexpr OSType kNv12FullRangePixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;

struct ConversionParameters {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t color_matrix;
    std::uint32_t full_range;
};

struct ImportPoolSlot {
    CVPixelBufferRef destination = nullptr;
    IOSurfaceRef surface = nullptr;
    CVMetalTextureRef destination_texture = nullptr;
    bool leased = false;
};

class MacosNativeFrameImporter;

class MacosImportedNativeTexture final : public ImportedNativeTexture {
public:
    MacosImportedNativeTexture(MacosNativeFrameImporter *owner, std::size_t slot_index,
                               gs_texture_t *texture)
        : owner_(owner), slot_index_(slot_index), texture_(texture)
    {
    }

    ~MacosImportedNativeTexture() override;

    [[nodiscard]] ImportedTextureFormat format() const override { return ImportedTextureFormat::Bgra; }
    [[nodiscard]] gs_texture_t *primary_texture() const override { return texture_; }
    [[nodiscard]] gs_texture_t *chroma_texture() const override { return nullptr; }

private:
    MacosNativeFrameImporter *owner_ = nullptr;
    std::size_t slot_index_ = 0;
    gs_texture_t *texture_ = nullptr;
};

bool is_supported_nv12(CVPixelBufferRef pixel_buffer)
{
    if (!pixel_buffer || CVPixelBufferGetPlaneCount(pixel_buffer) != kNv12PlaneCount) {
        return false;
    }
    const OSType pixel_format = CVPixelBufferGetPixelFormatType(pixel_buffer);
    return pixel_format == kNv12VideoRangePixelFormat || pixel_format == kNv12FullRangePixelFormat;
}

std::string metal_error_detail(NSError *error)
{
    if (!error) {
        return {};
    }
    const char *description = [[error localizedDescription] UTF8String];
    return description ? description : "unknown Metal error";
}

#if defined(CAMBRIDGE_ENABLE_TEST_FAULTS)
bool native_fault_requested(const char *fault_name)
{
    const char *requested_fault = std::getenv("CAMBRIDGE_NATIVE_FAULT");
    return requested_fault && std::strcmp(requested_fault, fault_name) == 0;
}
#endif

class MacosNativeFrameImporter final : public NativeFrameImporter {
public:
    ~MacosNativeFrameImporter() override { reset(); }

    NativeSetupResult prepare(std::uint32_t maximum_width,
                              std::uint32_t maximum_height) override
    {
        reset();
        if (maximum_width == kUnsetDimension || maximum_height == kUnsetDimension) {
            return {NativeSetupStatus::Failed, "native importer dimensions are empty"};
        }

        device_ = MTLCreateSystemDefaultDevice();
        if (!device_) {
            return {NativeSetupStatus::Unsupported, "Metal device is unavailable"};
        }
        command_queue_ = [device_ newCommandQueue];
        if (!command_queue_) {
            return {NativeSetupStatus::Failed, "could not create the Metal command queue"};
        }
        const CVReturn cache_result = CVMetalTextureCacheCreate(
            kCFAllocatorDefault, nullptr, device_, nullptr, &texture_cache_);
        if (cache_result != kCVReturnSuccess || !texture_cache_) {
            reset();
            return {NativeSetupStatus::Failed, "could not create the Metal texture cache"};
        }

        char *metallib_path = obs_module_file(kMetallibFileName);
        if (!metallib_path) {
            reset();
            return {NativeSetupStatus::Failed, "compiled Metal library is unavailable"};
        }
        NSError *library_error = nil;
        NSString *library_path = [NSString stringWithUTF8String:metallib_path];
        id<MTLLibrary> library = [device_ newLibraryWithFile:library_path error:&library_error];
        bfree(metallib_path);
        if (!library) {
            reset();
            return {NativeSetupStatus::Failed,
                    "could not load the compiled Metal library:" + metal_error_detail(library_error)};
        }
        NSString *function_name = [NSString stringWithUTF8String:kMetalFunctionName];
        id<MTLFunction> function = [library newFunctionWithName:function_name];
        if (!function) {
            reset();
            return {NativeSetupStatus::Failed, "compiled Metal conversion function is unavailable"};
        }
        NSError *pipeline_error = nil;
        pipeline_ = [device_ newComputePipelineStateWithFunction:function error:&pipeline_error];
        if (!pipeline_) {
            reset();
            return {NativeSetupStatus::Failed,
                    "could not create the Metal conversion pipeline:" +
                        metal_error_detail(pipeline_error)};
        }

        maximum_width_ = maximum_width;
        maximum_height_ = maximum_height;
        for (ImportPoolSlot &slot : pool_) {
            const CVReturn slot_result = create_destination_slot(slot);
            if (slot_result != kCVReturnSuccess) {
                reset();
                return {NativeSetupStatus::Failed, "could not allocate the IOSurface conversion pool"};
            }
            gs_texture_t *probe = gs_texture_create_from_iosurface(
                reinterpret_cast<void *>(slot.surface));
            if (!probe) {
                reset();
                return {NativeSetupStatus::Unsupported,
                        "OBS IOSurface texture import is unavailable"};
            }
            gs_texture_destroy(probe);
        }
        prepared_ = true;
        return {NativeSetupStatus::Ready, {}};
    }

    NativeImportResult import_frame(const NativeFramePtr &frame,
                                    std::uint64_t frame_generation) override
    {
        const auto *mac_frame =
            frame ? dynamic_cast<const MacosNativeFrame *>(frame.get()) : nullptr;
        if (!prepared_ || !mac_frame || !mac_frame->pixel_buffer()) {
            return {nullptr, kNoGpuCopies,
                    "native_import:VideoToolbox frame is unavailable:generation=" +
                        std::to_string(frame_generation)};
        }
        CVPixelBufferRef source = mac_frame->pixel_buffer();
        const std::uint32_t source_width = static_cast<std::uint32_t>(CVPixelBufferGetWidth(source));
        const std::uint32_t source_height = static_cast<std::uint32_t>(CVPixelBufferGetHeight(source));
        if (source_width != maximum_width_ || source_height != maximum_height_) {
            return {nullptr, kNoGpuCopies,
                    "native_import:VideoToolbox frame dimensions do not match the session:generation=" +
                        std::to_string(frame_generation)};
        }
        if (!is_supported_nv12(source) || !CVPixelBufferGetIOSurface(source)) {
            return {nullptr, kNoGpuCopies,
                    "native_import:VideoToolbox frame layout is unsupported:generation=" +
                        std::to_string(frame_generation)};
        }
        const std::size_t luma_width = CVPixelBufferGetWidthOfPlane(source, kLumaPlaneIndex);
        const std::size_t luma_height = CVPixelBufferGetHeightOfPlane(source, kLumaPlaneIndex);
        const std::size_t chroma_width = CVPixelBufferGetWidthOfPlane(source, kChromaPlaneIndex);
        const std::size_t chroma_height = CVPixelBufferGetHeightOfPlane(source, kChromaPlaneIndex);
        const bool has_expected_plane_geometry =
            luma_width == source_width && luma_height == source_height &&
            source_width % kNv12ChromaColumnsDivisor == 0 &&
            source_height % kNv12ChromaRowsDivisor == 0 &&
            chroma_width == source_width / kNv12ChromaColumnsDivisor &&
            chroma_height == source_height / kNv12ChromaRowsDivisor;
        if (!has_expected_plane_geometry) {
            return {nullptr, kNoGpuCopies,
                    "native_import:VideoToolbox plane geometry is unsupported:generation=" +
                        std::to_string(frame_generation)};
        }

        std::uint32_t color_matrix = kColorMatrixBt709;
        if (mac_frame->color_matrix() == MacosColorMatrix::Bt709) {
            color_matrix = kColorMatrixBt709;
        } else if (mac_frame->color_matrix() == MacosColorMatrix::Bt601) {
            color_matrix = kColorMatrixBt601;
        } else {
            return {nullptr, kNoGpuCopies,
                    "native_conversion:unknown native color matrix:generation=" +
                        std::to_string(frame_generation)};
        }
        std::uint32_t full_range = kLimitedRange;
        if (mac_frame->color_range() == MacosColorRange::Full) {
            full_range = kFullRange;
        } else if (mac_frame->color_range() == MacosColorRange::Limited) {
            full_range = kLimitedRange;
        } else {
            return {nullptr, kNoGpuCopies,
                    "native_conversion:unknown native color range:generation=" +
                        std::to_string(frame_generation)};
        }

#if defined(CAMBRIDGE_ENABLE_TEST_FAULTS)
        if (native_fault_requested("conversion")) {
            return {nullptr, kNoGpuCopies,
                    "native_conversion:fault injected conversion failure:generation=" +
                        std::to_string(frame_generation)};
        }
        if (native_fault_requested("import")) {
            return {nullptr, kNoGpuCopies,
                    "native_import:fault injected import failure:generation=" +
                        std::to_string(frame_generation)};
        }
        if (native_fault_requested("pool")) {
            return {nullptr, kNoGpuCopies,
                    "native_pool_exhaustion:fault injected pool exhaustion:generation=" +
                        std::to_string(frame_generation)};
        }
#endif

        const std::size_t slot_index = find_free_slot();
        if (slot_index == kNativeImportPoolSlots) {
            return {nullptr, kNoGpuCopies,
                    "native_pool_exhaustion:no IOSurface conversion slot:generation=" +
                        std::to_string(frame_generation)};
        }
        ImportPoolSlot &slot = pool_[slot_index];
        slot.leased = true;
        CVMetalTextureRef source_luma = nullptr;
        CVMetalTextureRef source_chroma = nullptr;
        auto release_source_textures = [&] {
            if (source_luma) {
                CFRelease(source_luma);
                source_luma = nullptr;
            }
            if (source_chroma) {
                CFRelease(source_chroma);
                source_chroma = nullptr;
            }
        };

        const CVReturn luma_result = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, texture_cache_, source, nullptr, MTLPixelFormatR8Unorm,
            static_cast<std::size_t>(source_width), static_cast<std::size_t>(source_height),
            kLumaPlaneIndex, &source_luma);
        const CVReturn chroma_result = luma_result == kCVReturnSuccess
                                           ? CVMetalTextureCacheCreateTextureFromImage(
                                                 kCFAllocatorDefault, texture_cache_, source, nullptr,
                                                 MTLPixelFormatRG8Unorm, chroma_width, chroma_height,
                                                 kChromaPlaneIndex, &source_chroma)
                                           : luma_result;
        id<MTLTexture> luma_texture = source_luma ? CVMetalTextureGetTexture(source_luma) : nil;
        id<MTLTexture> chroma_texture =
            source_chroma ? CVMetalTextureGetTexture(source_chroma) : nil;
        id<MTLTexture> destination_texture =
            slot.destination_texture ? CVMetalTextureGetTexture(slot.destination_texture) : nil;
        if (chroma_result != kCVReturnSuccess || !luma_texture || !chroma_texture ||
            !destination_texture) {
            release_source_textures();
            slot.leased = false;
            return {nullptr, kNoGpuCopies,
                    "native_conversion:Metal plane texture creation failed:generation=" +
                        std::to_string(frame_generation)};
        }

        id<MTLCommandBuffer> command_buffer = [command_queue_ commandBuffer];
        id<MTLComputeCommandEncoder> encoder = command_buffer ? [command_buffer computeCommandEncoder] : nil;
        if (!encoder) {
            release_source_textures();
            slot.leased = false;
            return {nullptr, kNoGpuCopies,
                    "native_conversion:Metal command encoder creation failed:generation=" +
                        std::to_string(frame_generation)};
        }
        const ConversionParameters parameters{source_width, source_height, color_matrix, full_range};
        [encoder setTexture:luma_texture atIndex:kLumaTextureIndex];
        [encoder setTexture:chroma_texture atIndex:kChromaTextureIndex];
        [encoder setTexture:destination_texture atIndex:kDestinationTextureIndex];
        [encoder setBytes:&parameters length:sizeof(parameters) atIndex:kParametersBufferIndex];
        const NSUInteger thread_width = pipeline_.threadExecutionWidth;
        if (thread_width == 0) {
            [encoder endEncoding];
            release_source_textures();
            slot.leased = false;
            return {nullptr, kNoGpuCopies,
                    "native_conversion:Metal pipeline has no usable threadgroup:generation=" +
                        std::to_string(frame_generation)};
        }
        const NSUInteger thread_height = pipeline_.maxTotalThreadsPerThreadgroup / thread_width;
        if (thread_height == 0) {
            [encoder endEncoding];
            release_source_textures();
            slot.leased = false;
            return {nullptr, kNoGpuCopies,
                    "native_conversion:Metal pipeline has no usable threadgroup:generation=" +
                        std::to_string(frame_generation)};
        }
        const MTLSize grid = MTLSizeMake(static_cast<NSUInteger>(source_width),
                                         static_cast<NSUInteger>(source_height),
                                         kMetalTextureDepth);
        const MTLSize threads = MTLSizeMake(thread_width, thread_height, kMetalTextureDepth);
        [encoder dispatchThreads:grid threadsPerThreadgroup:threads];
        [encoder endEncoding];
        [command_buffer commit];
        [command_buffer waitUntilCompleted];
        const bool command_succeeded =
            command_buffer.status == MTLCommandBufferStatusCompleted;
        release_source_textures();
        if (!command_succeeded) {
            slot.leased = false;
            return {nullptr, kNoGpuCopies,
                    "native_conversion:Metal command buffer failed:generation=" +
                        std::to_string(frame_generation)};
        }

        gs_texture_t *texture = gs_texture_create_from_iosurface(
            reinterpret_cast<void *>(slot.surface));
        if (!texture) {
            slot.leased = false;
            return {nullptr, kNoGpuCopies,
                    "native_import:OBS IOSurface texture creation failed:generation=" +
                        std::to_string(frame_generation)};
        }
        return {std::make_unique<MacosImportedNativeTexture>(this, slot_index, texture),
                kOneGpuCopy, {}};
    }

    void reset() override
    {
        prepared_ = false;
        for (ImportPoolSlot &slot : pool_) {
            slot.leased = false;
            if (slot.destination_texture) {
                CFRelease(slot.destination_texture);
                slot.destination_texture = nullptr;
            }
            if (slot.destination) {
                CVPixelBufferRelease(slot.destination);
                slot.destination = nullptr;
            }
            slot.surface = nullptr;
        }
        if (texture_cache_) {
            CFRelease(texture_cache_);
            texture_cache_ = nullptr;
        }
        pipeline_ = nil;
        command_queue_ = nil;
        device_ = nil;
        maximum_width_ = kUnsetDimension;
        maximum_height_ = kUnsetDimension;
    }

    void release_slot(std::size_t slot_index)
    {
        if (slot_index < kNativeImportPoolSlots) {
            pool_[slot_index].leased = false;
        }
    }

private:
    CVReturn create_destination_slot(ImportPoolSlot &slot)
    {
        CFMutableDictionaryRef attributes = CFDictionaryCreateMutable(
            kCFAllocatorDefault, kPixelBufferAttributeCapacity, &kCFTypeDictionaryKeyCallBacks,
            &kCFTypeDictionaryValueCallBacks);
        CFMutableDictionaryRef iosurface_properties = CFDictionaryCreateMutable(
            kCFAllocatorDefault, kEmptyDictionaryCapacity, &kCFTypeDictionaryKeyCallBacks,
            &kCFTypeDictionaryValueCallBacks);
        if (!attributes || !iosurface_properties) {
            if (attributes) {
                CFRelease(attributes);
            }
            if (iosurface_properties) {
                CFRelease(iosurface_properties);
            }
            return kCVReturnError;
        }
        CFDictionarySetValue(attributes, kCVPixelBufferIOSurfacePropertiesKey, iosurface_properties);
        CFDictionarySetValue(attributes, kCVPixelBufferMetalCompatibilityKey, kCFBooleanTrue);
        const CVReturn buffer_result = CVPixelBufferCreate(
            kCFAllocatorDefault, maximum_width_, maximum_height_, kCVPixelFormatType_32BGRA,
            attributes, &slot.destination);
        CFRelease(iosurface_properties);
        CFRelease(attributes);
        if (buffer_result != kCVReturnSuccess || !slot.destination) {
            return buffer_result;
        }
        slot.surface = CVPixelBufferGetIOSurface(slot.destination);
        if (!slot.surface) {
            return kCVReturnError;
        }
        return CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, texture_cache_, slot.destination, nullptr, MTLPixelFormatBGRA8Unorm,
            static_cast<std::size_t>(maximum_width_), static_cast<std::size_t>(maximum_height_),
            kDestinationPlaneIndex, &slot.destination_texture);
    }

    std::size_t find_free_slot() const
    {
        for (std::size_t index = 0; index < kNativeImportPoolSlots; ++index) {
            if (!pool_[index].leased) {
                return index;
            }
        }
        return kNativeImportPoolSlots;
    }

    __strong id<MTLDevice> device_ = nil;
    __strong id<MTLCommandQueue> command_queue_ = nil;
    __strong id<MTLComputePipelineState> pipeline_ = nil;
    CVMetalTextureCacheRef texture_cache_ = nullptr;
    std::array<ImportPoolSlot, kNativeImportPoolSlots> pool_{};
    std::uint32_t maximum_width_ = kUnsetDimension;
    std::uint32_t maximum_height_ = kUnsetDimension;
    bool prepared_ = false;
};

MacosImportedNativeTexture::~MacosImportedNativeTexture()
{
    if (texture_) {
        gs_texture_destroy(texture_);
        texture_ = nullptr;
    }
    if (owner_) {
        owner_->release_slot(slot_index_);
        owner_ = nullptr;
    }
}

} // namespace

std::unique_ptr<NativeFrameImporter> create_native_frame_importer()
{
    return std::make_unique<MacosNativeFrameImporter>();
}

} // namespace cambridge
