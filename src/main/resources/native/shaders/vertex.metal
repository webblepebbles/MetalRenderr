
#include <metal_stdlib>
using namespace metal;
struct VertexIn {
    half4 position [[attribute(0)]];
    half3 normal [[attribute(1)]];
};
struct VertexOut {
    half4 position [[position]];
    half3 normal;
};
vertex VertexOut vertex_main(VertexIn in [[stage_in]]) {
    VertexOut out;
    out.position = in.position;
    out.normal = in.normal;
    return out;
}
