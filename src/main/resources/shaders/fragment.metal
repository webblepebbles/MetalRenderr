#include <metal_stdlib>
using namespace metal;
struct SimpleVertexOut {
    float4 position  [[position]];
    float2 texCoord;
    float4 color;
    float2 lightUV;
    float  fogFactor;
};
fragment float4 fragment_terrain(
    SimpleVertexOut in [[stage_in]],
    texture2d<float> blockAtlas  [[texture(0)]],
    texture2d<float> lightmap    [[texture(1)]],
    constant float4& fogColor    [[buffer(6)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < 0.004) discard_fragment();
    float4 baseColor = texColor * in.color;
    float4 light = lightmap.sample(texSampler, in.lightUV);
    baseColor.rgb *= light.rgb;
    baseColor.rgb = mix(fogColor.rgb, baseColor.rgb, in.fogFactor);
    return float4(baseColor.rgb, 1.0);
}
fragment float4 fragment_terrain_cutout(
    SimpleVertexOut in [[stage_in]],
    texture2d<float> blockAtlas  [[texture(0)]],
    texture2d<float> lightmap    [[texture(1)]],
    constant float4& fogColor    [[buffer(6)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < 0.5) discard_fragment();
    float4 baseColor = texColor * in.color;
    float4 light = lightmap.sample(texSampler, in.lightUV);
    baseColor.rgb *= light.rgb;
    baseColor.rgb = mix(fogColor.rgb, baseColor.rgb, in.fogFactor);
    return float4(baseColor.rgb, 1.0);
}
fragment float4 fragment_terrain_translucent(
    SimpleVertexOut in [[stage_in]],
    texture2d<float> blockAtlas  [[texture(0)]],
    texture2d<float> lightmap    [[texture(1)]],
    constant float4& fogColor    [[buffer(6)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < 0.004) discard_fragment();
    float4 baseColor = texColor * in.color;
    float4 light = lightmap.sample(texSampler, in.lightUV);
    baseColor.rgb *= light.rgb;
    baseColor.rgb = mix(fogColor.rgb, baseColor.rgb, in.fogFactor);
    return baseColor;
}
struct DebugVertexOut {
    float4 position [[position]];
    float4 color;
};
fragment float4 fragment_debug(DebugVertexOut in [[stage_in]]) {
    return in.color;
}
struct BlitVertexOut {
    float4 position [[position]];
    float2 texCoord;
};
fragment float4 fragment_blit(
    BlitVertexOut in [[stage_in]],
    texture2d<float> srcTexture [[texture(0)]]
) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    return srcTexture.sample(s, in.texCoord);
}
