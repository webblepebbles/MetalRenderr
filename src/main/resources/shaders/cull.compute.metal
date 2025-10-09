#include <metal_stdlib>
using namespace metal;

kernel void cull_pass(device uint* visibility [[buffer(0)]],
                      uint gid [[thread_position_in_grid]]) {
    // First pass: mark all as visible
    visibility[gid] = 1u;
}
