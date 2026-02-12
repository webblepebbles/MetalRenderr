#include <metal_stdlib>
using namespace metal;

struct OITDrawUniforms {
    float originX;
    float originY;
    float originZ;
    float renderLayer;
};

struct OITFrameUniforms {
    float4x4 viewProj;
    float4 cameraPos;
    half4 fogColor;
    half4 fogParams;
    half4 lightParams;
};

struct OITVertexOut {
    float4 position [[position]];
    half2 texCoord;
    half4 color;
    half2 light;
    half distance;
};
struct OITAccumulation {
    half4 accumColor [[color(0), raster_order_group(0)]];  
    half  accumAlpha [[color(1), raster_order_group(0)]];  
};
constant half MODEL_ORIGIN_OIT = 8.0h;
constant half MODEL_RANGE_OIT = 32.0h;
constant uint POSITION_MAX_VALUE_OIT = 1u << 20u;
constant uint TEXTURE_MAX_VALUE_OIT = 1u << 15u;

float3 decodePositionOIT(uint posHi, uint posLo, float3 origin) {
    uint xHi = (posHi >> 0u) & 0x3FFu;
    uint yHi = (posHi >> 10u) & 0x3FFu;
    uint zHi = (posHi >> 20u) & 0x3FFu;
    uint xLo = (posLo >> 0u) & 0x3FFu;
    uint yLo = (posLo >> 10u) & 0x3FFu;
    uint zLo = (posLo >> 20u) & 0x3FFu;
    uint qx = (xHi << 10u) | xLo;
    uint qy = (yHi << 10u) | yLo;
    uint qz = (zHi << 10u) | zLo;
    float x = (float(qx) / float(POSITION_MAX_VALUE_OIT)) * float(MODEL_RANGE_OIT) - float(MODEL_ORIGIN_OIT) + origin.x;
    float y = (float(qy) / float(POSITION_MAX_VALUE_OIT)) * float(MODEL_RANGE_OIT) - float(MODEL_ORIGIN_OIT) + origin.y;
    float z = (float(qz) / float(POSITION_MAX_VALUE_OIT)) * float(MODEL_RANGE_OIT) - float(MODEL_ORIGIN_OIT) + origin.z;
    return float3(x, y, z);
}

vertex OITVertexOut oit_translucent_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant OITDrawUniforms& draw [[buffer(1)]],
    constant OITFrameUniforms& frame [[buffer(2)]]
) {
    uint base = vertexId * 5u;
    uint posHi = vertexData[base + 0u];
    uint posLo = vertexData[base + 1u];
    uint color = vertexData[base + 2u];
    uint texPacked = vertexData[base + 3u];
    uint lightData = vertexData[base + 4u];
    
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    float3 worldPos = decodePositionOIT(posHi, posLo, origin);
    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;
    
    OITVertexOut out;
    out.position = frame.viewProj * float4(cameraRelativePos, 1.0);
    
    uint uRaw = texPacked & 0xFFFFu;
    uint vRaw = (texPacked >> 16u) & 0xFFFFu;
    out.texCoord = half2(
        half(uRaw & 0x7FFFu) / half(TEXTURE_MAX_VALUE_OIT),
        half(vRaw & 0x7FFFu) / half(TEXTURE_MAX_VALUE_OIT)
    );
    
    out.color = half4(
        half((color >> 0u) & 0xFFu) / 255.0h,
        half((color >> 8u) & 0xFFu) / 255.0h,
        half((color >> 16u) & 0xFFu) / 255.0h,
        1.0h
    );
    
    uint block = (lightData >> 0u) & 0xFFu;
    uint sky = (lightData >> 8u) & 0xFFu;
    out.light = half2(half(block) / 255.0h, half(sky) / 255.0h);
    out.distance = half(clamp(length(cameraRelativePos), 0.0f, 8192.0f));
    
    return out;
}

fragment OITAccumulation oit_translucent_fragment(
    OITVertexOut in [[stage_in]],
    texture2d<half, access::sample> atlas [[texture(0)]],
    sampler atlasSampler [[sampler(0)]],
    constant OITFrameUniforms& frame [[buffer(2)]]
) {
    half4 texColor = atlas.sample(atlasSampler, float2(in.texCoord));
    if (texColor.a < 0.01h) {
        discard_fragment();
    }
    half4 finalColor = texColor * in.color;
    half skyLight = in.light.y;
    half dayBrightness = frame.lightParams.x;
    half ambientLight = frame.lightParams.y;
    half moonlight = 0.15h;
    half effectiveSky = max(dayBrightness, moonlight);
    half brightness = skyLight * effectiveSky;
    brightness = brightness * brightness * (3.0h - 2.0h * brightness);
    half lightLevel = max(brightness, ambientLight);
    finalColor.rgb *= lightLevel;
    half z = in.position.z; 
    half alpha = finalColor.a;
    half weight = alpha * max(0.01h, min(3000.0h, 
        10.0h / (0.00001h + z * z * z * 10000.0h)));
    
    OITAccumulation out;
    out.accumColor = half4(finalColor.rgb * alpha * weight, alpha * weight);
    out.accumAlpha = alpha;
    return out;
}

struct CompositeVertexOut {
    float4 position [[position]];
    float2 texCoord;
};

vertex CompositeVertexOut oit_composite_vertex(uint vertexId [[vertex_id]]) {
    float2 pos;
    pos.x = (vertexId == 1) ? 3.0 : -1.0;
    pos.y = (vertexId == 2) ? 3.0 : -1.0;
    
    CompositeVertexOut out;
    out.position = float4(pos, 0.0, 1.0);
    out.texCoord = pos * 0.5 + 0.5;
    out.texCoord.y = 1.0 - out.texCoord.y;
    return out;
}

fragment half4 oit_composite_fragment(
    CompositeVertexOut in [[stage_in]],
    texture2d<half, access::read> accumTexture [[texture(0)]],
    texture2d<half, access::read> alphaTexture [[texture(1)]]
) {
    uint2 coord = uint2(in.position.xy);
    half4 accum = accumTexture.read(coord);
    half revealage = alphaTexture.read(coord).r;
    if (accum.w < 0.001h) {
        discard_fragment();
    }
    
    half3 averageColor = accum.rgb / max(accum.w, 0.001h);
    half alpha = 1.0h - revealage;
    
    return half4(averageColor, alpha);
}
