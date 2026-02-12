#include <metal_stdlib>
using namespace metal;

struct EntityFrameUniforms {
    float4x4 viewProj;
    float4 cameraPos;
    float4 fogColor;
    float4 fogParams;
    float4 lightParams;
};

struct EntityVertexIn {
    float3 position [[attribute(0)]];
    float2 texCoord [[attribute(1)]];
    uint color [[attribute(2)]];
    uint packedNormal [[attribute(3)]];
};

struct EntityVertexOut {
    float4 position [[position]];
    half2 texCoord;
    half4 color;
    half3 normal;
    half distance;
    half lightBrightness;
    half dayBrightness;
};

half4 decodeEntityColor(uint colorPacked) {
    return half4(
        half((colorPacked >> 0u) & 0xFFu) / 255.0h,
        half((colorPacked >> 8u) & 0xFFu) / 255.0h,
        half((colorPacked >> 16u) & 0xFFu) / 255.0h,
        half((colorPacked >> 24u) & 0xFFu) / 255.0h
    );
}

half3 decodeEntityNormal(uint packedNormal) {
    half nx = half((packedNormal >> 0u) & 0x3FFu) / 1023.0h * 2.0h - 1.0h;
    half ny = half((packedNormal >> 10u) & 0x3FFu) / 1023.0h * 2.0h - 1.0h;
    half nz = half((packedNormal >> 20u) & 0x3FFu) / 1023.0h * 2.0h - 1.0h;
    return normalize(half3(nx, ny, nz));
}

half decodeLightBrightness(uint packedLight, half dayBrightness, half ambientLight) {
    half blockLight = half(packedLight & 0xFFFFu) / 240.0h;
    half skyLight = half((packedLight >> 16u) & 0xFFFFu) / 240.0h;

    blockLight = clamp(blockLight, 0.0h, 1.0h);
    skyLight = clamp(skyLight, 0.0h, 1.0h);

    half blockBrightness = blockLight * blockLight * (3.0h - 2.0h * blockLight);
    blockBrightness = 0.1h + blockBrightness * 0.9h;
    if (blockLight < 0.01h) blockBrightness = 0.0h;

    half effectiveDayBrightness = max(0.15h, dayBrightness);
    half skyBrightness = skyLight * effectiveDayBrightness;

    half brightness = max(blockBrightness, skyBrightness);
    half ambient = max(0.03h, ambientLight * 0.15h);

    return clamp(ambient + brightness * (1.0h - ambient), 0.0h, 1.0h);
}

vertex float4 entity_depth_vertex(
    uint vertexId [[vertex_id]],
    constant float* vertexData [[buffer(0)]],
    constant EntityFrameUniforms& frame [[buffer(1)]]
) {
    uint base = vertexId * 8u;

    float3 worldPos = float3(
        vertexData[base + 0u],
        vertexData[base + 1u],
        vertexData[base + 2u]
    );

    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;
    return frame.viewProj * float4(cameraRelativePos, 1.0);
}

vertex EntityVertexOut entity_color_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant EntityFrameUniforms& frame [[buffer(1)]]
) {
    uint base = vertexId * 8u;

    float3 worldPos = float3(
        as_type<float>(vertexData[base + 0u]),
        as_type<float>(vertexData[base + 1u]),
        as_type<float>(vertexData[base + 2u])
    );

    float2 uv = float2(
        as_type<float>(vertexData[base + 3u]),
        as_type<float>(vertexData[base + 4u])
    );

    uint colorPacked = vertexData[base + 5u];
    uint normalPacked = vertexData[base + 6u];
    uint packedLight = vertexData[base + 7u];

    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;

    EntityVertexOut out;
    out.position = frame.viewProj * float4(cameraRelativePos, 1.0);
    out.texCoord = half2(uv);
    out.color = decodeEntityColor(colorPacked);
    out.normal = decodeEntityNormal(normalPacked);
    out.distance = half(clamp(length(cameraRelativePos), 0.0f, 4096.0f));
    out.dayBrightness = frame.lightParams.x;
    out.lightBrightness = decodeLightBrightness(packedLight, frame.lightParams.x, frame.lightParams.y);

    return out;
}

fragment half4 entity_color_fragment(
    EntityVertexOut in [[stage_in]],
    texture2d<half, access::sample> entityTexture [[texture(0)]]
) {

    constexpr sampler entityPointSampler(coord::normalized,
                                         address::clamp_to_edge,
                                         filter::nearest,
                                         mip_filter::nearest);
    half4 texColor = entityTexture.sample(entityPointSampler, float2(in.texCoord));

    if (texColor.a < 0.1h) {
        discard_fragment();
    }

    half4 finalColor = texColor * in.color;

    half dayFactor = in.dayBrightness;
    half sunAngle = dayFactor * 3.14159h;
    half3 sunDir = normalize(half3(
        cos(sunAngle) * 0.4h,
        sin(sunAngle) * 0.8h + 0.2h,
        0.3h
    ));

    half ndotl = max(dot(in.normal, sunDir), 0.0h);
    half directional = 0.7h + 0.3h * ndotl;

    half totalLight = in.lightBrightness * directional;
    finalColor.rgb *= clamp(totalLight, 0.0h, 1.0h);

    return finalColor;
}

fragment half4 entity_color_only_fragment(
    EntityVertexOut in [[stage_in]]
) {
    half4 finalColor = in.color;

    if (finalColor.a < 0.1h) {
        discard_fragment();
    }

    half dayFactor = in.dayBrightness;
    half sunAngle = dayFactor * 3.14159h;
    half3 sunDir = normalize(half3(cos(sunAngle) * 0.4h, sin(sunAngle) * 0.8h + 0.2h, 0.3h));
    half ndotl = max(dot(in.normal, sunDir), 0.0h);
    half directional = 0.75h + 0.25h * ndotl;

    half totalLight = in.lightBrightness * directional;
    finalColor.rgb *= clamp(totalLight, 0.0h, 1.0h);

    return finalColor;
}

vertex EntityVertexOut entity_color_vertex_vd(
    EntityVertexIn in [[stage_in]],
    constant EntityFrameUniforms& frame [[buffer(1)]]
) {
    float3 cameraRelativePos = in.position - frame.cameraPos.xyz;

    EntityVertexOut out;
    out.position = frame.viewProj * float4(cameraRelativePos, 1.0);
    out.texCoord = half2(in.texCoord);
    out.color = decodeEntityColor(in.color);
    out.normal = decodeEntityNormal(in.packedNormal);
    out.distance = half(clamp(length(cameraRelativePos), 0.0f, 4096.0f));
    out.dayBrightness = frame.lightParams.x;
    out.lightBrightness = decodeLightBrightness(0x00F000F0u, frame.lightParams.x, frame.lightParams.y);

    return out;
}
