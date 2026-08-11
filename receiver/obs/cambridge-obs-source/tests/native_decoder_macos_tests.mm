#include "../src/platform/interfaces/native_decoder_adapter.hpp"
#include "../src/platform/macos/native_frame_macos.hpp"
#include "../src/protocol_contract.generated.hpp"

#import <CoreVideo/CoreVideo.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
}

#include <cstdlib>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#ifndef CAMBRIDGE_NATIVE_TEST_SAMPLE_PATH
#error "CAMBRIDGE_NATIVE_TEST_SAMPLE_PATH must be provided by CMake"
#endif

namespace {

constexpr std::size_t kMaximumDecodedFramesToInspect = 4;
constexpr std::size_t kFirstFrameIndex = 0;

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

std::vector<std::uint8_t> read_bounded_sample()
{
    std::ifstream sample(CAMBRIDGE_NATIVE_TEST_SAMPLE_PATH, std::ios::binary);
    require(sample.good());
    sample.seekg(0, std::ios::end);
    const std::streamoff sample_size = sample.tellg();
    require(sample_size > 0);
    require(static_cast<std::uintmax_t>(sample_size) <=
            cambridge::contract::kMaximumAccessUnitBytes);
    sample.seekg(0, std::ios::beg);
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(sample_size));
    sample.read(reinterpret_cast<char *>(bytes.data()), static_cast<std::streamsize>(sample_size));
    require(sample.good() || sample.eof());
    return bytes;
}

enum AVPixelFormat choose_native_format(AVCodecContext *context,
                                        const enum AVPixelFormat *formats)
{
    auto *adapter = static_cast<cambridge::NativeDecoderAdapter *>(context->opaque);
    return adapter ? adapter->choose_pixel_format(formats) : AV_PIX_FMT_NONE;
}

void test_bounded_fixture_decodes_to_retained_pixel_buffer()
{
    const std::vector<std::uint8_t> sample = read_bounded_sample();
    const auto decoder = avcodec_find_decoder(AV_CODEC_ID_H264);
    require(decoder != nullptr);
    AVCodecContext *codec_context = avcodec_alloc_context3(decoder);
    require(codec_context != nullptr);
    codec_context->width = CAMBRIDGE_NATIVE_TEST_WIDTH;
    codec_context->height = CAMBRIDGE_NATIVE_TEST_HEIGHT;

    auto adapter = cambridge::create_native_decoder_adapter();
    require(adapter != nullptr);
    codec_context->opaque = adapter.get();
    codec_context->get_format = &choose_native_format;
    const cambridge::NativeSetupResult setup = adapter->configure(
        *codec_context,
        cambridge::NativeDecoderConfig{CAMBRIDGE_NATIVE_TEST_WIDTH,
                                       CAMBRIDGE_NATIVE_TEST_HEIGHT,
                                       {}});
    require(setup.status == cambridge::NativeSetupStatus::Ready);
    require(avcodec_open2(codec_context, decoder, nullptr) >= 0);

    AVPacket *packet = av_packet_alloc();
    require(packet != nullptr);
    require(av_new_packet(packet, static_cast<int>(sample.size())) >= 0);
    std::memcpy(packet->data, sample.data(), sample.size());
    require(avcodec_send_packet(codec_context, packet) >= 0);

    AVFrame *decoded = av_frame_alloc();
    require(decoded != nullptr);
    cambridge::NativeFramePtr exported;
    for (std::size_t frame_index = kFirstFrameIndex;
         frame_index < kMaximumDecodedFramesToInspect && !exported; ++frame_index) {
        const int receive_result = avcodec_receive_frame(codec_context, decoded);
        if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
            break;
        }
        require(receive_result >= 0);
        std::string error;
        exported = adapter->export_frame(*decoded, error);
        require(exported != nullptr);
        const auto *native_frame =
            dynamic_cast<const cambridge::MacosNativeFrame *>(exported.get());
        require(native_frame != nullptr);
        const CVPixelBufferRef pixel_buffer = native_frame->pixel_buffer();
        require(pixel_buffer != nullptr);
        require(CVPixelBufferGetWidth(pixel_buffer) == CAMBRIDGE_NATIVE_TEST_WIDTH);
        require(CVPixelBufferGetHeight(pixel_buffer) == CAMBRIDGE_NATIVE_TEST_HEIGHT);
        require(CVPixelBufferGetIOSurface(pixel_buffer) != nullptr);
        const OSType pixel_format = CVPixelBufferGetPixelFormatType(pixel_buffer);
        require(pixel_format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
                pixel_format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange);
        require(native_frame->color_matrix() == cambridge::MacosColorMatrix::Bt709);
        const cambridge::MacosColorRange expected_range =
            pixel_format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                ? cambridge::MacosColorRange::Full
                : cambridge::MacosColorRange::Limited;
        require(native_frame->color_range() == expected_range);
        av_frame_unref(decoded);
        require(CVPixelBufferGetWidth(native_frame->pixel_buffer()) ==
                CAMBRIDGE_NATIVE_TEST_WIDTH);
    }
    require(exported != nullptr);

    av_frame_free(&decoded);
    av_packet_free(&packet);
    adapter->reset();
    avcodec_free_context(&codec_context);
}

} // namespace

int main()
{
    test_bounded_fixture_decodes_to_retained_pixel_buffer();
    return 0;
}
