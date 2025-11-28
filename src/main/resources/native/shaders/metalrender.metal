/**
 * MetalRender Terrain Shaders
 * 
 * Decodes Sodium's CompactChunkVertex format (20 bytes):
 * - bytes 0-3:   packPositionHi (x10|y10|z10|unused2)
 * - bytes 4-7:   packPositionLo (x10|y10|z10|unused2)
 * - bytes 8-11:  color ARGB (ColorARGB format: a<<24|r<<16|g<<8|b, with AO pre-applied to RGB)
 * - bytes 12-15: texU[15+sign] | texV[15+sign]
 * - bytes 16-19: light[16] | material[8] | sectionIndex[8]
 */

#include <metal_stdlib>
using namespace metal;

constant uint POSITION_MAX_VALUE = 1u << 20u;  // 1048576 for 20-bit position
constant float MODEL_ORIGIN = 8.0f;            // Section-local origin offset
constant float MODEL_RANGE = 32.0f;            // Range of position values
constant float VERTEX_SCALE = MODEL_RANGE / float(POSITION_MAX_VALUE);  // 32/1048576
constant float VERTEX_OFFSET = -MODEL_ORIGIN;  // -8.0

constant uint TEXTURE_MAX_VALUE = 1u << 15u;   // 32768 for 15-bit texture coords
constant float TEXTURE_SCALE = 1.0f / float(TEXTURE_MAX_VALUE);

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
    float4 texCoordShrink;      // Texture coordinate adjustment
};

struct TerrainVertexOut {
    float4 position [[position]];
    float2 texCoord;
    half4 color;          // RGBA with AO
    half2 light;          // block light, sky light
};

// ============================================================================
// Vertex Decoding Functions
// ============================================================================

// Decode 20-bit position from two packed 32-bit values
// Each axis uses 10 bits from Hi and 10 bits from Lo
float3 decodePosition(uint posHi, uint posLo) {
    // Extract high 10 bits from posHi
    uint xHi = (posHi >> 0u) & 0x3FFu;
    uint yHi = (posHi >> 10u) & 0x3FFu;
    uint zHi = (posHi >> 20u) & 0x3FFu;
    
    // Extract low 10 bits from posLo
    uint xLo = (posLo >> 0u) & 0x3FFu;
    uint yLo = (posLo >> 10u) & 0x3FFu;
    uint zLo = (posLo >> 20u) & 0x3FFu;
    
    // Combine to 20-bit values and scale
    float x = float((xHi << 10u) | xLo) * VERTEX_SCALE + VERTEX_OFFSET;
    float y = float((yHi << 10u) | yLo) * VERTEX_SCALE + VERTEX_OFFSET;
    float z = float((zHi << 10u) | zLo) * VERTEX_SCALE + VERTEX_OFFSET;
    
    return float3(x, y, z);
}

// Decode texture coordinate with Sodium's bias handling
// The 16th bit indicates the direction of sub-pixel bias
float2 decodeTexCoord(uint texPacked, float2 shrink) {
    uint uRaw = texPacked & 0xFFFFu;
    uint vRaw = (texPacked >> 16u) & 0xFFFFu;
    
    // Base 15-bit coordinates
    float2 baseCoords = float2(
        float(uRaw & 0x7FFFu),
        float(vRaw & 0x7FFFu)
    ) * TEXTURE_SCALE;
    
    // Bias direction from sign bit
    float2 bias = float2(
        (uRaw & 0x8000u) ? 1.0f : -1.0f,
        (vRaw & 0x8000u) ? 1.0f : -1.0f
    );
    
    return baseCoords + (bias * shrink);
}

// Decode ARGB color (already has AO applied)
// Sodium uses ColorARGB.pack(r,g,b,a) = (a<<24)|(r<<16)|(g<<8)|b
// In little-endian memory: [B, G, R, A]
half4 decodeColor(uint packed) {
    return half4(
        half((packed >> 16u) & 0xFFu) / 255.0h,  // R (byte 2)
        half((packed >> 8u) & 0xFFu) / 255.0h,   // G (byte 1)
        half((packed >> 0u) & 0xFFu) / 255.0h,   // B (byte 0)
        half((packed >> 24u) & 0xFFu) / 255.0h   // A (byte 3)
    );
}

// Decode light values (block light and sky light)
// Sodium encodes as: clamp(light + 8, 8, 248) for each channel
// This maps Minecraft's 0-15 light levels to 8-248 range
// We remap 8-248 back to ~0-1 range for rendering
half2 decodeLight(uint lightMaterial) {
    uint blockLight = lightMaterial & 0xFFu;
    uint skyLight = (lightMaterial >> 8u) & 0xFFu;
    // Subtract 8 and divide by 240 to get 0-1 range from 8-248 input
    // Formula: (value - 8) / 240
    return half2(
        half(max(0u, blockLight - 8u)) / 240.0h,
        half(max(0u, skyLight - 8u)) / 240.0h
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
    
    float3 localPos = decodePosition(posHi, posLo);
    float3 worldPos = localPos + float3(draw.originX, draw.originY, draw.originZ);
    
    return frame.viewProj * float4(worldPos, 1.0);
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
    uint colorPacked = vertexData[base + 2u];
    uint texPacked = vertexData[base + 3u];
    uint lightMaterial = vertexData[base + 4u];
    
    // Decode position
    float3 localPos = decodePosition(posHi, posLo);
    float3 worldPos = localPos + float3(draw.originX, draw.originY, draw.originZ);
    
    // Output
    TerrainVertexOut out;
    out.position = frame.viewProj * float4(worldPos, 1.0);
    out.texCoord = decodeTexCoord(texPacked, frame.texCoordShrink.xy);
    out.color = decodeColor(colorPacked);
    out.light = decodeLight(lightMaterial);
    
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
    // Sample block texture
    half4 texColor = atlas.sample(atlasSampler, in.texCoord);
    
    // Alpha test - transparent pixels are common in atlas (e.g., leaves)
    // Fallback to vertex color if texture alpha is low so we can debug geometry
    // Note: We avoid discarding here so the pipeline will show geometry even
    // if the atlas sampling returns zero alpha (for debugging).
    half3 finalColor;
    if (texColor.a < 0.5h) {
        finalColor = in.color.rgb; // use vertex color fallback
    } else {
        finalColor = texColor.rgb * in.color.rgb;
    }
    
    // Apply Minecraft-style lighting
    // Sky light can be reduced by weather/time, block light is constant
    // Combined: max(blockLight, skyLight * daylight) in vanilla
    // For now, use max and ensure minimum ambient of ~0.05 (light level 0.8)
    half light = max(in.light.x, in.light.y);
    // Ensure minimum ambient so caves aren't completely black
    // This mimics Minecraft's ambient occlusion minimum
    light = max(light, 0.03h);
    finalColor *= light;
    
    return half4(finalColor, texColor.a);
}

