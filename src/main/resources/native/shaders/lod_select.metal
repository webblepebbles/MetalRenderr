#include <metal_stdlib>
using namespace metal;

constant half LOD_THRESHOLD_0 = 128.0h;  
constant half LOD_THRESHOLD_1 = 192.0h;  
constant half LOD_THRESHOLD_2 = 256.0h; 
constant half LOD_THRESHOLD_3 = 512.0h;   
constant half LOD_THRESHOLD_4 = 1024.0h; 
constant uint MAX_COMMANDS_PER_LOD = 65536u;

struct ChunkMeta {
    half originX;
    half originY;
    half originZ;
    half boundsRadius;
    uint vertexOffset;
    uint vertexCount;
    uint lodOffsets[6];
    uint lodCounts[6];
    uint renderLayer;
    uint flags;
};

struct LODDrawCommand {
    half originX;
    half originY;
    half originZ;
    half renderLayer;
    uint vertexOffset;
    uint vertexCount;
};

struct LODFrameUniforms {
    float4 cameraPos;
    float4x4 viewProj;
    float4 frustumPlanes[6];
    half dayBrightness;
    half ambientLight;
    half fogStart;
    half fogEnd;
};

uint selectLOD(half distance) {
    if (distance < LOD_THRESHOLD_0) return 0u;
    if (distance < LOD_THRESHOLD_1) return 1u;
    if (distance < LOD_THRESHOLD_2) return 2u;
    if (distance < LOD_THRESHOLD_3) return 3u;
    if (distance < LOD_THRESHOLD_4) return 4u;
    return 5u;
}

bool isVisibleInFrustum(float3 center, half radius, constant float4* planes) {
    for (int i = 0; i < 6; i++) {
        float dist = dot(planes[i].xyz, center) + planes[i].w;
        if (dist < -float(radius)) {
            return false;
        }
    }
    return true;
}

kernel void lod_select_and_cull(
    device const ChunkMeta* chunks         [[buffer(0)]],
    constant LODFrameUniforms& frame       [[buffer(1)]],
    device atomic_uint* lodDrawCounts      [[buffer(2)]],
    device LODDrawCommand* lodCommands0    [[buffer(3)]],
    device LODDrawCommand* lodCommands1    [[buffer(4)]],
    device LODDrawCommand* lodCommands2    [[buffer(5)]],
    device LODDrawCommand* lodCommands3    [[buffer(6)]],
    device LODDrawCommand* lodCommands4    [[buffer(7)]],
    device LODDrawCommand* lodCommands5    [[buffer(8)]],
    constant uint& chunkCount              [[buffer(9)]],

    uint tid [[thread_position_in_grid]]
) {
    if (tid >= chunkCount) return;
    
    ChunkMeta chunk = chunks[tid];
    
    if (chunk.vertexCount == 0u) return;
    
    float3 chunkCenter = float3(
        float(chunk.originX) + 8.0f,
        float(chunk.originY) + 8.0f,
        float(chunk.originZ) + 8.0f
    );
    float3 cameraRelative = chunkCenter - frame.cameraPos.xyz;
    
    half distance = half(length(cameraRelative));
    
    half radius = max(chunk.boundsRadius, half(14.0h));
    if (!isVisibleInFrustum(chunkCenter, radius, frame.frustumPlanes)) {
        return;
    }
    
    uint lodLevel = selectLOD(distance);
    
    uint vertexOffset = chunk.lodOffsets[lodLevel];
    uint vertexCount = chunk.lodCounts[lodLevel];
    
    if (vertexCount == 0u) {
        for (uint fallback = lodLevel; fallback > 0u; fallback--) {
            if (chunk.lodCounts[fallback - 1u] > 0u) {
                vertexOffset = chunk.lodOffsets[fallback - 1u];
                vertexCount = chunk.lodCounts[fallback - 1u];
                lodLevel = fallback - 1u;
                break;
            }
        }
        if (vertexCount == 0u) {
            vertexOffset = chunk.lodOffsets[0];
            vertexCount = chunk.lodCounts[0];
            lodLevel = 0u;
        }
    }
    
    if (vertexCount == 0u) return;
    
    uint index;
    
    LODDrawCommand cmd;
    cmd.originX = chunk.originX;
    cmd.originY = chunk.originY;
    cmd.originZ = chunk.originZ;
    cmd.renderLayer = half(chunk.renderLayer);
    cmd.vertexOffset = vertexOffset;
    cmd.vertexCount = vertexCount;
    
    switch (lodLevel) {
        case 0u:
            index = atomic_fetch_add_explicit(&lodDrawCounts[0], 1u, memory_order_relaxed);
            if (index < MAX_COMMANDS_PER_LOD) lodCommands0[index] = cmd;
            break;
        case 1u:
            index = atomic_fetch_add_explicit(&lodDrawCounts[1], 1u, memory_order_relaxed);
            if (index < MAX_COMMANDS_PER_LOD) lodCommands1[index] = cmd;
            break;
        case 2u:
            index = atomic_fetch_add_explicit(&lodDrawCounts[2], 1u, memory_order_relaxed);
            if (index < MAX_COMMANDS_PER_LOD) lodCommands2[index] = cmd;
            break;
        case 3u:
            index = atomic_fetch_add_explicit(&lodDrawCounts[3], 1u, memory_order_relaxed);
            if (index < MAX_COMMANDS_PER_LOD) lodCommands3[index] = cmd;
            break;
        case 4u:
            index = atomic_fetch_add_explicit(&lodDrawCounts[4], 1u, memory_order_relaxed);
            if (index < MAX_COMMANDS_PER_LOD) lodCommands4[index] = cmd;
            break;
        case 5u:
            index = atomic_fetch_add_explicit(&lodDrawCounts[5], 1u, memory_order_relaxed);
            if (index < MAX_COMMANDS_PER_LOD) lodCommands5[index] = cmd;
            break;
    }
}

kernel void hiz_lod_cull(
    device LODDrawCommand* commands        [[buffer(0)]],
    device atomic_uint* survivorCount      [[buffer(1)]],
    device LODDrawCommand* survivors       [[buffer(2)]],
    constant LODFrameUniforms& frame       [[buffer(3)]],
    texture2d<half, access::read> hizBuffer [[texture(0)]],
    constant uint& commandCount            [[buffer(4)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid >= commandCount) return;
    
    LODDrawCommand cmd = commands[tid];
    
    float3 minBounds = float3(
        float(cmd.originX),
        float(cmd.originY),
        float(cmd.originZ)
    );
    float3 maxBounds = minBounds + float3(16.0f, 16.0f, 16.0f);
    
    float minScreenX = 1.0f, maxScreenX = -1.0f;
    float minScreenY = 1.0f, maxScreenY = -1.0f;
    float minDepth = 1.0f;
    
    float3 corners[8] = {
        float3(minBounds.x, minBounds.y, minBounds.z),
        float3(maxBounds.x, minBounds.y, minBounds.z),
        float3(minBounds.x, maxBounds.y, minBounds.z),
        float3(maxBounds.x, maxBounds.y, minBounds.z),
        float3(minBounds.x, minBounds.y, maxBounds.z),
        float3(maxBounds.x, minBounds.y, maxBounds.z),
        float3(minBounds.x, maxBounds.y, maxBounds.z),
        float3(maxBounds.x, maxBounds.y, maxBounds.z)
    };
    
    bool anyInFront = false;
    for (int i = 0; i < 8; i++) {
        float3 relative = corners[i] - frame.cameraPos.xyz;
        float4 clip = frame.viewProj * float4(relative, 1.0f);
        if (clip.w <= 0.0f) continue;
        
        float3 ndc = clip.xyz / clip.w;
        minScreenX = min(minScreenX, ndc.x);
        maxScreenX = max(maxScreenX, ndc.x);
        minScreenY = min(minScreenY, ndc.y);
        maxScreenY = max(maxScreenY, ndc.y);
        minDepth = min(minDepth, ndc.z);
        anyInFront = true;
    }
    
    if (!anyInFront) return;
    
    float2 uvMin = float2(minScreenX * 0.5f + 0.5f, minScreenY * 0.5f + 0.5f);
    float2 uvMax = float2(maxScreenX * 0.5f + 0.5f, maxScreenY * 0.5f + 0.5f);
    
    uvMin = clamp(uvMin, float2(0.0f), float2(1.0f));
    uvMax = clamp(uvMax, float2(0.0f), float2(1.0f));
    
    uint hizW = hizBuffer.get_width();
    uint hizH = hizBuffer.get_height();
    
    float2 uvCenter = (uvMin + uvMax) * 0.5f;
    uint2 sampleCoord = uint2(
        clamp(uint(uvCenter.x * float(hizW)), 0u, hizW - 1u),
        clamp(uint(uvCenter.y * float(hizH)), 0u, hizH - 1u)
    );
    
    half hizDepth = hizBuffer.read(sampleCoord).x;
    
    if (minDepth > float(hizDepth) + 0.001f) {
        return;
    }
    
    uint idx = atomic_fetch_add_explicit(survivorCount, 1u, memory_order_relaxed);
    survivors[idx] = cmd;
}
