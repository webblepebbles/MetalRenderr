#include <metal_stdlib>
using namespace metal;

struct Vertex { float3 pos; };

kernel void mesh_build(device const uint* input [[buffer(0)]],
                       device Vertex* vertices [[buffer(1)]],
                       uint gid [[thread_position_in_grid]]) {

    if (gid == 0) {
        vertices[0].pos = float3(0.0, 0.0, 0.0);
    }
}
