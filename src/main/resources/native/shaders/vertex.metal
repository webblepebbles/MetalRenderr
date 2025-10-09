#include <metal_stdlib>
using namespace metal;

struct VSOut { float4 pos [[position]]; };
vertex VSOut vs_main(uint vid [[vertex_id]]) {
	VSOut o; o.pos = float4(0,0,0,1); return o;
}
