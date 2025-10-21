#include <metal_stdlib>
using namespace metal;

struct VertexIn {
    float4 position
    half3 normal
};

struct VertexOut {
    float4 position [[position]];
    half3 normal;
};

vertex VertexOut vertex_main(VertexIn in [[stage_in]]) {
    VertexOut out;
    out.position = in.position;
    out.normal = in.normal;
    return out;
}
