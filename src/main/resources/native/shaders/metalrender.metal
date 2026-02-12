#include <metal_stdlib>
using namespace metal;

constant half MODEL_ORIGIN_H = 8.0h;
constant half MODEL_RANGE_H = 32.0h;
constant uint POSITION_MAX_VALUE = 1u << 20u;
constant uint TEXTURE_MAX_VALUE = 1u << 15u;

constant uint LOD_LEVEL [[function_constant(0)]];
constant bool ENABLE_BLOCK_LIGHT [[function_constant(1)]];
constant bool ENABLE_TEXTURE_SAMPLE [[function_constant(2)]];
constant bool ENABLE_FOG [[function_constant(3)]];
constant bool IS_DISTANT_LOD = (LOD_LEVEL >= 3u);
constant bool IS_ULTRA_DISTANT = (LOD_LEVEL >= 5u);

struct DrawUniforms {
    float originX;
    float originY;
    float originZ;
    float renderLayer;
};

struct FrameUniforms {
    float4x4 viewProj;
    float4 cameraPos;
    float4 fogColor;
    float4 fogParams;
    float4 texCoordShrink;
    float4 lightParams;
};

struct TerrainVertexOut {
    float4 position [[position]];
    half2 texCoord;
    half4 color;
    half2 light;
    half distance;
};

float3 decodePosition(uint posHi, uint posLo, float3 origin) {
    float kScale = float(MODEL_RANGE_H) / float(POSITION_MAX_VALUE);
    float kOffset = float(MODEL_ORIGIN_H);

    uint3 hi = uint3(posHi & 0x3FFu, (posHi >> 10u) & 0x3FFu, (posHi >> 20u) & 0x3FFu);
    uint3 lo = uint3(posLo & 0x3FFu, (posLo >> 10u) & 0x3FFu, (posLo >> 20u) & 0x3FFu);
    uint3 q = (hi << 10u) | lo;

    return fma(float3(q), float3(kScale), origin - kOffset);
}

half2 decodeTexCoord(uint texPacked) {
    half kInvTex = 1.0h / half(TEXTURE_MAX_VALUE);
    return half2(half(texPacked & 0x7FFFu), half((texPacked >> 16u) & 0x7FFFu)) * kInvTex;
}

half4 decodeColor(uint colorWord) {
    half kInv255 = 1.0h / 255.0h;
    return half4(
        half(colorWord & 0xFFu),
        half((colorWord >> 8u) & 0xFFu),
        half((colorWord >> 16u) & 0xFFu),
        255.0h
    ) * kInv255;
}

half2 decodeLight(uint lightWord) {
    half kInv255 = 1.0h / 255.0h;
    return half2(half(lightWord & 0xFFu), half((lightWord >> 8u) & 0xFFu)) * kInv255;
}

half computeDistantLighting(half skyLight, half dayBrightness, half ambientLight) {
    half effectiveSky = fast::max(dayBrightness, 0.15h);
    half brightness = skyLight * effectiveSky;
    brightness = brightness * brightness * fma(-2.0h, brightness, 3.0h);
    return fast::max(brightness, ambientLight);
}

half computeFullLighting(half2 light, half dayBrightness, half ambientLight) {
    half blockLight = light.x;
    half skyLight = light.y;

    half blockBrightness = blockLight * blockLight * fma(-2.0h, blockLight, 3.0h);
    blockBrightness = fma(blockBrightness, 0.95h, 0.05h);

    half effectiveSkyFactor = fast::max(dayBrightness, 0.15h);
    half skyBrightness = skyLight * effectiveSkyFactor;
    skyBrightness = skyBrightness * skyBrightness * fma(-2.0h, skyBrightness, 3.0h);

    half combinedLight = fast::max(blockBrightness * blockLight, skyBrightness);
    return fast::max(combinedLight, ambientLight);
}

half4 applyFog(half4 color, half distance, float4 fogColor, float4 fogParams) {
    if (!ENABLE_FOG) return color;

    half fogStart = half(fogParams.x);
    half fogEnd = half(fogParams.y);
    if (fogEnd <= fogStart) return color;

    half fogFactor = saturate((distance - fogStart) / (fogEnd - fogStart));
    return mix(color, half4(fogColor), fogFactor);
}

vertex float4 terrain_depth_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant DrawUniforms& draw [[buffer(1)]],
    constant FrameUniforms& frame [[buffer(2)]]
) {
    uint base = vertexId * 5u;
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    float3 worldPos = decodePosition(vertexData[base], vertexData[base + 1u], origin);
    return frame.viewProj * float4(worldPos - frame.cameraPos.xyz, 1.0);
}

vertex TerrainVertexOut terrain_color_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant DrawUniforms& draw [[buffer(1)]],
    constant FrameUniforms& frame [[buffer(2)]]
) {
    uint base = vertexId * 5u;
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    float3 worldPos = decodePosition(vertexData[base], vertexData[base + 1u], origin);
    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;

    TerrainVertexOut out;
    out.position = frame.viewProj * float4(cameraRelativePos, 1.0);
    out.texCoord = decodeTexCoord(vertexData[base + 3u]);
    out.color = decodeColor(vertexData[base + 2u]);
    out.light = decodeLight(vertexData[base + 4u]);
    out.distance = half(fast::clamp(fast::length(cameraRelativePos), 0.0f, 8192.0f));

    return out;
}

fragment half4 terrain_color_fragment(
    TerrainVertexOut in [[stage_in]],
    texture2d<half, access::sample> atlas [[texture(0)]],
    constant DrawUniforms& draw [[buffer(0)]],
    constant FrameUniforms& frame [[buffer(2)]]
) {
    constexpr sampler nearSampler(coord::normalized,
                                  address::repeat,
                                  filter::linear,
                                  mip_filter::nearest);
    constexpr sampler voxySampler(coord::normalized,
                                  address::repeat,
                                  filter::nearest,
                                  mip_filter::nearest);

    half4 texColor;

    if (ENABLE_TEXTURE_SAMPLE) {
        if (IS_DISTANT_LOD) {
            texColor = atlas.sample(voxySampler, float2(in.texCoord));
        } else {
            texColor = atlas.sample(nearSampler, float2(in.texCoord));
        }
    } else {
        texColor = half4(1.0h);
    }

    uint renderLayer = uint(draw.renderLayer);

    if (renderLayer == 1u) {
        if (texColor.a < 0.5h) {
            discard_fragment();
        }
    } else if (renderLayer == 0u) {
        texColor.a = 1.0h;
    }

    half4 finalColor = texColor * in.color;

    half dayBrightness = half(frame.lightParams.x);
    half ambientLight = half(frame.lightParams.y);
    half lightLevel;

    if (ENABLE_BLOCK_LIGHT) {
        lightLevel = computeFullLighting(in.light, dayBrightness, ambientLight);
    } else {
        lightLevel = computeDistantLighting(in.light.y, dayBrightness, ambientLight);
    }

    finalColor.rgb *= lightLevel;
    finalColor.a = texColor.a;

    return applyFog(finalColor, in.distance, frame.fogColor, frame.fogParams);
}
