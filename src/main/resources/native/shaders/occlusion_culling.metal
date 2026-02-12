#include <metal_stdlib>
using namespace metal;
struct Aabb {
    half3 minBounds;
    half3 maxBounds;
};
struct OcclusionConstants {
    uint count;
};
kernel void occlusion_test(const device Aabb* aabbs [[buffer(0)]], constant float4x4& viewProj [[buffer(1)]], device uchar* results [[buffer(2)]], constant OcclusionConstants& constants [[buffer(3)]], uint id [[thread_position_in_grid]]) {
    if (id >= constants.count) return;
    Aabb box = aabbs[id];
    half3 corners[8];
    corners[0] = half3(box.minBounds.x, box.minBounds.y, box.minBounds.z);
    corners[1] = half3(box.maxBounds.x, box.minBounds.y, box.minBounds.z);
    corners[2] = half3(box.minBounds.x, box.maxBounds.y, box.minBounds.z);
    corners[3] = half3(box.maxBounds.x, box.maxBounds.y, box.minBounds.z);
    corners[4] = half3(box.minBounds.x, box.minBounds.y, box.maxBounds.z);
    corners[5] = half3(box.maxBounds.x, box.minBounds.y, box.maxBounds.z);
    corners[6] = half3(box.minBounds.x, box.maxBounds.y, box.maxBounds.z);
    corners[7] = half3(box.maxBounds.x, box.maxBounds.y, box.maxBounds.z);
    bool visible = false;
    float minDepth = 1.0f;
    float maxDepth = -1.0f;
    for (uint i = 0; i < 8; ++i) {
        float3 corner_f = float3(corners[i]);
        float4 clip = viewProj * float4(corner_f, 1.0f);
        if (clip.w == 0.0f) continue;
        float3 ndc = clip.xyz / clip.w;
        minDepth = min(minDepth, ndc.z);
        maxDepth = max(maxDepth, ndc.z);
        if (ndc.x >= -1.05f && ndc.x <= 1.05f && ndc.y >= -1.05f && ndc.y <= 1.05f && ndc.z >= -1.05f && ndc.z <= 1.05f) {
            visible = true;
        }
    }
    bool culled = !visible || minDepth > 1.02f || maxDepth < -1.02f;
    results[id] = culled ? uchar(1) : uchar(0);
}
