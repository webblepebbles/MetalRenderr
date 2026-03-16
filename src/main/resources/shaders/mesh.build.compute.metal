#include <metal_stdlib>
using namespace metal;

struct MeshBuildParams {
    uint3  chunkOrigin;
    uint   lodLevel;
    uint   maxQuads;
    float  texScale;
};


struct MeshVertex {
    packed_float3  position;
    packed_short2  texCoord;
    packed_uchar4  color;
    packed_uchar4  normal;
    packed_short2  lightUV;
};


struct FaceOutput {
    float3 positions[4];
    float2 texCoords[4];
    uchar4 color;
    uchar4 normal;
    short2 lightUV;
};


struct IndirectDrawArgs {
    atomic_uint vertexCount;
    uint instanceCount;
    uint firstVertex;
    uint firstInstance;
};


constant int3 faceOffsets[6] = {
    int3( 1,  0,  0),
    int3(-1,  0,  0),
    int3( 0,  1,  0),
    int3( 0, -1,  0),
    int3( 0,  0,  1),
    int3( 0,  0, -1)
};


constant float3 faceNormals[6] = {
    float3( 1,  0,  0),
    float3(-1,  0,  0),
    float3( 0,  1,  0),
    float3( 0, -1,  0),
    float3( 0,  0,  1),
    float3( 0,  0, -1)
};


uchar4 packNormal(float3 n) {
    return uchar4(
        uchar((n.x * 0.5 + 0.5) * 255.0),
        uchar((n.y * 0.5 + 0.5) * 255.0),
        uchar((n.z * 0.5 + 0.5) * 255.0),
        255
    );
}


bool isAir(uint blockId) {
    return blockId == 0;
}


bool isOccluding(device const uint* blockStates, int3 pos) {
    if (any(pos < 0) || any(pos >= 16)) return false;
    uint idx = uint(pos.y * 256 + pos.z * 16 + pos.x);
    uint id = blockStates[idx];

    return id != 0 && (id & 0x80000000u) == 0;
}

kernel void mesh_build(
    device const uint*      blockStates   [[buffer(0)]],
    device const uint*      blockTexIds   [[buffer(1)]],
    device const uchar4*    blockColors   [[buffer(2)]],
    device const uchar*     lightData     [[buffer(3)]],
    device MeshVertex*      vertices      [[buffer(4)]],
    device IndirectDrawArgs* drawArgs     [[buffer(5)]],
    constant MeshBuildParams& params      [[buffer(6)]],
    uint3 gid [[thread_position_in_grid]]
) {

    uint step = 1u << params.lodLevel;
    if ((gid.x % step) != 0 || (gid.y % step) != 0 || (gid.z % step) != 0) return;

    int3 blockPos = int3(gid);
    uint idx = gid.y * 256 + gid.z * 16 + gid.x;
    uint blockId = blockStates[idx];
    if (isAir(blockId)) return;

    float blockSize = float(step);
    float3 basePos = float3(gid) + float3(params.chunkOrigin);

    uchar4 tint = blockColors[idx];
    uint texId  = blockTexIds[idx];
    float texU  = float(texId & 0xFFFFu) * params.texScale;
    float texV  = float(texId >> 16)      * params.texScale;

    uchar lightByte = lightData[idx];
    short2 lightUV = short2(short(lightByte & 0xF) * 16, short(lightByte >> 4) * 16);


    for (uint face = 0; face < 6; face++) {
        int3 neighbour = blockPos + faceOffsets[face];
        if (isOccluding(blockStates, neighbour)) continue;


        uint base = atomic_fetch_add_explicit(&drawArgs->vertexCount, 4, memory_order_relaxed);
        if (base + 4 > params.maxQuads * 4) return;

        float3 n = faceNormals[face];
        uchar4 packedN = packNormal(n);


        float3 p0, p1, p2, p3;
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

        p0 = center - right * 0.5 - up * 0.5;
        p1 = center + right * 0.5 - up * 0.5;
        p2 = center + right * 0.5 + up * 0.5;
        p3 = center - right * 0.5 + up * 0.5;

        float2 uv0 = float2(texU,                     texV);
        float2 uv1 = float2(texU + params.texScale,   texV);
        float2 uv2 = float2(texU + params.texScale,   texV + params.texScale);
        float2 uv3 = float2(texU,                     texV + params.texScale);


        MeshVertex mv;
        mv.color   = tint;
        mv.normal  = packedN;
        mv.lightUV = lightUV;

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
