#include <metal_stdlib>
using namespace metal;
constant half LOD_THRESHOLDS[6] = { 128.0h, 192.0h, 256.0h, 512.0h, 1024.0h, 65504.0h };

struct DrawCommandInput {
    uint32_t offset;
    uint32_t vertexCount;
    uint32_t byteLength;
    half originX;
    half originY;
    half originZ;
    half distance;        
    uint32_t lodLevel;    
};

struct DrawUniforms {
    float originX;        
    float originY;
    float originZ;
    float renderLayer;
};

struct ICBContainer {
    command_buffer icb [[id(0)]];
};
struct LODDrawCounts {
    atomic_uint counts[6];
};

kernel void cull_and_encode(
    device DrawCommandInput* inputs [[buffer(0)]],
    constant float4* frustumPlanes [[buffer(1)]],
    device atomic_uint* drawCount [[buffer(2)]],
    device DrawUniforms* drawUniforms [[buffer(3)]],
    device ICBContainer* container [[buffer(4)]],
    device uchar* vertexBuffer [[buffer(5)]],
    uint tid [[thread_position_in_grid]]
) {
    if (inputs[tid].vertexCount == 0) return;
    DrawCommandInput cmd = inputs[tid];
    float3 center = float3(float(cmd.originX) + 8.0f, float(cmd.originY) + 8.0f, float(cmd.originZ) + 8.0f);
    float radius = 24.0f;
    
    bool visible = true;
    for (int i = 0; i < 6; i++) {
        if (dot(frustumPlanes[i].xyz, center) + frustumPlanes[i].w < -radius) {
            visible = false;
            break;
        }
    }
    
    if (visible) {
        uint index = atomic_fetch_add_explicit(drawCount, 1, memory_order_relaxed);
        
        drawUniforms[index].originX = float(cmd.originX);
        drawUniforms[index].originY = float(cmd.originY);
        drawUniforms[index].originZ = float(cmd.originZ);
        drawUniforms[index].renderLayer = 0.0f;
        
        render_command renderCmd(container->icb, index);
        renderCmd.set_vertex_buffer(vertexBuffer + cmd.offset, 0);
        renderCmd.set_vertex_buffer(drawUniforms + index, 1);
        renderCmd.draw_primitives(primitive_type::triangle, 0, cmd.vertexCount, 1, 0);
    }
}

kernel void cull_and_encode_multi_lod(
    device DrawCommandInput* inputs [[buffer(0)]],
    constant float4* frustumPlanes [[buffer(1)]],
    constant float4& cameraPos [[buffer(2)]],         
    device LODDrawCounts* lodCounts [[buffer(3)]],     
    device DrawUniforms* lodDrawUniforms0 [[buffer(4)]],  
    device DrawUniforms* lodDrawUniforms1 [[buffer(5)]],
    device DrawUniforms* lodDrawUniforms2 [[buffer(6)]],
    device DrawUniforms* lodDrawUniforms3 [[buffer(7)]],
    device DrawUniforms* lodDrawUniforms4 [[buffer(8)]],
    device DrawUniforms* lodDrawUniforms5 [[buffer(9)]],
    device ICBContainer* icb0 [[buffer(10)]],  
    device ICBContainer* icb1 [[buffer(11)]],  
    device ICBContainer* icb2 [[buffer(12)]],  
    device ICBContainer* icb3 [[buffer(13)]],  
    device ICBContainer* icb4 [[buffer(14)]],  
    device ICBContainer* icb5 [[buffer(15)]],  
    device uchar* vertexBuffer [[buffer(16)]],
    constant uint& totalChunks [[buffer(17)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid >= totalChunks) return;
    if (inputs[tid].vertexCount == 0) return;
    
    DrawCommandInput cmd = inputs[tid];
    float3 center = float3(float(cmd.originX) + 8.0f, float(cmd.originY) + 8.0f, float(cmd.originZ) + 8.0f);
    float radius = 24.0f;
    
    bool visible = true;
    for (int i = 0; i < 6; i++) {
        if (dot(frustumPlanes[i].xyz, center) + frustumPlanes[i].w < -radius) {
            visible = false;
            break;
        }
    }
    if (!visible) return;
    half3 camH = half3(half(cameraPos.x), half(cameraPos.y), half(cameraPos.z));
    half3 chunkCenter = half3(cmd.originX + 8.0h, cmd.originY + 8.0h, cmd.originZ + 8.0h);
    half dist = distance(camH, chunkCenter);
    
    uint lodLevel = 0u;
    for (uint i = 0u; i < 5u; i++) {
        if (dist > LOD_THRESHOLDS[i]) {
            lodLevel = i + 1u;
        }
    }
    if (cmd.lodLevel < 6u) {
        lodLevel = cmd.lodLevel;
    }
    device DrawUniforms* targetUniforms;
    device ICBContainer* targetICB;
    
    switch (lodLevel) {
        case 0u: targetUniforms = lodDrawUniforms0; targetICB = icb0; break;
        case 1u: targetUniforms = lodDrawUniforms1; targetICB = icb1; break;
        case 2u: targetUniforms = lodDrawUniforms2; targetICB = icb2; break;
        case 3u: targetUniforms = lodDrawUniforms3; targetICB = icb3; break;
        case 4u: targetUniforms = lodDrawUniforms4; targetICB = icb4; break;
        default: targetUniforms = lodDrawUniforms5; targetICB = icb5; break;
    }
    
    uint index = atomic_fetch_add_explicit(&lodCounts->counts[lodLevel], 1, memory_order_relaxed);
    
    targetUniforms[index].originX = float(cmd.originX);
    targetUniforms[index].originY = float(cmd.originY);
    targetUniforms[index].originZ = float(cmd.originZ);
    targetUniforms[index].renderLayer = 0.0f;
    
    render_command renderCmd(targetICB->icb, index);
    renderCmd.set_vertex_buffer(vertexBuffer + cmd.offset, 0);
    renderCmd.set_vertex_buffer(targetUniforms + index, 1);
    renderCmd.draw_primitives(primitive_type::triangle, 0, cmd.vertexCount, 1, 0);
}
