#include <metal_stdlib>
using namespace metal;
struct CullParams {
    float4x4 viewProjection;
    float4   cameraPos;
    float4   frustumPlanes[6];
    float    maxDrawDist;
    uint     regionCount;
    uint     maxDrawCalls;
};

struct RegionAABB {
    float3 minCorner;
    float  _pad0;
    float3 maxCorner;
    float  _pad1;
};

struct IndirectIndexedArgs {
    uint indexCount;
    uint instanceCount;
    uint indexStart;
    int  baseVertex;
    uint baseInstance;
};

struct RegionDrawInfo {
    uint  vertexBufferIndex;
    uint  indexBufferIndex;
    uint  indexCount;
    uint  baseIndex;
    int   baseVertex;
    uint  regionId;
};

bool aabbOutsidePlane(float3 minC, float3 maxC, float4 plane) {
    float3 pVertex;
    pVertex.x = (plane.x > 0.0) ? maxC.x : minC.x;
    pVertex.y = (plane.y > 0.0) ? maxC.y : minC.y;
    pVertex.z = (plane.z > 0.0) ? maxC.z : minC.z;
    return (dot(plane.xyz, pVertex) + plane.w) < 0.0;
}


bool isInsideFrustum(float3 minC, float3 maxC, constant float4* planes) {
    for (uint i = 0; i < 6; i++) {
        if (aabbOutsidePlane(minC, maxC, planes[i])) return false;
    }
    return true;
}

kernel void cull_pass(
    device const RegionAABB*     regions      [[buffer(0)]],
    device const RegionDrawInfo* drawInfos    [[buffer(1)]],
    device uint*                 visibility   [[buffer(2)]],
    device IndirectIndexedArgs*  indirectArgs [[buffer(3)]],
    device atomic_uint*          drawCount    [[buffer(4)]],
    constant CullParams&         params       [[buffer(5)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= params.regionCount) return;

    RegionAABB aabb = regions[gid];
    float3 minC = aabb.minCorner;
    float3 maxC = aabb.maxCorner;


    bool visible = isInsideFrustum(minC, maxC, params.frustumPlanes);


    if (visible) {
        float3 closest = clamp(params.cameraPos.xyz, minC, maxC);
        float3 diff = closest - params.cameraPos.xyz;
        float distSq = dot(diff, diff);
        if (distSq > params.maxDrawDist) {
            visible = false;
        }
    }

    visibility[gid] = visible ? 1u : 0u;


    if (visible) {
        uint outIdx = atomic_fetch_add_explicit(drawCount, 1, memory_order_relaxed);
        if (outIdx < params.maxDrawCalls) {
            RegionDrawInfo info = drawInfos[gid];
            IndirectIndexedArgs args;
            args.indexCount    = info.indexCount;
            args.instanceCount = 1;
            args.indexStart    = info.baseIndex;
            args.baseVertex    = info.baseVertex;
            args.baseInstance  = info.regionId;
            indirectArgs[outIdx] = args;
        }
    }
}





kernel void cull_frustum_only(
    device const RegionAABB* regions    [[buffer(0)]],
    device uint*             visibility [[buffer(1)]],
    constant CullParams&     params     [[buffer(2)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= params.regionCount) return;

    RegionAABB aabb = regions[gid];
    bool visible = isInsideFrustum(aabb.minCorner, aabb.maxCorner, params.frustumPlanes);

    if (visible) {
        float3 closest = clamp(params.cameraPos.xyz, aabb.minCorner, aabb.maxCorner);
        float3 diff = closest - params.cameraPos.xyz;
        float distSq = dot(diff, diff);
        if (distSq > params.maxDrawDist) visible = false;
    }

    visibility[gid] = visible ? 1u : 0u;
}
