#include <metal_stdlib>

using namespace metal;

struct ConversionParameters {
    uint width;
    uint height;
    uint color_matrix;
    uint full_range;
};

constant uint2 kNv12Subsampling = uint2(2, 2);
constant uint kBt709Matrix = 0;
constant uint kBt601Matrix = 1;
constant uint kLimitedRange = 0;
constant uint kFullRange = 1;
constant float kVideoRangeLumaOffset = 16.0 / 255.0;
constant float kVideoRangeLumaScale = 1.16438356;
constant float kVideoRangeChromaCenter = 128.0 / 255.0;
constant float kFullRangeChromaCenter = 0.5;
constant float kColorMinimum = 0.0;
constant float kColorMaximum = 1.0;
constant float kOpaqueAlpha = 1.0;
constant float kBt709RedCoefficient = 1.5748;
constant float kBt709GreenBlueCoefficient = 0.1873;
constant float kBt709GreenRedCoefficient = 0.4681;
constant float kBt709BlueCoefficient = 1.8556;
constant float kBt601RedCoefficient = 1.4020;
constant float kBt601GreenBlueCoefficient = 0.3441;
constant float kBt601GreenRedCoefficient = 0.7141;
constant float kBt601BlueCoefficient = 1.7720;
constant float kBt709LimitedRedCoefficient = 1.79274107;
constant float kBt709LimitedGreenBlueCoefficient = 0.21324866;
constant float kBt709LimitedGreenRedCoefficient = 0.53290960;
constant float kBt709LimitedBlueCoefficient = 2.11240179;
constant float kBt601LimitedRedCoefficient = 1.59602679;
constant float kBt601LimitedGreenBlueCoefficient = 0.39176228;
constant float kBt601LimitedGreenRedCoefficient = 0.81296719;
constant float kBt601LimitedBlueCoefficient = 2.01723214;

float3 convert(float y, float2 chroma, float red_coefficient,
               float green_blue_coefficient, float green_red_coefficient,
               float blue_coefficient)
{
    return float3(
        y + red_coefficient * chroma.y,
        y - green_blue_coefficient * chroma.x - green_red_coefficient * chroma.y,
        y + blue_coefficient * chroma.x);
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
    float2 sampled_chroma = chroma.read(position / kNv12Subsampling).rg;
    float3 rgb;
    if (parameters.full_range == kLimitedRange) {
        y = (y - kVideoRangeLumaOffset) * kVideoRangeLumaScale;
        float2 centered_chroma = sampled_chroma -
            float2(kVideoRangeChromaCenter, kVideoRangeChromaCenter);
        if (parameters.color_matrix == kBt709Matrix) {
            rgb = convert(y, centered_chroma, kBt709LimitedRedCoefficient,
                          kBt709LimitedGreenBlueCoefficient,
                          kBt709LimitedGreenRedCoefficient,
                          kBt709LimitedBlueCoefficient);
        } else if (parameters.color_matrix == kBt601Matrix) {
            rgb = convert(y, centered_chroma, kBt601LimitedRedCoefficient,
                          kBt601LimitedGreenBlueCoefficient,
                          kBt601LimitedGreenRedCoefficient,
                          kBt601LimitedBlueCoefficient);
        } else {
            return;
        }
    } else if (parameters.full_range == kFullRange) {
        float2 centered_chroma = sampled_chroma -
            float2(kFullRangeChromaCenter, kFullRangeChromaCenter);
        if (parameters.color_matrix == kBt709Matrix) {
            rgb = convert(y, centered_chroma, kBt709RedCoefficient,
                          kBt709GreenBlueCoefficient, kBt709GreenRedCoefficient,
                          kBt709BlueCoefficient);
        } else if (parameters.color_matrix == kBt601Matrix) {
            rgb = convert(y, centered_chroma, kBt601RedCoefficient,
                          kBt601GreenBlueCoefficient, kBt601GreenRedCoefficient,
                          kBt601BlueCoefficient);
        } else {
            return;
        }
    } else {
        return;
    }
    destination.write(float4(clamp(rgb.b, kColorMinimum, kColorMaximum),
                             clamp(rgb.g, kColorMinimum, kColorMaximum),
                             clamp(rgb.r, kColorMinimum, kColorMaximum), kOpaqueAlpha), position);
}
