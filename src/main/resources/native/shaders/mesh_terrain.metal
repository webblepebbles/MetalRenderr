#include <metal_stdlib>
#include <metal_mesh>
using namespace metal;
struct CameraUniforms {
    float4x4 viewProjection;
    float4x4 projection;
    float4x4 modelView;
    float4   cameraPosition;
    float4   frustumPlanes[6];
    float2   screenSize;
    float    nearPlane;
    float    farPlane;
    uint     frameIndex;
    uint     hizMipCount;
    uint     totalChunks;
    uint     _pad;
};
struct MeshletDescriptor {
    float4   aabbMin;          
    float4   aabbMax;          
    uint     vertexOffset;     
    uint     vertexCount;      
    uint     indexOffset;       
    uint     indexCount;        
    uint     chunkIndex;       
    uint     lodLevel;         
    uint     geometryCategory; 
    uint     _pad;
};
struct ChunkUniforms {
    float4   chunkOffset;      
};
struct InhouseTerrainVertex {
    packed_short3  position;
    packed_ushort2 texCoord;
    packed_uchar4  color;
    uchar          packedLight;
    uchar          normalIndex;
};
struct MeshVertexOut {
    float4 position [[position]];
    half2  texCoord;
    half4  color;
    half2  lightUV;
};
constant float3 kNormalTable[6] = {
    float3( 0,  1,  0),  
    float3( 0, -1,  0),  
    float3( 1,  0,  0),  
    float3(-1,  0,  0),  
    float3( 0,  0,  1),  
    float3( 0,  0, -1),  
};
struct MeshletPayload {
    uint meshletIndex;
};
[[object]]
void object_terrain(
    object_data MeshletPayload&              payload    [[payload]],
    mesh_grid_properties                     grid,
    device const MeshletDescriptor*          meshlets   [[buffer(0)]],
    constant CameraUniforms&                 camera     [[buffer(1)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid >= camera.totalChunks) {
        grid.set_threadgroups_per_grid(uint3(0, 0, 0));
        return;
    }
    MeshletDescriptor m = meshlets[tid];
    float3 minC = m.aabbMin.xyz;
    float3 maxC = m.aabbMax.xyz;
    for (uint i = 0; i < 6; i++) {
        float4 plane = camera.frustumPlanes[i];
        float3 pVertex;
        pVertex.x = (plane.x > 0.0) ? maxC.x : minC.x;
        pVertex.y = (plane.y > 0.0) ? maxC.y : minC.y;
        pVertex.z = (plane.z > 0.0) ? maxC.z : minC.z;
        if (dot(plane.xyz, pVertex) + plane.w < 0.0) {
            grid.set_threadgroups_per_grid(uint3(0, 0, 0));
            return;
        }
    }
    payload.meshletIndex = tid;
    grid.set_threadgroups_per_grid(uint3(1, 1, 1));
}
constant uint kMaxMeshletVertices   = 256;
constant uint kMaxMeshletPrimitives = 170;
[[mesh]]
void mesh_terrain(
    metal::mesh<MeshVertexOut, void, 256, 170, metal::topology::triangle> output,
    const object_data MeshletPayload&     payload    [[payload]],
    device const MeshletDescriptor*       meshlets   [[buffer(0)]],
    constant CameraUniforms&              camera     [[buffer(1)]],
    device const InhouseTerrainVertex*    vertices   [[buffer(2)]],
    device const uint*                    indices    [[buffer(3)]],
    device const ChunkUniforms*           chunks     [[buffer(4)]],
    uint tid  [[thread_index_in_threadgroup]],
    uint tgSize [[threads_per_threadgroup]]
) {
    MeshletDescriptor m = meshlets[payload.meshletIndex];
    ChunkUniforms chunk = chunks[m.chunkIndex];
    uint numVerts = min(m.vertexCount, kMaxMeshletVertices);
    uint numTris  = min(m.indexCount / 3u, kMaxMeshletPrimitives);
    output.set_primitive_count(numTris);
    for (uint i = tid; i < numVerts; i += tgSize) {
        InhouseTerrainVertex v = vertices[m.vertexOffset + i];
        MeshVertexOut out;
        float3 localPos = float3(short3(v.position)) / 256.0;
        float3 worldPos = localPos + chunk.chunkOffset.xyz;
        float4 viewPos  = camera.modelView * float4(worldPos, 1.0);
        out.position = camera.projection * viewPos;
        out.texCoord = half2(float2(v.texCoord) / 65535.0);
        out.color    = half4(float4(v.color) / 255.0);
        half lightVal = half(float(v.packedLight) / 255.0);
        out.lightUV  = half2(lightVal, lightVal);
        output.set_vertex(i, out);
    }
    for (uint i = tid; i < numTris; i += tgSize) {
        uint baseIdx = m.indexOffset + i * 3;
        uint i0 = indices[baseIdx + 0];
        uint i1 = indices[baseIdx + 1];
        uint i2 = indices[baseIdx + 2];
        uint localI0 = i0 - m.vertexOffset;
        uint localI1 = i1 - m.vertexOffset;
        uint localI2 = i2 - m.vertexOffset;
        output.set_index(i * 3 + 0, localI0);
        output.set_index(i * 3 + 1, localI1);
        output.set_index(i * 3 + 2, localI2);
    }
}
fragment half4 fragment_terrain_mesh_opaque(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(s, float2(in.texCoord));
    if (texColor.a < half(0.5) && in.color.a >= half(0.998)) discard_fragment();
    half4 baseColor = texColor * in.color;
    half light = max(max(in.lightUV.x, in.lightUV.y), half(0.1));
    baseColor.rgb *= light;
    half outAlpha = in.color.a < half(0.99) ? half(in.color.a) : half(1.0);
    return half4(baseColor.rgb, outAlpha);
}
fragment half4 fragment_terrain_mesh_cutout(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(s, float2(in.texCoord));
    if (texColor.a < half(0.5)) discard_fragment();
    half4 baseColor = texColor * in.color;
    half light = max(max(in.lightUV.x, in.lightUV.y), half(0.1));
    baseColor.rgb *= light;
    return half4(baseColor.rgb, half(1.0));
}
fragment half4 fragment_terrain_mesh_emissive(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(s, float2(in.texCoord));
    if (texColor.a < half(0.1)) discard_fragment();
    return texColor * in.color;
}
