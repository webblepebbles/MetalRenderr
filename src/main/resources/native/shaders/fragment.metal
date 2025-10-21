
#include <metal_stdlib>
using namespace metal;
fragment float4 fragment_main(half3 normal [[stage_in]]) {
    half3 lightDir = normalize(half3(0.5, 1.0, 0.7));
    float diff = max(dot(normal, lightDir), 0.2);
    half3 color = half3(0.7, 0.8, 1.0) * diff;
    return float4(color, 1.0);
}
