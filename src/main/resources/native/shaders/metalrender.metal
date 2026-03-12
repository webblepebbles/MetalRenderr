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
    texture2d<float> blockAtlas  [[texture(0)]],
    texture2d<float> lightmap    [[texture(1)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < 0.004) discard_fragment();

    float4 baseColor = texColor * in.color;


    float4 light = lightmap.sample(texSampler, in.lightUV);
    baseColor.rgb *= light.rgb;

    return float4(baseColor.rgb, 1.0);
}











struct InhouseTerrainVertex {
    packed_float3 position;
    packed_short2 texCoord;
    packed_uchar4 color;
    packed_uchar4 normal;
    packed_short2 lightUV;
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

    float3 localPos = float3(v.position);
    float3 worldPos = localPos + chunkOffset.xyz;

    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = float2(v.texCoord) / 65535.0;
    out.color    = float4(v.color) / 255.0;
    out.lightUV  = float2(v.lightUV) / 256.0;

    return out;
}








struct EntityVertex {
    packed_float3 position;
    packed_short2 texCoord;
    packed_uchar4 color;
    packed_uchar4 normal;
    packed_short2 overlay;
    packed_short2 lightUV;
};

struct EntityVertexOut {
    float4 position [[position]];
    float2 texCoord;
    float4 color;
    float3 normal;
    float2 lightUV;
    float2 overlay;
};

vertex EntityVertexOut vertex_entity(
    device const EntityVertex* vertices       [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    uint vid [[vertex_id]]
) {
    EntityVertex v = vertices[vid];
    EntityVertexOut out;

    float3 pos      = float3(v.position);

    float4 viewPos  = modelViewMatrix * float4(pos, 1.0);
    out.position    = projectionMatrix * viewPos;
    out.texCoord    = float2(v.texCoord) / 32768.0;
    out.color       = float4(v.color) / 255.0;
    out.normal      = normalize(float3(v.normal.xyz) / 127.0 - 1.0);
    out.lightUV     = float2(v.lightUV) / 256.0;
    out.overlay     = float2(v.overlay.x, v.overlay.y);

    return out;
}

fragment float4 fragment_entity(
    EntityVertexOut in               [[stage_in]],
    texture2d<float> entityTex       [[texture(0)]]
    
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);

    float4 texColor = entityTex.sample(texSampler, in.texCoord);
    

    if (texColor.a < 0.1) {
        discard_fragment();
    }
    

    float4 finalColor = texColor * in.color;
    

    if (in.overlay.y < 5.0) {

        finalColor.rgb = mix(finalColor.rgb, float3(1.0, 0.0, 0.0), 0.6);
    }
    
    return finalColor;
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
