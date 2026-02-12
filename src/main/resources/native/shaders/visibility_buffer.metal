#include <metal_stdlib>
using namespace metal;

struct VisBufferUniforms {
    float4x4 viewProj;
    float4 cameraPos;
};

struct VisBufferDrawUniforms {
    float originX;
    float originY;
    float originZ;
    uint drawId;          
};

struct VisBufferVertexOut {
    float4 position [[position]];
    uint drawId [[flat]];       
};
struct VisBufferOutput {
    uint visData [[color(0)]];
};

constant half MODEL_ORIGIN_VIS = 8.0h;
constant half MODEL_RANGE_VIS = 32.0h;
constant uint POSITION_MAX_VALUE_VIS = 1u << 20u;

vertex VisBufferVertexOut visbuffer_vertex(
    uint vertexId [[vertex_id]],
    constant uint* vertexData [[buffer(0)]],
    constant VisBufferDrawUniforms& draw [[buffer(1)]],
    constant VisBufferUniforms& frame [[buffer(2)]]
) {
    uint base = vertexId * 5u;
    
    uint posHi = vertexData[base + 0u];
    uint posLo = vertexData[base + 1u];
    uint xHi = (posHi >> 0u) & 0x3FFu;
    uint yHi = (posHi >> 10u) & 0x3FFu;
    uint zHi = (posHi >> 20u) & 0x3FFu;
    uint xLo = (posLo >> 0u) & 0x3FFu;
    uint yLo = (posLo >> 10u) & 0x3FFu;
    uint zLo = (posLo >> 20u) & 0x3FFu;
    uint qx = (xHi << 10u) | xLo;
    uint qy = (yHi << 10u) | yLo;
    uint qz = (zHi << 10u) | zLo;
    
    float3 origin = float3(draw.originX, draw.originY, draw.originZ);
    float x = (float(qx) / float(POSITION_MAX_VALUE_VIS)) * float(MODEL_RANGE_VIS) - float(MODEL_ORIGIN_VIS) + origin.x;
    float y = (float(qy) / float(POSITION_MAX_VALUE_VIS)) * float(MODEL_RANGE_VIS) - float(MODEL_ORIGIN_VIS) + origin.y;
    float z = (float(qz) / float(POSITION_MAX_VALUE_VIS)) * float(MODEL_RANGE_VIS) - float(MODEL_ORIGIN_VIS) + origin.z;
    
    float3 cameraRelativePos = float3(x, y, z) - frame.cameraPos.xyz;
    
    VisBufferVertexOut out;
    out.position = frame.viewProj * float4(cameraRelativePos, 1.0);
    out.drawId = draw.drawId;
    return out;
}

fragment VisBufferOutput visbuffer_fragment(
    VisBufferVertexOut in [[stage_in]],
    uint primitiveId [[primitive_id]]   
) {
    VisBufferOutput out;
    out.visData = (primitiveId & 0x00FFFFFFu) | ((in.drawId & 0xFFu) << 24u);
    return out;
}

struct ResolveVertexOut {
    float4 position [[position]];
    float2 texCoord;
};

vertex ResolveVertexOut visbuffer_resolve_vertex(
    uint vertexId [[vertex_id]]
) {
    float2 pos;
    pos.x = (vertexId == 1) ? 3.0 : -1.0;
    pos.y = (vertexId == 2) ? 3.0 : -1.0;
    
    ResolveVertexOut out;
    out.position = float4(pos, 0.0, 1.0);
    out.texCoord = pos * 0.5 + 0.5;
    out.texCoord.y = 1.0 - out.texCoord.y; 
    return out;
}

fragment half4 visbuffer_resolve_fragment(
    ResolveVertexOut in [[stage_in]],
    texture2d<uint, access::read> visBuffer [[texture(0)]],
    texture2d<half, access::sample> atlas [[texture(1)]],
    sampler atlasSampler [[sampler(0)]]
) {
    uint2 coord = uint2(in.position.xy);
    uint visData = visBuffer.read(coord).r;
    
    if (visData == 0u) {
        discard_fragment();
    }
    
    uint drawId = (visData >> 24u) & 0xFFu;
    half hue = half(drawId) / 255.0h;
    return half4(hue, 1.0h - hue, 0.5h, 1.0h);
}
