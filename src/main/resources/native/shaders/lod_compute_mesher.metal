#include <metal_stdlib>
using namespace metal;

constant uint SECTION_SIZE = 16u;
constant uint BLOCKS_PER_SECTION = 4096u;  
constant uint MAX_FACES_PER_SECTION = 4096u * 6u;
constant uint VERTS_PER_FACE = 6u; 

struct CompactVertexGPU {
    uint word0;
    uint word1;
};

struct MeshUniforms {
    float originX;
    float originY;
    float originZ;
    uint  sectionIndex;
    uint  outputBaseVertex; 
    uint  maxOutputVerts;   
};

struct MeshCounters {
    atomic_uint vertexCount;
    atomic_uint faceCount;
};

constant int3 faceNormals[6] = {
    int3( 0, -1,  0), 
    int3( 0,  1,  0), 
    int3( 0,  0, -1), 
    int3( 0,  0,  1),
    int3(-1,  0,  0),
    int3( 1,  0,  0),  
};

constant half3 faceCorners[6][4] = {
    { half3(0,0,0), half3(1,0,0), half3(1,0,1), half3(0,0,1) },
    { half3(0,1,0), half3(0,1,1), half3(1,1,1), half3(1,1,0) },
    { half3(0,0,0), half3(0,1,0), half3(1,1,0), half3(1,0,0) },
    { half3(0,0,1), half3(1,0,1), half3(1,1,1), half3(0,1,1) },
    { half3(0,0,0), half3(0,0,1), half3(0,1,1), half3(0,1,0) },
    { half3(1,0,0), half3(1,1,0), half3(1,1,1), half3(1,0,1) },
};

constant uint triIndices[6] = { 0, 1, 2, 0, 2, 3 };

inline uint blockIndex(uint x, uint y, uint z) {
    return (y << 8u) | (z << 4u) | x;
}

inline ushort floatToHalf(float v) {
    return as_type<ushort>(half(v));
}

kernel void lod_compute_count_faces(
    device const uint* blockStates          [[buffer(0)]],
    device atomic_uint* totalFaceCount      [[buffer(1)]],
    device uint* perBlockFaceMask           [[buffer(2)]], 
    constant uint& blockCount               [[buffer(3)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid >= blockCount) return;

    uint blockId = blockStates[tid];
    if (blockId == 0u) {
        perBlockFaceMask[tid] = 0u;
        return;
    }

    uint y = tid >> 8u;
    uint z = (tid >> 4u) & 0xFu;
    uint x = tid & 0xFu;

    uint faceMask = 0u;

    for (uint face = 0u; face < 6u; face++) {
        int3 n = faceNormals[face];
        int nx = int(x) + n.x;
        int ny = int(y) + n.y;
        int nz = int(z) + n.z;
        if (nx < 0 || nx >= 16 || ny < 0 || ny >= 16 || nz < 0 || nz >= 16) {
            faceMask |= (1u << face);
            continue;
        }

        uint neighborIdx = blockIndex(uint(nx), uint(ny), uint(nz));
        if (blockStates[neighborIdx] == 0u) {
            faceMask |= (1u << face);
        }
    }

    perBlockFaceMask[tid] = faceMask;

    uint faceCount = popcount(faceMask);
    if (faceCount > 0u) {
        atomic_fetch_add_explicit(totalFaceCount, faceCount, memory_order_relaxed);
    }
}

kernel void lod_compute_emit_faces(
    device const uint* blockStates          [[buffer(0)]],
    device const uchar* packedLight         [[buffer(1)]],
    device const uint* perBlockFaceMask     [[buffer(2)]],
    device CompactVertexGPU* outputVertices [[buffer(3)]],
    device MeshCounters* counters           [[buffer(4)]],
    constant MeshUniforms& uniforms         [[buffer(5)]],
    constant uint& blockCount               [[buffer(6)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid >= blockCount) return;

    uint faceMask = perBlockFaceMask[tid];
    if (faceMask == 0u) return;

    uint blockId = blockStates[tid];
    if (blockId == 0u) return;

    uint y = tid >> 8u;
    uint z = (tid >> 4u) & 0xFu;
    uint x = tid & 0xFu;
    uchar light = packedLight[tid];
    uint skyLight = (uint(light) >> 4u) & 0xFu;
    uint ao = 15u; 

    uint skyAo = (skyLight << 4u) | ao;
    uint colorIdx = blockId & 0xFFu;

    float bx = float(x);
    float by = float(y);
    float bz = float(z);

    for (uint face = 0u; face < 6u; face++) {
        if ((faceMask & (1u << face)) == 0u) continue;
        uint baseVertex = atomic_fetch_add_explicit(&counters->vertexCount,
                                                     VERTS_PER_FACE,
                                                     memory_order_relaxed);

        if (baseVertex + VERTS_PER_FACE > uniforms.maxOutputVerts) {
            return; 
        }

        uint outputBase = uniforms.outputBaseVertex + baseVertex;

        for (uint ti = 0u; ti < VERTS_PER_FACE; ti++) {
            uint cornerIdx = triIndices[ti];
            half3 corner = faceCorners[face][cornerIdx];

            float vx = bx + float(corner.x);
            float vy = by + float(corner.y);
            float vz = bz + float(corner.z);

            CompactVertexGPU vtx;
            vtx.word0 = uint(floatToHalf(vx)) | (uint(floatToHalf(vy)) << 16u);
            vtx.word1 = uint(floatToHalf(vz)) | (colorIdx << 16u) | (skyAo << 24u);

            outputVertices[outputBase + ti] = vtx;
        }

        atomic_fetch_add_explicit(&counters->faceCount, 1u, memory_order_relaxed);
    }
}
kernel void lod_compute_clear_counters(
    device MeshCounters* counters [[buffer(0)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid == 0u) {
        atomic_store_explicit(&counters->vertexCount, 0u, memory_order_relaxed);
        atomic_store_explicit(&counters->faceCount, 0u, memory_order_relaxed);
    }
}
