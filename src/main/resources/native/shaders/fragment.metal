
#include <metal_stdlib>
using namespace metal;
fragment half4 fragment_main(half3 normal [[stage_in]]) {
    half3 lightDir = normalize(half3(0.5h, 1.0h, 0.7h));
    half diff = max(dot(normal, lightDir), half(0.2));
    half3 color = half3(0.7h, 0.8h, 1.0h) * diff;
    return half4(color, 1.0h);
}
