#include <metal_stdlib>
using namespace metal;
struct LodParams {
    float4   cameraPos;
    float4   cameraForward;
    float    lod0Distance;
    float    lod1Distance;
    float    lod2Distance;
    float    lod3Distance;
    float    lodTransitionWidth;
    float    screenHeight;
    float    fovTanHalf;
    uint     regionCount;
    uint     frameIndex;
    uint     _pad0;
    uint     _pad1;
    uint     _pad2;
};
struct RegionAABB {
    float3 minCorner;
    float  _pad0;
    float3 maxCorner;
    float  _pad1;
};
struct RegionLodState {
    uint currentLod;
    uint framesAtLod;
};
struct LodResult {
    uint lodLevel;
    uint transition;
};
kernel void lod_select(
    device const RegionAABB*     regions     [[buffer(0)]],
    device const RegionLodState* prevState   [[buffer(1)]],
    device LodResult*            results     [[buffer(2)]],
    device RegionLodState*       newState    [[buffer(3)]],
    constant LodParams&          params      [[buffer(4)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= params.regionCount) return;
    RegionAABB aabb = regions[gid];
    float3 closest = clamp(params.cameraPos.xyz, aabb.minCorner, aabb.maxCorner);
    float dist = length(closest - params.cameraPos.xyz);
    float3 extent = aabb.maxCorner - aabb.minCorner;
    float regionSize = length(extent);
    float projectedSize = (regionSize / max(dist, 0.01)) * params.screenHeight / (2.0 * params.fovTanHalf);
    uint baseLod = 4;
    if (dist < params.lod0Distance) baseLod = 0;
    else if (dist < params.lod1Distance) baseLod = 1;
    else if (dist < params.lod2Distance) baseLod = 2;
    else if (dist < params.lod3Distance) baseLod = 3;
    if (projectedSize > 200.0 && baseLod > 0) baseLod = max(baseLod - 1, 0u);
    if (projectedSize > 400.0 && baseLod > 0) baseLod = max(baseLod - 1, 0u);
    RegionLodState prev = prevState[gid];
    uint desiredLod = baseLod;
    bool transitioning = false;
    if (desiredLod != prev.currentLod) {
        float threshold = 0.0;
        if (desiredLod < prev.currentLod) {
            threshold = params.lodTransitionWidth;
        } else {
            threshold = -params.lodTransitionWidth;
        }
        float lodBoundary;
        switch (prev.currentLod) {
            case 0: lodBoundary = params.lod0Distance; break;
            case 1: lodBoundary = params.lod1Distance; break;
            case 2: lodBoundary = params.lod2Distance; break;
            case 3: lodBoundary = params.lod3Distance; break;
            default: lodBoundary = params.lod3Distance * 2.0; break;
        }
        if (abs(dist - lodBoundary) < params.lodTransitionWidth) {
            if (prev.framesAtLod < 30) {
                desiredLod = prev.currentLod;
            }
            transitioning = true;
        }
    }
    LodResult result;
    result.lodLevel = desiredLod;
    result.transition = transitioning ? 1 : 0;
    results[gid] = result;
    RegionLodState state;
    state.currentLod = desiredLod;
    state.framesAtLod = (desiredLod == prev.currentLod) ? (prev.framesAtLod + 1) : 0;
    newState[gid] = state;
}
