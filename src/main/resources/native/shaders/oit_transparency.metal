#include <metal_stdlib>
using namespace metal;
struct OitParams {
    float4x4 projection;
    float4x4 modelView;
    float4   cameraPos;
    float4   chunkOffset;
    float4   fogColor;
    float    fogStart;
    float    fogEnd;
    float    _pad0;
    float    _pad1;
};

struct TranslucentVertex {
    packed_float3 position;
    packed_short2 texCoord;
    packed_uchar4 color;
    packed_uchar4 normal;
    packed_short2 lightUV;
};

struct TranslucentVertexOut {
    float4 position [[position]];
    float2 texCoord;
    float4 color;
    float2 lightUV;
    float  viewDepth;
};
vertex TranslucentVertexOut vertex_oit(
    device const TranslucentVertex* vertices    [[buffer(0)]],
    constant OitParams&             params       [[buffer(1)]],
    uint vid [[vertex_id]]
) {
    TranslucentVertex v = vertices[vid];
    TranslucentVertexOut out;

    float3 worldPos = float3(v.position) + params.chunkOffset.xyz;
    float4 viewPos  = params.modelView * float4(worldPos, 1.0);
    out.position    = params.projection * viewPos;
    out.texCoord    = float2(v.texCoord) / 65535.0;
    out.color       = float4(v.color) / 255.0;
    out.lightUV     = float2(v.lightUV) / 256.0;
    out.viewDepth   = -viewPos.z;

    return out;
}

struct OitAccumOutput {
    float4 accumColor [[color(0)]];
    float  revealage  [[color(1)]];
};

fragment OitAccumOutput fragment_oit_accum(
    TranslucentVertexOut in [[stage_in]],
    texture2d<float> blockAtlas [[texture(0)]],
    texture2d<float> lightmap   [[texture(1)]],
    constant OitParams& params  [[buffer(1)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < 0.004) discard_fragment();
    float4 baseColor = texColor * in.color;
    float4 light = lightmap.sample(texSampler, in.lightUV);
    baseColor.rgb *= light.rgb;
    float fogFactor = saturate((params.fogEnd - in.viewDepth) /
                               max(params.fogEnd - params.fogStart, 0.001));
    baseColor.rgb = mix(params.fogColor.rgb, baseColor.rgb, fogFactor);
    float alpha = baseColor.a;
    float linearDepth = in.viewDepth;
    float weight = alpha * max(1e-2,
        min(3e3, 10.0 / (1e-5 + pow(linearDepth / 200.0, 4.0))));
    OitAccumOutput out;
    out.accumColor = float4(baseColor.rgb * alpha * weight, alpha * weight);
    out.revealage  = alpha;
    return out;
}
struct CompositeVertexOut {
    float4 position [[position]];
    float2 texCoord;
};
vertex CompositeVertexOut vertex_oit_composite(uint vid [[vertex_id]]) {
    float2 positions[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };
    float2 texcoords[3] = { float2(0, 1), float2(2, 1), float2(0, -1) };
    CompositeVertexOut out;
    out.position = float4(positions[vid], 0.0, 1.0);
    out.texCoord = texcoords[vid];
    return out;
}
fragment float4 fragment_oit_composite(
    CompositeVertexOut in [[stage_in]],
    texture2d<float> accumTexture     [[texture(0)]],
    texture2d<float> revealageTexture [[texture(1)]],
    texture2d<float> opaqueColor      [[texture(2)]]
) {
    uint2 coord = uint2(in.position.xy);
    float4 accum = accumTexture.read(coord);
    float revealage = revealageTexture.read(coord).r;
    float4 opaque = opaqueColor.read(coord);
    if (accum.a < 1e-4) return opaque;
    float3 avgColor = accum.rgb / max(accum.a, 1e-4);
    float3 result = avgColor * (1.0 - revealage) + opaque.rgb * revealage;
    return float4(result, 1.0);
}
