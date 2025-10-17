
#include <metal_stdlib>
using namespace metal;
struct VertexIn {
    float4 position [[attribute(0)]];
    float3 normal [[attribute(1)]];
};
struct VertexOut {
    float4 position [[position]];
    float3 normal;
};
vertex VertexOut vertex_main(VertexIn in [[stage_in]]) {
    VertexOut out;
    out.position = in.position;
    out.normal = in.normal;
    return out;
}
