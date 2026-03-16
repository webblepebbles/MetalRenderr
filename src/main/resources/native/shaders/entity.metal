#include <metal_stdlib>
using namespace metal;










struct EntityVertex {
    packed_float3 position;
    packed_short2 texCoord;
    packed_uchar4 color;
    packed_uchar4 normal;
    packed_short2 overlay;
    packed_short2 lightUV;
};

struct EntityVertexOut {
    float4 position  [[position]];
    float2 texCoord;
    float4 color;
    float3 normal;
    float2 lightUV;
    float2 overlay;
    float3 worldPos;
};


struct EntityInstanceData {
    float4x4 modelMatrix;
    float4   tintColor;
    float4   overlayParams;
    uint     textureIndex;
    uint     flags;
    float    _pad0;
    float    _pad1;
};





vertex EntityVertexOut vertex_entity(
    device const EntityVertex*     vertices    [[buffer(0)]],
    constant float4x4&             projection  [[buffer(1)]],
    constant float4x4&             modelView   [[buffer(2)]],
    uint vid [[vertex_id]]
) {
    EntityVertex v = vertices[vid];
    EntityVertexOut out;

    float3 pos     = float3(v.position);
    float4 viewPos = modelView * float4(pos, 1.0);

    out.position = projection * viewPos;
    out.texCoord = float2(v.texCoord) / 32768.0;
    out.color    = float4(v.color) / 255.0;
    out.normal   = normalize(float3(v.normal.xyz) / 127.0 - 1.0);
    out.lightUV  = float2(v.lightUV) / 256.0;
    out.overlay  = float2(v.overlay.x, v.overlay.y);
    out.worldPos = pos;

    return out;
}


vertex EntityVertexOut vertex_entity_instanced(
    device const EntityVertex*       vertices    [[buffer(0)]],
    device const EntityInstanceData* instances   [[buffer(1)]],
    constant float4x4&               projection  [[buffer(2)]],
    constant float4x4&               viewMatrix  [[buffer(3)]],
    uint vid [[vertex_id]],
    uint iid [[instance_id]]
) {
    EntityVertex v = vertices[vid];
    EntityInstanceData inst = instances[iid];
    EntityVertexOut out;

    float3 localPos = float3(v.position);
    float4 worldPos = inst.modelMatrix * float4(localPos, 1.0);
    float4 viewPos  = viewMatrix * worldPos;

    out.position = projection * viewPos;
    out.texCoord = float2(v.texCoord) / 32768.0;
    out.color    = float4(v.color) / 255.0 * inst.tintColor;
    out.normal   = normalize((inst.modelMatrix * float4(float3(v.normal.xyz) / 127.0 - 1.0, 0.0)).xyz);
    out.lightUV  = float2(v.lightUV) / 256.0;
    out.overlay  = inst.overlayParams.xy;
    out.worldPos = worldPos.xyz;

    return out;
}





fragment float4 fragment_entity(
    EntityVertexOut in [[stage_in]],
    texture2d<float> entityTex  [[texture(0)]],
    constant float4& overlayParams [[buffer(5)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);

    float4 texColor = entityTex.sample(texSampler, in.texCoord);
    if (texColor.a < 0.1) discard_fragment();

    float4 finalColor = texColor * in.color;


    float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
    float nDotL     = max(dot(in.normal, lightDir), 0.0);
    float ambient   = 0.4;
    finalColor.rgb *= (ambient + (1.0 - ambient) * nDotL);


    float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
    float skyLight   = clamp(in.lightUV.y, 0.0, 1.0);
    float lightLevel = max(blockLight, skyLight);

    lightLevel = max(lightLevel, 0.5);
    finalColor.rgb *= lightLevel;


    float hurtTime = overlayParams.x;
    if (hurtTime > 0.0) {
        finalColor.rgb = mix(finalColor.rgb, float3(1.0, 0.0, 0.0), clamp(hurtTime, 0.0, 0.6));
    }


    float whiteFlash = overlayParams.y;
    if (whiteFlash > 0.0) {
        finalColor.rgb = mix(finalColor.rgb, float3(1.0), clamp(whiteFlash, 0.0, 1.0));
    }

    return finalColor;
}


fragment float4 fragment_entity_translucent(
    EntityVertexOut in [[stage_in]],
    texture2d<float> entityTex  [[texture(0)]],
    constant float4& overlayParams [[buffer(5)]]
) {
    constexpr sampler texSampler(filter::linear, address::clamp_to_edge);

    float4 texColor = entityTex.sample(texSampler, in.texCoord);
    if (texColor.a < 0.004) discard_fragment();

    float4 finalColor = texColor * in.color;

    float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
    float nDotL     = max(dot(in.normal, lightDir), 0.0);
    finalColor.rgb *= (0.4 + 0.6 * nDotL);


    float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
    float skyLight   = clamp(in.lightUV.y, 0.0, 1.0);
    float lightLevel = max(max(blockLight, skyLight), 0.5);
    finalColor.rgb *= lightLevel;


    float hurtTime = overlayParams.x;
    if (hurtTime > 0.0) {
        finalColor.rgb = mix(finalColor.rgb, float3(1.0, 0.0, 0.0), clamp(hurtTime, 0.0, 0.6));
    }

    return finalColor;
}


fragment float4 fragment_entity_emissive(
    EntityVertexOut in [[stage_in]],
    texture2d<float> entityTex  [[texture(0)]],
    constant float4& overlayParams [[buffer(5)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);

    float4 texColor = entityTex.sample(texSampler, in.texCoord);
    if (texColor.a < 0.1) discard_fragment();

    float4 finalColor = texColor * in.color;


    float hurtTime = overlayParams.x;
    if (hurtTime > 0.0) {
        finalColor.rgb = mix(finalColor.rgb, float3(1.0, 0.0, 0.0), clamp(hurtTime, 0.0, 0.6));
    }

    return finalColor;
}


fragment float4 fragment_entity_outline(
    EntityVertexOut in [[stage_in]],
    constant float4& outlineColor [[buffer(4)]]
) {
    return outlineColor;
}






fragment float4 fragment_particle(
    EntityVertexOut in [[stage_in]],
    texture2d<float> entityTex  [[texture(0)]],
    constant float4& overlayParams [[buffer(5)]]
) {

    constexpr sampler texSampler(filter::nearest, mip_filter::nearest,
                                  address::clamp_to_edge);

    float4 texColor = entityTex.sample(texSampler, in.texCoord);
    if (texColor.a < 0.004) discard_fragment();

    float4 finalColor = texColor * in.color;


    float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
    float skyLight   = clamp(in.lightUV.y, 0.0, 1.0);
    float lightLevel = max(max(blockLight, skyLight), 0.3);
    finalColor.rgb  *= lightLevel;

    return finalColor;
}


fragment float4 fragment_entity_shadow(
    EntityVertexOut in [[stage_in]],
    texture2d<float> entityTex  [[texture(0)]],
    constant float4& overlayParams [[buffer(5)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);

    float4 texColor = entityTex.sample(texSampler, in.texCoord);
    if (texColor.a < 0.1) discard_fragment();


    return float4(0.0, 0.0, 0.0, 0.5 * texColor.a);
}
