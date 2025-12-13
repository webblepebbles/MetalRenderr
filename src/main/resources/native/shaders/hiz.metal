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
                         uint2 groupId [[threadgroup_position_in_grid]]) {
    if (params.srcWidth == 0u || params.srcHeight == 0u ||
        params.dstWidth == 0u || params.dstHeight == 0u) {
        return;
    }

    threadgroup float tile[kThreadgroupWidth * kMaxReduceStep *
                           kThreadgroupHeight * kMaxReduceStep];

    uint step = max(1u, min(kMaxReduceStep, 1u << params.scaleShift));
    uint2 groupOrigin = groupId * uint2(kThreadgroupWidth, kThreadgroupHeight);
    uint tileStride = kThreadgroupWidth * kMaxReduceStep;

    uint scaledGroupX = groupOrigin.x * step;
    uint scaledGroupY = groupOrigin.y * step;

    for (uint sy = tid.y; sy < kThreadgroupHeight * step; sy += kThreadgroupHeight) {
        uint srcY = clamp_coord(scaledGroupY + sy, params.srcHeight);
        for (uint sx = tid.x; sx < kThreadgroupWidth * step; sx += kThreadgroupWidth) {
            uint srcX = clamp_coord(scaledGroupX + sx, params.srcWidth);
            tile[sy * tileStride + sx] = depth.read(uint2(srcX, srcY)).x;
        }
    }

    threadgroup_barrier(mem_flags::mem_threadgroup);

    if (gid.x >= params.dstWidth || gid.y >= params.dstHeight) {
        return;
    }

    float minDepth = 1.0f;
    uint localBaseX = tid.x * step;
    uint localBaseY = tid.y * step;
    for (uint y = 0; y < step; ++y) {
        uint rowIndex = (localBaseY + y) * tileStride + localBaseX;
        for (uint x = 0; x < step; ++x) {
            minDepth = fmin(minDepth, tile[rowIndex + x]);
        }
    }

    dst.write(half(minDepth), gid);
}

kernel void downsample_level(texture2d<half, access::read> src [[texture(0)]],
                             texture2d<half, access::write> dst [[texture(1)]],
                             constant HiZMipParams &params [[buffer(0)]],
                             uint2 gid [[thread_position_in_grid]],
                             uint2 tid [[thread_position_in_threadgroup]],
                             uint2 groupId [[threadgroup_position_in_grid]]) {
    if (params.srcWidth == 0u || params.srcHeight == 0u ||
        params.dstWidth == 0u || params.dstHeight == 0u) {
        return;
    }

    threadgroup float tile[kThreadgroupWidth * kMaxReduceStep *
                           kThreadgroupHeight * kMaxReduceStep];

    uint2 groupOrigin = groupId * uint2(kThreadgroupWidth, kThreadgroupHeight);
    uint tileStride = kThreadgroupWidth * kMaxReduceStep;
    uint scaledGroupX = groupOrigin.x * kDownsampleStep;
    uint scaledGroupY = groupOrigin.y * kDownsampleStep;

    for (uint sy = tid.y; sy < kThreadgroupHeight * kDownsampleStep; sy += kThreadgroupHeight) {
        uint srcY = clamp_coord(scaledGroupY + sy, params.srcHeight);
        for (uint sx = tid.x; sx < kThreadgroupWidth * kDownsampleStep; sx += kThreadgroupWidth) {
            uint srcX = clamp_coord(scaledGroupX + sx, params.srcWidth);
            tile[sy * tileStride + sx] = float(src.read(uint2(srcX, srcY)).x);
        }
    }

    threadgroup_barrier(mem_flags::mem_threadgroup);

    if (gid.x >= params.dstWidth || gid.y >= params.dstHeight) {
        return;
    }

    float minDepth = 1.0f;
    uint localBaseX = tid.x * kDownsampleStep;
    uint localBaseY = tid.y * kDownsampleStep;
    for (uint y = 0; y < kDownsampleStep; ++y) {
        uint rowIndex = (localBaseY + y) * tileStride + localBaseX;
        for (uint x = 0; x < kDownsampleStep; ++x) {
            minDepth = fmin(minDepth, tile[rowIndex + x]);
        }
    }

    dst.write(half(minDepth), gid);
}
