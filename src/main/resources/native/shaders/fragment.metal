#include <metal_stdlib>
using namespace metal;





struct SimpleVertexOut {
    float4 position  [[position]];
    float2 texCoord;
    float4 color;
    float2 lightUV;
};

fragment float4 fragment_terrain(
    SimpleVertexOut in [[stage_in]],
    texture2d<float> blockAtlas  [[texture(0)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);


    float4 baseColor = texColor * in.color;
    float lightFactor = clamp(in.lightUV.x * 0.8 + 0.2, 0.2, 1.0);
    baseColor.rgb *= lightFactor;

    return float4(baseColor.rgb, 1.0);
}







struct DebugVertexOut {
    float4 position [[position]];
    float4 color;
};

fragment float4 fragment_debug(DebugVertexOut in [[stage_in]]) {
    return in.color;
}
