#include <metal_stdlib>

using namespace metal;

struct ConversionParameters {
    uint width;
    uint height;
    uint color_matrix;
    uint full_range;
};

constexpr uint2 kNv12Subsampling = uint2(2, 2);
constexpr uint kBt709Matrix = 0;
constexpr uint kBt601Matrix = 1;
constexpr uint kLimitedRange = 0;
constexpr float kVideoRangeLumaOffset = 16.0 / 255.0;
constexpr float kVideoRangeLumaScale = 1.16438356;
constexpr float kChromaCenter = 0.5;
constexpr float kColorMinimum = 0.0;
constexpr float kColorMaximum = 1.0;
constexpr float kOpaqueAlpha = 1.0;
constexpr float kBt709RedCoefficient = 1.5748;
constexpr float kBt709GreenBlueCoefficient = 0.1873;
constexpr float kBt709GreenRedCoefficient = 0.4681;
constexpr float kBt709BlueCoefficient = 1.8556;
constexpr float kBt601RedCoefficient = 1.4020;
constexpr float kBt601GreenBlueCoefficient = 0.3441;
constexpr float kBt601GreenRedCoefficient = 0.7141;
constexpr float kBt601BlueCoefficient = 1.7720;

float3 convert_bt709(float y, float2 chroma)
{
    return float3(
        y + kBt709RedCoefficient * chroma.y,
        y - kBt709GreenBlueCoefficient * chroma.x - kBt709GreenRedCoefficient * chroma.y,
        y + kBt709BlueCoefficient * chroma.x);
}

float3 convert_bt601(float y, float2 chroma)
{
    return float3(
        y + kBt601RedCoefficient * chroma.y,
        y - kBt601GreenBlueCoefficient * chroma.x - kBt601GreenRedCoefficient * chroma.y,
        y + kBt601BlueCoefficient * chroma.x);
}

kernel void nv12_to_bgra(
    texture2d<float, access::read> luma [[texture(0)]],
    texture2d<float, access::read> chroma [[texture(1)]],
    texture2d<float, access::write> destination [[texture(2)]],
    constant ConversionParameters &parameters [[buffer(0)]],
    uint2 position [[thread_position_in_grid]])
{
    if (position.x >= parameters.width || position.y >= parameters.height) {
        return;
    }

    float y = luma.read(position).r;
    if (parameters.full_range == kLimitedRange) {
        y = (y - kVideoRangeLumaOffset) * kVideoRangeLumaScale;
    }
    float2 centered_chroma =
        chroma.read(position / kNv12Subsampling).rg - float2(kChromaCenter, kChromaCenter);
    float3 rgb;
    if (parameters.color_matrix == kBt709Matrix) {
        rgb = convert_bt709(y, centered_chroma);
    } else if (parameters.color_matrix == kBt601Matrix) {
        rgb = convert_bt601(y, centered_chroma);
    } else {
        return;
    }
    destination.write(float4(clamp(rgb.b, kColorMinimum, kColorMaximum),
                             clamp(rgb.g, kColorMinimum, kColorMaximum),
                             clamp(rgb.r, kColorMinimum, kColorMaximum), kOpaqueAlpha), position);
}
