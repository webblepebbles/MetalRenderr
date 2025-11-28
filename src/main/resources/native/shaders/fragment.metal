
#include <metal_stdlib>
using namespace metal;

struct FragmentIn {
    float4 position [[position]];
    float2 texCoord;
    half3 color;
    half blockLight;
    half skyLight;
};

fragment half4 fragment_main(
    FragmentIn in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    sampler blockSampler [[sampler(0)]]
) {
    // Sample block texture
    half4 texColor = blockAtlas.sample(blockSampler, in.texCoord);
    
    // Apply vertex color (tint + AO)
    half3 finalColor = texColor.rgb * in.color;
    
    // Apply lighting (simple for now - max of block and sky light)
    half light = max(in.blockLight, in.skyLight);
    finalColor *= light;
    
    // Discard transparent pixels
    if (texColor.a < 0.5h) {
        discard_fragment();
    }
    
    return half4(finalColor, texColor.a);
}

