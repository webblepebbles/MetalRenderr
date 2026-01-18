/**
 * MetalRender Entity Shaders
 * 
 * Entity vertex format (32 bytes per vertex):
 * - bytes 0-11:  Position (float3) - world-space
 * - bytes 12-19: UV (float2) - texture coordinates
 * - bytes 20-23: Color (RGBA8) - packed color with lighting
 * - bytes 24-27: Normal (10-10-10-2) - packed normal vector
 * - bytes 28-31: Padding
 */

#include <metal_stdlib>
using namespace metal;

// ============================================================================
// Entity-Specific Structures
// ============================================================================

struct EntityFrameUniforms {
    float4x4 viewProj;          // View-projection matrix
    float4 cameraPos;           // Camera world position
    float4 fogColor;            // Fog color RGBA
    float4 fogParams;           // start, end, density, unused
};

struct EntityVertexIn {
    float3 position [[attribute(0)]];   // bytes 0-11
    float2 texCoord [[attribute(1)]];   // bytes 12-19
    uint color [[attribute(2)]];         // bytes 20-23 (packed RGBA8)
    uint packedNormal [[attribute(3)]]; // bytes 24-27 (10-10-10-2)
};

struct EntityVertexOut {
    float4 position [[position]];
    float2 texCoord;
    half4 color;
    float3 normal;
    float distance;  // Distance from camera for fog
};

// ============================================================================
// Entity Vertex Decoding
// ============================================================================

// Decode color from packed RGBA8
half4 decodeEntityColor(uint colorPacked) {
    return half4(
        half((colorPacked >> 0u) & 0xFFu) / 255.0h,   // R
        half((colorPacked >> 8u) & 0xFFu) / 255.0h,   // G
        half((colorPacked >> 16u) & 0xFFu) / 255.0h,  // B
        half((colorPacked >> 24u) & 0xFFu) / 255.0h   // A
    );
}

// Decode normal from 10-10-10-2 packed format
float3 decodeEntityNormal(uint packedNormal) {
    float nx = float((packedNormal >> 0u) & 0x3FFu) / 1023.0f * 2.0f - 1.0f;
    float ny = float((packedNormal >> 10u) & 0x3FFu) / 1023.0f * 2.0f - 1.0f;
    float nz = float((packedNormal >> 20u) & 0x3FFu) / 1023.0f * 2.0f - 1.0f;
    return normalize(float3(nx, ny, nz));
}

// ============================================================================
// Entity Depth-Only Vertex Shader
// ============================================================================

vertex float4 entity_depth_vertex(
    uint vertexId [[vertex_id]],
    constant float* vertexData [[buffer(0)]],      // Raw float data
    constant EntityFrameUniforms& frame [[buffer(1)]]
) {
    // 32 bytes = 8 floats per vertex, but position is first 3 floats
    uint base = vertexId * 8u;
    
    float3 worldPos = float3(
        vertexData[base + 0u],
        vertexData[base + 1u],
        vertexData[base + 2u]
    );
    
    // Transform to camera-relative coordinates
    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;
    
    return frame.viewProj * float4(cameraRelativePos, 1.0);
}

// ============================================================================
// Entity Color Vertex Shader
// ============================================================================

vertex EntityVertexOut entity_color_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],       // Raw uint data for proper bit access
    constant EntityFrameUniforms& frame [[buffer(1)]]
) {
    // 32 bytes = 8 uints per vertex
    uint base = vertexId * 8u;
    
    // Read position as floats (reinterpret uints)
    // NOTE: These positions are already transformed by the model matrix in Java
    // They are in world space coordinates
    float3 worldPos = float3(
        as_type<float>(vertexData[base + 0u]),
        as_type<float>(vertexData[base + 1u]),
        as_type<float>(vertexData[base + 2u])
    );
    
    // Read UV as floats
    float2 uv = float2(
        as_type<float>(vertexData[base + 3u]),
        as_type<float>(vertexData[base + 4u])
    );
    
    // Read packed color and normal
    uint colorPacked = vertexData[base + 5u];
    uint normalPacked = vertexData[base + 6u];
    // base + 7u is padding
    
    // Transform to camera-relative coordinates for rendering
    // The viewProj matrix is set up for camera-relative rendering
    float3 cameraRelativePos = worldPos - frame.cameraPos.xyz;
    float dist = length(cameraRelativePos);
    
    EntityVertexOut out;
    out.position = frame.viewProj * float4(cameraRelativePos, 1.0);
    out.texCoord = uv;
    out.color = decodeEntityColor(colorPacked);
    out.normal = decodeEntityNormal(normalPacked);
    out.distance = dist;
    
    return out;
}

// ============================================================================
// Entity Color Fragment Shader (with texture)
// ============================================================================

fragment half4 entity_color_fragment(
    EntityVertexOut in [[stage_in]],
    texture2d<half, access::sample> entityTexture [[texture(0)]],
    sampler entitySampler [[sampler(0)]]
) {
    // Sample entity texture
    half4 texColor = entityTexture.sample(entitySampler, in.texCoord);
    
    // Alpha test - discard transparent pixels
    if (texColor.a < 0.1h) {
        discard_fragment();
    }
    
    // Apply vertex color (includes pre-computed lighting from Minecraft)
    // The vertex color RGB channels contain lighting info, so multiplying
    // the texture by vertex color applies the lighting.
    half4 finalColor = texColor * in.color;
    
    // Don't apply additional lighting - Minecraft's vertex colors already have lighting baked in
    // The lighting was computed by Minecraft based on sky light, block light, etc.
    
    return finalColor;
}

// ============================================================================
// Entity Color-Only Fragment Shader (no texture)
// Used when no texture is bound - just uses vertex color
// ============================================================================

fragment half4 entity_color_only_fragment(
    EntityVertexOut in [[stage_in]]
) {
    // Just use vertex color directly (includes pre-computed lighting)
    half4 finalColor = in.color;
    
    // Alpha test
    if (finalColor.a < 0.1h) {
        discard_fragment();
    }
    
    // Simple directional light for basic shading
    float3 lightDir = normalize(float3(0.3, 1.0, 0.5));
    float ndotl = max(dot(in.normal, lightDir), 0.0f);
    float ambient = 0.4f;
    float lighting = ambient + (1.0f - ambient) * ndotl;
    
    finalColor.rgb *= half(lighting);
    
    return finalColor;
}

// ============================================================================
// Entity with Vertex Descriptor (alternative approach)
// ============================================================================

vertex EntityVertexOut entity_color_vertex_vd(
    EntityVertexIn in [[stage_in]],
    constant EntityFrameUniforms& frame [[buffer(1)]]
) {
    // Transform to camera-relative coordinates
    float3 cameraRelativePos = in.position - frame.cameraPos.xyz;
    float dist = length(cameraRelativePos);
    
    EntityVertexOut out;
    out.position = frame.viewProj * float4(cameraRelativePos, 1.0);
    out.texCoord = in.texCoord;
    out.color = decodeEntityColor(in.color);
    out.normal = decodeEntityNormal(in.packedNormal);
    out.distance = dist;
    
    return out;
}
