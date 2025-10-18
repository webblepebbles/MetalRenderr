
#include <metal_stdlib>
using namespace metal;
struct Aabb {
    half3 minBounds;
    half3 maxBounds;
};
struct OcclusionConstants {
    uint count;
};
kernel void occlusion_test(const device Aabb* aabbs [[buffer(0)]], constant half4x4& viewProj [[buffer(1)]], device uchar* results [[buffer(2)]], constant OcclusionConstants& constants [[buffer(3)]], uint id [[thread_position_in_grid]]) {
    if (id >= constants.count) return;
    Aabb box = aabbs[id];
    
    // Unrolled corner checking for better performance
    half3 minB = box.minBounds;
    half3 maxB = box.maxBounds;
    
    bool visible = false;
    half minDepth = half(1.0);
    half maxDepth = half(-1.0);
    
    // Check all 8 corners (unrolled)
    #define CHECK_CORNER(x, y, z) { \
        half4 clip = viewProj * half4(x, y, z, half(1.0)); \
        if (clip.w != half(0.0)) { \
            half3 ndc = clip.xyz / clip.w; \
            minDepth = min(minDepth, ndc.z); \
            maxDepth = max(maxDepth, ndc.z); \
            visible = visible || (abs(ndc.x) <= half(1.05) && abs(ndc.y) <= half(1.05) && abs(ndc.z) <= half(1.05)); \
        } \
    }
    
    CHECK_CORNER(minB.x, minB.y, minB.z)
    CHECK_CORNER(maxB.x, minB.y, minB.z)
    CHECK_CORNER(minB.x, maxB.y, minB.z)
    CHECK_CORNER(maxB.x, maxB.y, minB.z)
    CHECK_CORNER(minB.x, minB.y, maxB.z)
    CHECK_CORNER(maxB.x, minB.y, maxB.z)
    CHECK_CORNER(minB.x, maxB.y, maxB.z)
    CHECK_CORNER(maxB.x, maxB.y, maxB.z)
    
    #undef CHECK_CORNER
    
    bool culled = !visible || minDepth > half(1.02) || maxDepth < half(-1.02);
    results[id] = culled ? uchar(1) : uchar(0);
}
