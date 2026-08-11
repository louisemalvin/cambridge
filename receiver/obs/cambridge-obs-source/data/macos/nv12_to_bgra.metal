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

float3 convert_bt709(float y, float2 chroma)
{
    return float3(
        y + 1.5748 * chroma.y,
        y - 0.1873 * chroma.x - 0.4681 * chroma.y,
        y + 1.8556 * chroma.x);
}

float3 convert_bt601(float y, float2 chroma)
{
    return float3(
        y + 1.4020 * chroma.y,
        y - 0.3441 * chroma.x - 0.7141 * chroma.y,
        y + 1.7720 * chroma.x);
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
    if (parameters.full_range == 0) {
        y = (y - (16.0 / 255.0)) * 1.16438356;
    }
    float2 centered_chroma = chroma.read(position / kNv12Subsampling).rg - float2(0.5, 0.5);
    float3 rgb;
    if (parameters.color_matrix == kBt709Matrix) {
        rgb = convert_bt709(y, centered_chroma);
    } else if (parameters.color_matrix == kBt601Matrix) {
        rgb = convert_bt601(y, centered_chroma);
    } else {
        return;
    }
    destination.write(float4(clamp(rgb.b, 0.0, 1.0), clamp(rgb.g, 0.0, 1.0),
                             clamp(rgb.r, 0.0, 1.0), 1.0), position);
}
