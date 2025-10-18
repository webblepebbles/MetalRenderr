
#include <metal_stdlib>
using namespace metal;

// Optimized: Use fast math and precomputed constants
fragment half4 fragment_main(half3 normal [[stage_in]]) {
    // Precomputed normalized light direction (avoids runtime normalize)
    constant half3 lightDir = half3(0.408248, 0.816497, 0.408248);
    
    // Fast dot product with clamped result
    half diff = fmax(dot(normal, lightDir), half(0.2));
    
    // Optimized color computation
    half3 color = half3(0.7, 0.8, 1.0) * diff;
    return half4(color, 1.0);
}
