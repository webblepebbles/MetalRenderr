#include <metal_stdlib>
using namespace metal;
constant uint LOD_LEVEL [[function_constant(0)]];
constant bool USE_TEXTURED_FORMAT [[function_constant(1)]];  
constant bool ENABLE_TEXTURE [[function_constant(2)]];       
constant bool ENABLE_FOG [[function_constant(3)]];
constant bool ENABLE_STOCHASTIC_ALPHA [[function_constant(4)]]; 
constant bool IS_DEPTH_PRIME [[function_constant(5)]];       

struct ExtendedDrawUniforms {
    float originX;      
    float originY;
    float originZ;
    float padding;
};

struct ExtendedFrameUniforms {
    float4x4 viewProj;       
    float4 cameraPos;        
    float4 fogColor;
    float4 fogParams;        
    float4 lightParams;      
};

struct ExtLodVertexOut {
    float4 position [[position]];
    half2 texCoord;
    half4 color;
    half light;              
    half distance;           
};

struct DepthOnlyOut {
    float4 position [[position]];
};

struct PaletteBuffer {
    uint colors[256]; 
};
half decodeHalf(ushort bits) {
    return as_type<half>(bits);
}

struct TexturedVertex {
    float3 position;
    half2 texCoord;
    half4 color;
    half light;
};

TexturedVertex decodeTexturedVertex(constant uint* data, uint vertexId, float3 origin) {
    uint base = vertexId * 3u; 

    uint packedPos = data[base + 0u];
    uint packedUV = data[base + 1u];
    uint packedColorLight = data[base + 2u];
    float x = float((packedPos >> 0u) & 0x3FFu) / 64.0f;  
    float y = float((packedPos >> 10u) & 0x3FFu) / 64.0f;
    float z = float((packedPos >> 20u) & 0x3FFu) / 64.0f;
    float3 worldPos = float3(x, y, z) + origin;
    half u = decodeHalf(ushort(packedUV & 0xFFFFu));
    half v = decodeHalf(ushort((packedUV >> 16u) & 0xFFFFu));
    half r = half((packedColorLight >> 0u) & 0xFFu) / 255.0h;
    half g = half((packedColorLight >> 8u) & 0xFFu) / 255.0h;
    half b = half((packedColorLight >> 16u) & 0xFFu) / 255.0h;
    half light = half((packedColorLight >> 24u) & 0xFFu) / 255.0h;

    TexturedVertex vtx;
    vtx.position = worldPos;
    vtx.texCoord = half2(u, v);
    vtx.color = half4(r, g, b, 1.0h);
    vtx.light = light;
    return vtx;
}

struct CompactVertex {
    float3 position;
    uint colorIndex;
    half skyLight;
    half ao;
};

CompactVertex decodeCompactVertex(constant uint* data, uint vertexId, float3 origin) {
    uint base = vertexId * 2u; 

    uint word0 = data[base + 0u];
    uint word1 = data[base + 1u];
    half hx = decodeHalf(ushort(word0 & 0xFFFFu));
    half hy = decodeHalf(ushort((word0 >> 16u) & 0xFFFFu));
    half hz = decodeHalf(ushort(word1 & 0xFFFFu));
    float3 worldPos = float3(float(hx), float(hy), float(hz)) + origin;

    uint colorIdx = (word1 >> 16u) & 0xFFu;
    uint skyAo = (word1 >> 24u) & 0xFFu;

    CompactVertex vtx;
    vtx.position = worldPos;
    vtx.colorIndex = colorIdx;
    vtx.skyLight = half((skyAo >> 4u) & 0xFu) / 15.0h;
    vtx.ao = half(skyAo & 0xFu) / 15.0h;
    return vtx;
}

half computeExtendedLighting(half skyLight, half dayBrightness, half ambientLight) {
    half moonlight = 0.15h;
    half effectiveSky = fast::max(dayBrightness, moonlight);
    half brightness = skyLight * effectiveSky;
    brightness = brightness * brightness * fast::fma(-2.0h, brightness, 3.0h);
    return fast::max(brightness, ambientLight);
}

half4 applyExtendedFog(half4 color, half dist, float4 fogColor, float4 fogParams) {
    if (!ENABLE_FOG) return color;

    half fogStart = half(fogParams.x);
    half fogEnd = half(fogParams.y);
    if (fogEnd <= fogStart) return color;

    half fogFactor = fast::clamp((dist - fogStart) / (fogEnd - fogStart), 0.0h, 1.0h);
    fogFactor = fogFactor * fogFactor;
    return mix(color, half4(fogColor), fogFactor);
}

constant half bayerMatrix[16] = {
     0.0h/16.0h,  8.0h/16.0h,  2.0h/16.0h, 10.0h/16.0h,
    12.0h/16.0h,  4.0h/16.0h, 14.0h/16.0h,  6.0h/16.0h,
     3.0h/16.0h, 11.0h/16.0h,  1.0h/16.0h,  9.0h/16.0h,
    15.0h/16.0h,  7.0h/16.0h, 13.0h/16.0h,  5.0h/16.0h
};

half getBayerDither(float4 pos) {
    uint px = uint(pos.x) & 3u;
    uint py = uint(pos.y) & 3u;
    return bayerMatrix[py * 4u + px];
}

vertex DepthOnlyOut ext_lod_depth_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant ExtendedDrawUniforms& draw [[buffer(1)]],
    constant ExtendedFrameUniforms& frame [[buffer(2)]]
) {
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    float3 worldPos;

    if (USE_TEXTURED_FORMAT) {
        uint base = vertexId * 3u;
        uint packedPos = vertexData[base + 0u];
        float x = float((packedPos >> 0u) & 0x3FFu) / 64.0f;
        float y = float((packedPos >> 10u) & 0x3FFu) / 64.0f;
        float z = float((packedPos >> 20u) & 0x3FFu) / 64.0f;
        worldPos = float3(x, y, z) + origin;
    } else {
        uint base = vertexId * 2u;
        uint word0 = vertexData[base + 0u];
        uint word1 = vertexData[base + 1u];
        half hx = decodeHalf(ushort(word0 & 0xFFFFu));
        half hy = decodeHalf(ushort((word0 >> 16u) & 0xFFFFu));
        half hz = decodeHalf(ushort(word1 & 0xFFFFu));
        worldPos = float3(float(hx), float(hy), float(hz)) + origin;
    }

    float3 cameraRelative = worldPos - frame.cameraPos.xyz;

    DepthOnlyOut out;
    out.position = frame.viewProj * float4(cameraRelative, 1.0f);
    return out;
}
fragment void ext_lod_depth_fragment() {
}

vertex ExtLodVertexOut ext_lod_textured_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant ExtendedDrawUniforms& draw [[buffer(1)]],
    constant ExtendedFrameUniforms& frame [[buffer(2)]]
) {
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    TexturedVertex v = decodeTexturedVertex(vertexData, vertexId, origin);

    float3 cameraRelative = v.position - frame.cameraPos.xyz;

    ExtLodVertexOut out;
    out.position = frame.viewProj * float4(cameraRelative, 1.0f);
    out.texCoord = v.texCoord;
    out.color = v.color;
    out.light = v.light;
    out.distance = half(fast::clamp(fast::length(cameraRelative), 0.0f, 8192.0f));
    return out;
}

vertex ExtLodVertexOut ext_lod_compact_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant ExtendedDrawUniforms& draw [[buffer(1)]],
    constant ExtendedFrameUniforms& frame [[buffer(2)]],
    constant PaletteBuffer& palette [[buffer(3)]]
) {
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    CompactVertex v = decodeCompactVertex(vertexData, vertexId, origin);

    float3 cameraRelative = v.position - frame.cameraPos.xyz;
    uint packedColor = palette.colors[v.colorIndex];
    half r = half((packedColor >> 16u) & 0xFFu) / 255.0h;
    half g = half((packedColor >> 8u) & 0xFFu) / 255.0h;
    half b = half(packedColor & 0xFFu) / 255.0h;
    half aoFactor = fast::fma(v.ao, 0.6h, 0.4h); 

    ExtLodVertexOut out;
    out.position = frame.viewProj * float4(cameraRelative, 1.0f);
    out.texCoord = half2(0.0h, 0.0h); 
    out.color = half4(r * aoFactor, g * aoFactor, b * aoFactor, 1.0h);
    out.light = v.skyLight;
    out.distance = half(fast::clamp(fast::length(cameraRelative), 0.0f, 8192.0f));
    return out;
}

fragment half4 ext_lod_color_fragment(
    ExtLodVertexOut in [[stage_in]],
    texture2d<half, access::sample> atlas [[texture(0), function_constant(ENABLE_TEXTURE)]],
    constant ExtendedFrameUniforms& frame [[buffer(2)]]
) {
    half4 baseColor;

    if (ENABLE_TEXTURE) {
        constexpr sampler lodSampler(coord::normalized,
                                      address::repeat,
                                      filter::nearest,
                                      mip_filter::nearest);
        baseColor = atlas.sample(lodSampler, float2(in.texCoord));
        if (baseColor.a < 0.5h) {
            discard_fragment();
        }
    } else {
        baseColor = half4(1.0h, 1.0h, 1.0h, 1.0h);
    }
    half4 finalColor = baseColor * in.color;
    half dayBrightness = half(frame.lightParams.x);
    half ambientLight = half(frame.lightParams.y);
    half lightLevel = computeExtendedLighting(in.light, dayBrightness, ambientLight);
    finalColor.rgb *= lightLevel;
    if (ENABLE_STOCHASTIC_ALPHA) {
        half dither = getBayerDither(in.position);
        if (finalColor.a < dither) {
            discard_fragment();
        }
        finalColor.a = 1.0h; 
    }
    finalColor = applyExtendedFog(finalColor, in.distance, frame.fogColor, frame.fogParams);

    return finalColor;
}
