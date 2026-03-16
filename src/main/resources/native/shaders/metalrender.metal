#include <metal_stdlib>
using namespace metal;

struct SodiumVertex {
    uint posHi;
    uint posLo;
    uint color;
    uint texture;
    uint lightData;
};

float3 decodeSodiumPosition(uint posHi, uint posLo) {
    uint xHi = (posHi >>  0) & 0x3FF;
    uint yHi = (posHi >> 10) & 0x3FF;
    uint zHi = (posHi >> 20) & 0x3FF;
    uint xLo = (posLo >>  0) & 0x3FF;
    uint yLo = (posLo >> 10) & 0x3FF;
    uint zLo = (posLo >> 20) & 0x3FF;
    float x = float((xHi << 10) | xLo) / 1048576.0 * 32.0 - 8.0;
    float y = float((yHi << 10) | yLo) / 1048576.0 * 32.0 - 8.0;
    float z = float((zHi << 10) | zLo) / 1048576.0 * 32.0 - 8.0;
    return float3(x, y, z);
}

float4 decodeSodiumColor(uint c) {
    float a = float((c >> 24) & 0xFF) / 255.0;
    float r = float((c >> 16) & 0xFF) / 255.0;
    float g = float((c >>  8) & 0xFF) / 255.0;
    float b = float((c >>  0) & 0xFF) / 255.0;
    return float4(r, g, b, a);
}


float2 decodeSodiumTexCoord(uint tex) {
    float u = float(tex & 0x7FFF) / 32768.0;
    float v = float((tex >> 16) & 0x7FFF) / 32768.0;
    return float2(u, v);
}


float2 decodeSodiumLight(uint lightData) {
    uint light = lightData & 0xFFFF;
    float blockLight = float(light & 0xFF) / 256.0;
    float skyLight   = float((light >> 8) & 0xFF) / 256.0;
    return float2(blockLight, skyLight);
}



struct SimpleVertexOut {
    float4 position  [[position]];
    float2 texCoord;
    float4 color;
    float2 lightUV;
};

vertex SimpleVertexOut vertex_terrain(
    device const SodiumVertex* vertices       [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& cameraPosition           [[buffer(3)]],
    constant float4& chunkOffset              [[buffer(4)]],
    uint vid [[vertex_id]]
) {
    SodiumVertex v = vertices[vid];
    SimpleVertexOut out;
    float3 localPos = decodeSodiumPosition(v.posHi, v.posLo);
    float3 worldPos = localPos + chunkOffset.xyz;

    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = decodeSodiumTexCoord(v.texture);
    out.color    = decodeSodiumColor(v.color);
    out.lightUV  = decodeSodiumLight(v.lightData);

    return out;
}
fragment float4 fragment_terrain(
    SimpleVertexOut in [[stage_in]],
    texture2d<float> blockAtlas  [[texture(0)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);


    bool forceOpaque = (in.color.a < 0.998);
    if (forceOpaque) {
        if (texColor.a < 0.1) {

            texColor = float4(0.05, 0.05, 0.05, 1.0);
        } else {
            texColor.a = 1.0;
        }
    } else {

        if (texColor.a < 0.1) discard_fragment();
    }

    float4 tinted = texColor * in.color;
    tinted.a = forceOpaque ? 1.0 : tinted.a;


    float light = max(max(in.lightUV.x, in.lightUV.y), 0.1);
    tinted.rgb *= light;

    return float4(tinted.rgb, tinted.a);
}
struct InhouseTerrainVertex {
    packed_short3 position;
    packed_ushort2 texCoord;
    packed_uchar4 color;
    uchar packedLight;
    uchar normalIndex;
};


vertex SimpleVertexOut vertex_terrain_inhouse(
    device const InhouseTerrainVertex* vertices   [[buffer(0)]],
    constant float4x4& projectionMatrix           [[buffer(1)]],
    constant float4x4& modelViewMatrix            [[buffer(2)]],
    constant float4& cameraPosition               [[buffer(3)]],
    constant float4& chunkOffset                  [[buffer(4)]],
    uint vid [[vertex_id]]
) {
    InhouseTerrainVertex v = vertices[vid];
    SimpleVertexOut out;

    float3 localPos = float3(short3(v.position)) / 256.0;
    float3 worldPos = localPos + chunkOffset.xyz;

    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = float2(v.texCoord) / 65535.0;
    out.color    = float4(v.color) / 255.0;
    float lightVal = float(v.packedLight) / 255.0;
    out.lightUV  = float2(lightVal, lightVal);

    return out;
}



struct DebugVertexOut {
    float4 position [[position]];
    float4 color;
};
vertex DebugVertexOut vertex_debug(
    device const packed_float3* positions     [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& debugColor               [[buffer(5)]],
    uint vid [[vertex_id]]
) {
    DebugVertexOut out;
    float4 viewPos = modelViewMatrix * float4(float3(positions[vid]), 1.0);
    out.position = projectionMatrix * viewPos;
    out.color = debugColor;
    return out;
}
fragment float4 fragment_debug(DebugVertexOut in [[stage_in]]) {
    return in.color;
}
