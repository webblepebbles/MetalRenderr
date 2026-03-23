#include <metal_stdlib>
using namespace metal;
struct LodMesherParams {
    uint3  chunkOrigin;
    uint   lodLevel;
    uint   maxOutputQuads;
    float  texScale;
    uint   _pad0;
    uint   _pad1;
};
struct LodMeshVertex {
    packed_float3  position;
    packed_short2  texCoord;
    packed_uchar4  color;
    packed_uchar4  normal;
    uchar          packedLight;
    uchar          lodFlags;
    short          _pad;
};
struct LodDrawArgs {
    atomic_uint vertexCount;
    uint instanceCount;
    uint firstVertex;
    uint firstInstance;
};
constant float3 kNormals[6] = {
    float3( 1,  0,  0),
    float3(-1,  0,  0),
    float3( 0,  1,  0),
    float3( 0, -1,  0),
    float3( 0,  0,  1),
    float3( 0,  0, -1)
};
constant int3 kOffsets[6] = {
    int3( 1,  0,  0),
    int3(-1,  0,  0),
    int3( 0,  1,  0),
    int3( 0, -1,  0),
    int3( 0,  0,  1),
    int3( 0,  0, -1)
};
uchar4 encodeNormal(float3 n) {
    return uchar4(
        uchar((n.x * 0.5 + 0.5) * 255.0),
        uchar((n.y * 0.5 + 0.5) * 255.0),
        uchar((n.z * 0.5 + 0.5) * 255.0),
        255
    );
}
bool isBlockAir(uint id) { return id == 0; }
bool neighborOccluding(device const uint* blocks, int3 pos, uint sectionSize) {
    if (any(pos < 0) || any(pos >= int3(sectionSize))) return false;
    uint idx = uint(pos.y * sectionSize * sectionSize + pos.z * sectionSize + pos.x);
    uint id = blocks[idx];
    return id != 0 && (id & 0x80000000u) == 0;
}
kernel void lod_compute_mesh(
    device const uint*     blockStates   [[buffer(0)]],
    device const uchar4*   blockColors   [[buffer(1)]],
    device const uint*     blockTexIds   [[buffer(2)]],
    device const uchar*    lightData     [[buffer(3)]],
    device LodMeshVertex*  vertices      [[buffer(4)]],
    device LodDrawArgs*    drawArgs      [[buffer(5)]],
    constant LodMesherParams& params     [[buffer(6)]],
    uint3 gid [[thread_position_in_grid]]
) {
    uint step = 1u << params.lodLevel;
    uint sectionSize = 16;
    uint3 blockBase = gid * step;
    if (any(blockBase >= uint3(sectionSize))) return;
    bool hasBlock = false;
    uchar4 avgColor = uchar4(0);
    uint colorR = 0, colorG = 0, colorB = 0, colorA = 0;
    uint texId = 0;
    uchar maxLight = 0;
    uint blockCount = 0;
    for (uint dy = 0; dy < step && (blockBase.y + dy) < sectionSize; dy++) {
        for (uint dz = 0; dz < step && (blockBase.z + dz) < sectionSize; dz++) {
            for (uint dx = 0; dx < step && (blockBase.x + dx) < sectionSize; dx++) {
                uint3 pos = blockBase + uint3(dx, dy, dz);
                uint idx = pos.y * 256 + pos.z * 16 + pos.x;
                uint id = blockStates[idx];
                if (!isBlockAir(id)) {
                    hasBlock = true;
                    uchar4 c = blockColors[idx];
                    colorR += uint(c.r);
                    colorG += uint(c.g);
                    colorB += uint(c.b);
                    colorA += uint(c.a);
                    texId = blockTexIds[idx];
                    maxLight = max(maxLight, lightData[idx]);
                    blockCount++;
                }
            }
        }
    }
    if (!hasBlock) return;
    if (blockCount > 0) {
        avgColor = uchar4(
            uchar(colorR / blockCount),
            uchar(colorG / blockCount),
            uchar(colorB / blockCount),
            uchar(colorA / blockCount)
        );
    }
    float blockSize = float(step);
    float3 basePos = float3(blockBase) + float3(params.chunkOrigin);
    float texU = float(texId & 0xFFFFu) * params.texScale;
    float texV = float(texId >> 16)      * params.texScale;
    for (uint face = 0; face < 6; face++) {
        int3 neighborPos = int3(blockBase) + kOffsets[face] * int(step);
        bool occluded = true;
        if (any(neighborPos < 0) || any(neighborPos >= int3(sectionSize))) {
            occluded = false;
        } else {
            for (uint d1 = 0; d1 < step && occluded; d1++) {
                for (uint d2 = 0; d2 < step && occluded; d2++) {
                    int3 checkPos = neighborPos;
                    if (face < 2) { checkPos.y += int(d1); checkPos.z += int(d2); }
                    else if (face < 4) { checkPos.x += int(d1); checkPos.z += int(d2); }
                    else { checkPos.x += int(d1); checkPos.y += int(d2); }
                    if (any(checkPos < 0) || any(checkPos >= int3(sectionSize))) {
                        occluded = false;
                    } else {
                        uint nIdx = uint(checkPos.y * 256 + checkPos.z * 16 + checkPos.x);
                        if (isBlockAir(blockStates[nIdx])) occluded = false;
                    }
                }
            }
        }
        if (occluded) continue;
        uint base = atomic_fetch_add_explicit(&drawArgs->vertexCount, 4, memory_order_relaxed);
        if (base + 4 > params.maxOutputQuads * 4) return;
        float3 n = kNormals[face];
        uchar4 packedN = encodeNormal(n);
        float3 center = basePos + n * blockSize * 0.5;
        float3 up, right;
        if (abs(n.y) > 0.5) {
            right = float3(blockSize, 0, 0);
            up    = float3(0, 0, blockSize);
        } else if (abs(n.x) > 0.5) {
            right = float3(0, 0, blockSize);
            up    = float3(0, blockSize, 0);
        } else {
            right = float3(blockSize, 0, 0);
            up    = float3(0, blockSize, 0);
        }
        float3 p0 = center - right * 0.5 - up * 0.5;
        float3 p1 = center + right * 0.5 - up * 0.5;
        float3 p2 = center + right * 0.5 + up * 0.5;
        float3 p3 = center - right * 0.5 + up * 0.5;
        float2 uv0 = float2(texU,                   texV);
        float2 uv1 = float2(texU + params.texScale, texV);
        float2 uv2 = float2(texU + params.texScale, texV + params.texScale);
        float2 uv3 = float2(texU,                   texV + params.texScale);
        LodMeshVertex mv;
        mv.color = avgColor;
        mv.normal = packedN;
        mv.packedLight = maxLight;
        mv.lodFlags = 0;
        mv._pad = 0;
        mv.position = p0; mv.texCoord = short2(short(uv0.x * 65535.0), short(uv0.y * 65535.0));
        vertices[base + 0] = mv;
        mv.position = p1; mv.texCoord = short2(short(uv1.x * 65535.0), short(uv1.y * 65535.0));
        vertices[base + 1] = mv;
        mv.position = p2; mv.texCoord = short2(short(uv2.x * 65535.0), short(uv2.y * 65535.0));
        vertices[base + 2] = mv;
        mv.position = p3; mv.texCoord = short2(short(uv3.x * 65535.0), short(uv3.y * 65535.0));
        vertices[base + 3] = mv;
    }
}
