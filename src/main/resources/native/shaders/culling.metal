#include <metal_stdlib>
using namespace metal;

struct DrawCommandInput {
    uint32_t offset;
    uint32_t vertexCount;
    uint32_t byteLength;
    half originX;
    half originY;
    half originZ;
    half distance;
    uint32_t padding;
};

struct DrawUniforms {
    half originX;
    half originY;
    half originZ;
    half padding;
};

struct ICBContainer {
    command_buffer icb [[id(0)]];
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
    
    float3 center = float3(cmd.originX + 8.0f, cmd.originY + 8.0f, cmd.originZ + 8.0f);
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
        
        drawUniforms[index].originX = cmd.originX;
        drawUniforms[index].originY = cmd.originY;
        drawUniforms[index].originZ = cmd.originZ;
        drawUniforms[index].padding = 0.0f;
        
        render_command renderCmd = container->icb.render_command_at_index(index);
        
        renderCmd.set_vertex_buffer(vertexBuffer + cmd.offset, 0);
    
        renderCmd.set_vertex_buffer(drawUniforms + index, 1);
        
        renderCmd.draw_primitives(primitive_type::triangle, 0, cmd.vertexCount, 1, 0);
    }
}
