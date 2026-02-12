#include <metal_stdlib>
using namespace metal;

namespace {
constant uint kThreadgroupWidth = 8;
constant uint kThreadgroupHeight = 8;
constant uint kMaxReduceStep = 4;
constant uint kDownsampleStep = 2;

static inline uint clamp_coord(uint value, uint upperBound) {
    return upperBound == 0u ? 0u : min(value, upperBound - 1u);
}
}

struct HiZParams {
    uint srcWidth;
    uint srcHeight;
    uint dstWidth;
    uint dstHeight;
    uint scaleShift;
};

struct HiZMipParams {
    uint srcWidth;
    uint srcHeight;
    uint dstWidth;
    uint dstHeight;
};

kernel void reduce_level(texture2d<half, access::write> dst [[texture(0)]],
                         texture2d<float, access::read> depth [[texture(1)]],
                         constant HiZParams &params [[buffer(0)]],
                         uint2 gid [[thread_position_in_grid]],
                         uint2 tid [[thread_position_in_threadgroup]],
                         uint2 groupId [[threadgroup_position_in_grid]],
                         uint simd_lane [[thread_index_in_simdgroup]],
                         uint simd_group [[simdgroup_index_in_threadgroup]]) {
    if (params.srcWidth == 0u || params.srcHeight == 0u ||
        params.dstWidth == 0u || params.dstHeight == 0u) {
        return;
    }

    uint step = max(1u, min(kMaxReduceStep, 1u << params.scaleShift));
    uint2 groupOrigin = groupId * uint2(kThreadgroupWidth, kThreadgroupHeight);

    uint scaledGroupX = groupOrigin.x * step;
    uint scaledGroupY = groupOrigin.y * step;

    float localMin = 1.0f;
    uint localBaseX = tid.x * step;
    uint localBaseY = tid.y * step;
    for (uint y = 0; y < step; ++y) {
        uint srcY = clamp_coord(scaledGroupY + localBaseY + y, params.srcHeight);
        for (uint x = 0; x < step; ++x) {
            uint srcX = clamp_coord(scaledGroupX + localBaseX + x, params.srcWidth);
            localMin = fmin(localMin, depth.read(uint2(srcX, srcY)).x);
        }
    }

    if (gid.x >= params.dstWidth || gid.y >= params.dstHeight) {
        return;
    }

    dst.write(half(localMin), gid);
}

kernel void downsample_level(texture2d<half, access::read> src [[texture(0)]],
                             texture2d<half, access::write> dst [[texture(1)]],
                             constant HiZMipParams &params [[buffer(0)]],
                             uint2 gid [[thread_position_in_grid]],
                             uint2 tid [[thread_position_in_threadgroup]],
                             uint2 groupId [[threadgroup_position_in_grid]],
                             uint simd_lane [[thread_index_in_simdgroup]]) {
    if (params.srcWidth == 0u || params.srcHeight == 0u ||
        params.dstWidth == 0u || params.dstHeight == 0u) {
        return;
    }

    uint2 groupOrigin = groupId * uint2(kThreadgroupWidth, kThreadgroupHeight);
    uint scaledGroupX = groupOrigin.x * kDownsampleStep;
    uint scaledGroupY = groupOrigin.y * kDownsampleStep;

    uint localBaseX = tid.x * kDownsampleStep;
    uint localBaseY = tid.y * kDownsampleStep;

    float minDepth = 1.0f;
    for (uint y = 0; y < kDownsampleStep; ++y) {
        uint srcY = clamp_coord(scaledGroupY + localBaseY + y, params.srcHeight);
        for (uint x = 0; x < kDownsampleStep; ++x) {
            uint srcX = clamp_coord(scaledGroupX + localBaseX + x, params.srcWidth);
            minDepth = fmin(minDepth, float(src.read(uint2(srcX, srcY)).x));
        }
    }

    if (gid.x >= params.dstWidth || gid.y >= params.dstHeight) {
        return;
    }

    dst.write(half(minDepth), gid);
}

kernel void downsample_level_simd(texture2d<half, access::read> src [[texture(0)]],
                                   texture2d<half, access::write> dst [[texture(1)]],
                                   constant HiZMipParams &params [[buffer(0)]],
                                   uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= params.dstWidth || gid.y >= params.dstHeight) {
        return;
    }

    uint2 srcBase = gid * 2u;
    float d0 = float(src.read(uint2(min(srcBase.x,     params.srcWidth - 1u), min(srcBase.y,     params.srcHeight - 1u))).x);
    float d1 = float(src.read(uint2(min(srcBase.x + 1u, params.srcWidth - 1u), min(srcBase.y,     params.srcHeight - 1u))).x);
    float d2 = float(src.read(uint2(min(srcBase.x,     params.srcWidth - 1u), min(srcBase.y + 1u, params.srcHeight - 1u))).x);
    float d3 = float(src.read(uint2(min(srcBase.x + 1u, params.srcWidth - 1u), min(srcBase.y + 1u, params.srcHeight - 1u))).x);

    float minDepth = fmin(fmin(d0, d1), fmin(d2, d3));
    dst.write(half(minDepth), gid);
}
