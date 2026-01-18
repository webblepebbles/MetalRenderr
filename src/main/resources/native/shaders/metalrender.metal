#include <metal_stdlib>
using namespace metal;

// Sodium COMPACT format constants
constant float MODEL_ORIGIN = 8.0f;
constant float MODEL_RANGE = 32.0f;
constant uint POSITION_MAX_VALUE = 1u << 20u;  // 1048576
constant uint TEXTURE_MAX_VALUE = 1u << 15u;   // 32768

struct DrawUniforms {
    float originX;      // Section world X
    float originY;      // Section world Y  
    float originZ;      // Section world Z
    float padding;      // Alignment
};

struct FrameUniforms {
    float4x4 viewProj;          // View-projection matrix
    float4 cameraPos;           // Camera world position
    float4 fogColor;            // Fog color RGBA
    float4 fogParams;           // start, end, density, unused
    float4 texCoordShrink;      // Texture coordinate adjustment (not used)
};

struct TerrainVertexOut {
    float4 position [[position]];
    float2 texCoord;
    half4 color;          // RGBA with AO
    half2 light;          // block light, sky light
};

// ============================================================================
// Sodium COMPACT Format Decoding (20 bytes per vertex)
// ============================================================================

// Decode 20-bit deinterleaved position from posHi and posLo
float3 decodePosition(uint posHi, uint posLo, float3 origin) {
    // Extract 10-bit hi and lo parts for each axis
    uint xHi = (posHi >> 0u) & 0x3FFu;
    uint yHi = (posHi >> 10u) & 0x3FFu;
    uint zHi = (posHi >> 20u) & 0x3FFu;
    
    uint xLo = (posLo >> 0u) & 0x3FFu;
    uint yLo = (posLo >> 10u) & 0x3FFu;
    uint zLo = (posLo >> 20u) & 0x3FFu;
    
    // Combine to 20-bit values
    uint qx = (xHi << 10u) | xLo;
    uint qy = (yHi << 10u) | yLo;
    uint qz = (zHi << 10u) | zLo;
    
    // Decode: pos = (quantized / 1048576) * 32.0 - 8.0 + origin
    float x = (float(qx) / float(POSITION_MAX_VALUE)) * MODEL_RANGE - MODEL_ORIGIN + origin.x;
    float y = (float(qy) / float(POSITION_MAX_VALUE)) * MODEL_RANGE - MODEL_ORIGIN + origin.y;
    float z = (float(qz) / float(POSITION_MAX_VALUE)) * MODEL_RANGE - MODEL_ORIGIN + origin.z;
    
    return float3(x, y, z);
}

// Decode texture coordinates - 15-bit with sign bit for bias
float2 decodeTexCoord(uint texPacked) {
    uint uRaw = texPacked & 0xFFFFu;
    uint vRaw = (texPacked >> 16u) & 0xFFFFu;
    // Extract 15-bit value (bit 15 is bias, not part of coordinate)
    float u = float(uRaw & 0x7FFFu) / float(TEXTURE_MAX_VALUE);
    float v = float(vRaw & 0x7FFFu) / float(TEXTURE_MAX_VALUE);
    return float2(u, v);
}

// Decode color from Sodium format (ARGB order - ColorARGB)
half4 decodeColor(uint colorWord) {
    // TEST 44: Swap R and B to fix biome tint colors (water, grass)
    // Original assumed Java int ARGB → bytes B,G,R,A (little-endian)
    // But empirically water/grass are swapped, so try the opposite
    return half4(
        half((colorWord >> 0u) & 0xFFu) / 255.0h,   // R from byte 0 (was B)
        half((colorWord >> 8u) & 0xFFu) / 255.0h,   // G from byte 1
        half((colorWord >> 16u) & 0xFFu) / 255.0h,  // B from byte 2 (was R)
        1.0h  // Alpha forced to 1.0 for opaque terrain
    );
}

// Decode light values from light word
half2 decodeLight(uint lightWord) {
    uint block = (lightWord >> 0u) & 0xFFu;
    uint sky = (lightWord >> 8u) & 0xFFu;
    return half2(
        half(block) / 255.0h,
        half(sky) / 255.0h
    );
}

// ============================================================================
// Depth-Only Vertex Shader (for depth pre-pass)
// ============================================================================

vertex float4 terrain_depth_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant DrawUniforms& draw [[buffer(1)]],
    constant FrameUniforms& frame [[buffer(2)]]
) {
    // 20 bytes = 5 uints per vertex
    uint base = vertexId * 5u;
    
    uint posHi = vertexData[base + 0u];
    uint posLo = vertexData[base + 1u];
    
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    float3 worldPos = decodePosition(posHi, posLo, origin);
    
    // Sodium's modelView has NO camera translation! We must subtract camera position.
    // This gives us camera-relative coordinates for the view matrix.
    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;
    
    return frame.viewProj * float4(cameraRelativePos, 1.0);
}

// ============================================================================
// Color Vertex Shader (main terrain pass)
// ============================================================================

vertex TerrainVertexOut terrain_color_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant DrawUniforms& draw [[buffer(1)]],
    constant FrameUniforms& frame [[buffer(2)]]
) {
    // 20 bytes = 5 uints per vertex
    uint base = vertexId * 5u;
    
    uint posHi = vertexData[base + 0u];
    uint posLo = vertexData[base + 1u];
    uint color = vertexData[base + 2u];
    uint texPacked = vertexData[base + 3u];
    uint lightData = vertexData[base + 4u];
    
    // Get section origin in world space
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    
    // Decode world position
    float3 worldPos = decodePosition(posHi, posLo, origin);
    
    // Sodium's modelView has NO camera translation! We must subtract camera position.
    // This gives us camera-relative coordinates for the view matrix.
    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;
    
    TerrainVertexOut out;
    float4 clipPos = frame.viewProj * float4(cameraRelativePos, 1.0);
    
    // TEST 65: Check if Y flip is needed for Metal vs OpenGL coordinate system
    // Metal has Y-down in framebuffer, OpenGL has Y-up
    // The projection from OpenGL might have incorrect Y mapping
    // Try NOT flipping to see if it helps the top 1/3 issue
    out.position = clipPos;
    
    out.texCoord = decodeTexCoord(texPacked);
    out.color = decodeColor(color);
    out.light = decodeLight(lightData);
    
    return out;
}

// ============================================================================
// Color Fragment Shader
// ============================================================================

fragment half4 terrain_color_fragment(
    TerrainVertexOut in [[stage_in]],
    texture2d<half, access::sample> atlas [[texture(0)]],
    sampler atlasSampler [[sampler(0)]]
) {
    // Sample the atlas texture
    half4 texColor = atlas.sample(atlasSampler, in.texCoord);
    
    // Discard transparent pixels (cutout for leaves, grass, etc.)
    // This prevents transparent areas from writing to depth buffer and
    // allows blocks behind to show through properly
    if (texColor.a < 0.5h) {
        discard_fragment();
    }
    
    // Apply vertex color (includes ambient occlusion from Sodium)
    half4 finalColor = texColor * in.color;
    
    // Apply lighting from Minecraft's light values
    // blockLight (in.light.x) = light from torches, lava, etc.
    // skyLight (in.light.y) = light from the sky
    // Combine them with sky light having priority when outdoors
    half blockLight = in.light.x;
    half skyLight = in.light.y;
    
    // Minecraft's light formula: max(blockLight, skyLight * dayLight)
    // Since we don't have dayLight info, use skyLight directly
    // The light values are already normalized 0-1 from Sodium
    half combinedLight = max(blockLight, skyLight);
    
    // Apply minimum ambient light (0.05) to prevent pitch black areas
    // This matches Minecraft's default minimum light level
    half lightLevel = max(combinedLight, 0.05h);
    
    // Apply lighting - only affects RGB, not alpha
    finalColor.rgb *= lightLevel;
    
    // Keep alpha from texture (will be 1.0 for non-discarded pixels)
    finalColor.a = texColor.a;
    
    return finalColor;
}

