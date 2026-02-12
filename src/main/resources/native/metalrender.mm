#include "metalrender.h"
#import <AppKit/AppKit.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#if METALRENDER_HAS_METALFX
#import <MetalFX/MetalFX.h>
#endif
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#import <QuartzCore/CAMetalLayer.h>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <pthread.h>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct DrawCommandData {
  uint32_t bufferOffset;
  uint32_t vertexCount;
  float originX, originY, originZ;
  uint32_t renderLayer;
  uint32_t lodLevel;
};

struct MetalContext {
  id<MTLDevice> device = nil;
  id<MTLCommandQueue> graphicsQueue = nil;
  id<MTLCommandQueue> computeQueue = nil;
  id<MTLLibrary> library = nil;
  id<MTLComputePipelineState> occlusionPipeline = nil;
  id<MTLBuffer> aabbBuffer = nil;
  id<MTLBuffer> occlusionResultBuffer = nil;
  id<MTLBuffer> occlusionConstants = nil;
  id<MTLBuffer> persistentBuffer = nil;
  id<MTLBuffer> indirectArgs = nil;
  id<MTLBuffer> drawUniformsBuffer = nil;
  id<MTLBuffer> frameUniformsBuffer = nil;
  std::vector<DrawCommandData> drawCommands;
  uint32_t maxIndirectCommands = 65536;
  uint32_t currentIndirectCount = 0;

  static constexpr uint32_t FRAME_OVERLAP = 3;
  id<MTLBuffer> ringDrawUniforms[FRAME_OVERLAP] = {};
  id<MTLBuffer> ringFrameUniforms[FRAME_OVERLAP] = {};
  uint32_t ringIndex = 0;
  dispatch_semaphore_t ringFrameSemaphore = nullptr;

  id<MTLBuffer> entityStagingBuffer = nil;
  size_t entityStagingCapacity = 0;
  size_t entityStagingOffset = 0;
  static constexpr size_t ENTITY_STAGING_SIZE = 16 * 1024 * 1024;

  id<MTLIndirectCommandBuffer> icb = nil;
  id<MTLArgumentEncoder> icbArgumentEncoder = nil;
  bool icbSupported = false;
  uint32_t icbMaxCommands = 16384;

#if METALRENDER_HAS_METALFX

  id<MTLFXSpatialScaler> metalFxScaler = nil;
  id<MTLTexture> metalFxColor = nil;
  id<MTLTexture> metalFxDepth = nil;
  id<MTLTexture> metalFxOutput = nil;
  uint32_t metalFxInputWidth = 0;
  uint32_t metalFxInputHeight = 0;
  uint32_t metalFxOutputWidth = 0;
  uint32_t metalFxOutputHeight = 0;
  bool metalFxSupported = false;
  bool metalFxEnabled = false;
  bool metalFxResetHistory = false;
  bool metalFxDestroyed = false;
#endif

  size_t persistentCapacity = 1024 * 1024 * 1024;
  size_t persistentAlignment = 256;
  size_t persistentCursor = 0;

  static constexpr uint32_t LOD_COUNT = 6;
  id<MTLIndirectCommandBuffer> lodICBs[LOD_COUNT] = {};
  id<MTLBuffer> lodDrawUniformsBuffers[LOD_COUNT] = {};
  id<MTLBuffer> lodDrawCountsBuffer = nil;
  id<MTLComputePipelineState> lodSelectPipeline = nil;
  id<MTLComputePipelineState> multiLodCullPipeline = nil;
  id<MTLRenderPipelineState> lodTerrainPipelines[LOD_COUNT] = {};
  id<MTLBuffer> lodThresholdsBuffer = nil;
  uint32_t multiICBMaxPerLOD = 8192;
  bool multiICBInitialized = false;
  int lodDrawCounts[LOD_COUNT] = {};

  bool tripleBufferingEnabled = false;
  uint32_t currentFrameIndex = 0;
  dispatch_semaphore_t frameSemaphore = nullptr;

  std::unordered_map<uint64_t, id<MTLBuffer>> argumentBuffers;
  std::string deviceName;
  bool hasViewProj = false;
  float viewProj[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  bool meshShadersSupported = false;
  float temporalJitterX = 0.0F;
  float temporalJitterY = 0.0F;
  float temporalBlend = 0.0F;

  IOSurfaceRef ioSurface = nullptr;
  id<MTLTexture> ioSurfaceTexture = nil;
  uint32_t ioSurfaceWidth = 0;
  uint32_t ioSurfaceHeight = 0;

  float renderScale = 1.0f;
  uint32_t fullWidth = 0;
  uint32_t fullHeight = 0;

  CAMetalLayer *metalLayer = nil;
  id<CAMetalDrawable> currentDrawable = nil;
  id<MTLRenderPipelineState> blitPipeline = nil;
  bool directPresentEnabled = false;

  id<MTLTexture> colorTexture = nil;
  id<MTLTexture> depthTexture = nil;
  id<MTLRenderPipelineState> terrainPipeline = nil;
  id<MTLDepthStencilState> depthState = nil;
  id<MTLLibrary> terrainLibrary = nil;
  id<MTLSamplerState> terrainSampler = nil;

  id<MTLRenderPipelineState> entityPipeline = nil;
  id<MTLRenderPipelineState> entityColorOnlyPipeline = nil;
  id<MTLRenderPipelineState> guiPipeline = nil;
  id<MTLDepthStencilState> entityDepthState = nil;
  id<MTLSamplerState> entitySampler = nil;
  id<MTLRenderCommandEncoder> currentEntityEncoder = nil;
  id<MTLCommandBuffer> currentEntityCommandBuffer = nil;
  bool entityPassActive = false;
  id<MTLTexture> whiteTexture = nil;

  id<MTLCommandBuffer> lastGuiCommandBuffer = nil;
  id<MTLCommandBuffer> lastEntityCommandBuffer = nil;
  id<MTLCommandBuffer> lastTerrainCommandBuffer = nil;

  std::unordered_map<uint64_t, id<MTLTexture>> entityTextures;

  id<MTLTexture> atlasTexture = nil;
  uint32_t atlasWidth = 0;
  uint32_t atlasHeight = 0;

  float cameraX = 0, cameraY = 0, cameraZ = 0;
  float fogStart = 0, fogEnd = 1000;
  float fogR = 0.5f, fogG = 0.6f, fogB = 0.8f, fogA = 1.0f;
  float texShrinkU = 1.0f / 1024.0f;
  float texShrinkV = 1.0f / 1024.0f;

  float dayBrightness = 1.0f;
  float ambientLight = 0.1f;
  float skyAngle = 0.0f;

  id<MTLTexture> guiTexture = nil;
  id<MTLRenderPipelineState> compositePipeline = nil;
  id<MTLBuffer> compositeQuadVB = nil;
  uint32_t guiTextureWidth = 0;
  uint32_t guiTextureHeight = 0;
  bool guiNeedsComposite = false;

  id<MTLComputePipelineState> computeMesherCountPipeline = nil;
  id<MTLComputePipelineState> computeMesherEmitPipeline = nil;
  id<MTLComputePipelineState> computeMesherClearPipeline = nil;
  id<MTLBuffer> computeMesherCountersBuffer = nil;
  id<MTLBuffer> computeMesherFaceMaskBuffer = nil;
  uint32_t computeMesherMaxVerts = 0;
  bool computeMesherInitialized = false;

  id<MTLRenderPipelineState> opaqueTerrainPipeline = nil;
  id<MTLRenderPipelineState> cutoutTerrainPipeline = nil;
  id<MTLRenderPipelineState> translucentTerrainPipeline = nil;

  id<MTLCommandQueue> backgroundComputeQueue = nil;

  std::string shadersPath;
};

struct HiZResources {
  id<MTLTexture> depthTexture = nil;
  id<MTLTexture> pyramidTexture = nil;
  uint32_t width = 0;
  uint32_t height = 0;
};

static std::mutex gMutex;

static const char *kOcclusionSource = R"METAL(
#include <metal_stdlib>
using namespace metal;
struct Aabb {
	float3 minBounds;
	float3 maxBounds;
};
struct OcclusionConstants {
	uint count;
	float2 hiZSize;
};
inline float4 projectCorner(float3 corner, constant float4x4& viewProj) {
	return viewProj * float4(corner, 1.0);
}
kernel void occlusion_test(const device Aabb* aabbs [[buffer(0)]],
						   constant float4x4& viewProj [[buffer(1)]],
						   device uchar* results [[buffer(2)]],
						   constant OcclusionConstants& constants [[buffer(3)]],
						   texture2d<float> hiZTexture [[texture(0)]],
						   uint id [[thread_position_in_grid]]) {
	if (id >= constants.count) return;
	constexpr sampler hiZSampler(coord::normalized, address::clamp_to_edge, filter::nearest, mip_filter::nearest);

	Aabb box = aabbs[id];
	float3 corners[8];
	corners[0] = float3(box.minBounds.x, box.minBounds.y, box.minBounds.z);
	corners[1] = float3(box.maxBounds.x, box.minBounds.y, box.minBounds.z);
	corners[2] = float3(box.minBounds.x, box.maxBounds.y, box.minBounds.z);
	corners[3] = float3(box.maxBounds.x, box.maxBounds.y, box.minBounds.z);
	corners[4] = float3(box.minBounds.x, box.minBounds.y, box.maxBounds.z);
	corners[5] = float3(box.maxBounds.x, box.minBounds.y, box.maxBounds.z);
	corners[6] = float3(box.minBounds.x, box.maxBounds.y, box.maxBounds.z);
	corners[7] = float3(box.maxBounds.x, box.maxBounds.y, box.maxBounds.z);

	float minX = 1.0, maxX = -1.0, minY = 1.0, maxY = -1.0;
	float nearestZ = 1.0;
	bool allBehind = true;
	bool allClipped = true;

	for (uint i = 0; i < 8; ++i) {
		float4 clip = projectCorner(corners[i], viewProj);
		if (clip.w <= 0.001f) continue;
		float3 ndc = clip.xyz / clip.w;

		if (ndc.z < 1.0f) allBehind = false;
		if (ndc.x >= -1.0f && ndc.x <= 1.0f && ndc.y >= -1.0f && ndc.y <= 1.0f) allClipped = false;

		minX = min(minX, ndc.x);
		maxX = max(maxX, ndc.x);
		minY = min(minY, ndc.y);
		maxY = max(maxY, ndc.y);
		nearestZ = min(nearestZ, ndc.z);
	}

	if (allBehind || allClipped || maxX < -1.0f || minX > 1.0f || maxY < -1.0f || minY > 1.0f) {
		results[id] = 1;
		return;
	}

	float2 screenMin = float2((minX + 1.0f) * 0.5f, (1.0f - maxY) * 0.5f);
	float2 screenMax = float2((maxX + 1.0f) * 0.5f, (1.0f - minY) * 0.5f);
	screenMin = clamp(screenMin, float2(0.0f), float2(1.0f));
	screenMax = clamp(screenMax, float2(0.0f), float2(1.0f));

	float2 screenSize = (screenMax - screenMin) * constants.hiZSize;
	float maxDim = max(screenSize.x, screenSize.y);
	float mipLevel = max(0.0f, floor(log2(maxDim)));

	float2 samplePos = (screenMin + screenMax) * 0.5f;
	float hiZDepth = hiZTexture.sample(hiZSampler, samplePos, level(mipLevel)).r;

	bool occluded = (nearestZ > hiZDepth + 0.0001f);
	results[id] = occluded ? 1 : 0;
}
)METAL";

static id<MTLLibrary> createLibraryFromSource(id<MTLDevice> device,
                                              NSString *label) {
  NSError *error = nil;
  MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
  options.fastMathEnabled = YES;
  id<MTLLibrary> library = [device
      newLibraryWithSource:[NSString stringWithUTF8String:kOcclusionSource]
                   options:options
                     error:&error];
  if (!library || error) {
    printf("[MetalRender] Failed to compile occlusion shader: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown error");
    return nil;
  }
  (void)label;
  return library;
}

static MetalContext *getContext(jlong handle) {
  return reinterpret_cast<MetalContext *>(static_cast<intptr_t>(handle));
}

static HiZResources *getHiZ(jlong handle) {
  return reinterpret_cast<HiZResources *>(static_cast<intptr_t>(handle));
}

static void destroyHiZImpl(HiZResources *hiz) {
  if (!hiz)
    return;
  if (hiz->depthTexture)
    hiz->depthTexture = nil;
  if (hiz->pyramidTexture)
    hiz->pyramidTexture = nil;
  delete hiz;
}

static bool ensureOcclusionPipeline(MetalContext *ctx) {
  if (!ctx || !ctx->device)
    return false;
  if (ctx->occlusionPipeline)
    return true;
  if (!ctx->library) {
    ctx->library =
        createLibraryFromSource(ctx->device, @"MetalRenderOcclusion");
    if (!ctx->library)
      return false;
  }
  NSError *error = nil;
  id<MTLFunction> func = [ctx->library newFunctionWithName:@"occlusion_test"];
  if (!func)
    return false;
  ctx->occlusionPipeline =
      [ctx->device newComputePipelineStateWithFunction:func error:&error];
  if (!ctx->occlusionPipeline || error) {
    printf("[MetalRender] Failed to create occlusion pipeline: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown error");
    ctx->occlusionPipeline = nil;
    return false;
  }
  return true;
}

static bool ensureTerrainPipeline(MetalContext *ctx) {
  if (!ctx || !ctx->device)
    return false;
  if (ctx->terrainPipeline && ctx->depthState && ctx->terrainSampler)
    return true;

  if (!ctx->terrainLibrary) {
    if (ctx->shadersPath.empty()) {
      printf("[MetalRender] No shaders path set for terrain pipeline\n");
      return false;
    }
    NSString *pathString =
        [NSString stringWithUTF8String:ctx->shadersPath.c_str()];
    NSError *error = nil;
    ctx->terrainLibrary =
        [ctx->device newLibraryWithURL:[NSURL fileURLWithPath:pathString]
                                 error:&error];
    if (!ctx->terrainLibrary) {
      fprintf(
          stderr, "[MetalRender] Failed to load terrain library from %s: %s\n",
          ctx->shadersPath.c_str(),
          error ? [[error localizedDescription] UTF8String] : "unknown error");
      return false;
    }
    printf("[MetalRender] Loaded terrain shader library from %s\n",
           ctx->shadersPath.c_str());
  }

  if (!ctx->terrainPipeline) {

    MTLFunctionConstantValues *defaultConstants =
        [[MTLFunctionConstantValues alloc] init];
    uint32_t defaultLodLevel = 0;
    bool defaultBlockLight = true;
    bool defaultTexSample = true;
    bool defaultFog = true;
    [defaultConstants setConstantValue:&defaultLodLevel
                                  type:MTLDataTypeUInt
                               atIndex:0];
    [defaultConstants setConstantValue:&defaultBlockLight
                                  type:MTLDataTypeBool
                               atIndex:1];
    [defaultConstants setConstantValue:&defaultTexSample
                                  type:MTLDataTypeBool
                               atIndex:2];
    [defaultConstants setConstantValue:&defaultFog
                                  type:MTLDataTypeBool
                               atIndex:3];

    NSError *fcError = nil;
    id<MTLFunction> vertexFunc =
        [ctx->terrainLibrary newFunctionWithName:@"terrain_color_vertex"
                                  constantValues:defaultConstants
                                           error:&fcError];
    id<MTLFunction> fragmentFunc =
        [ctx->terrainLibrary newFunctionWithName:@"terrain_color_fragment"
                                  constantValues:defaultConstants
                                           error:&fcError];
    if (!vertexFunc || !fragmentFunc) {
      printf("[MetalRender] Failed to find terrain shader functions: %s\n",
             fcError ? [[fcError localizedDescription] UTF8String] : "unknown");
      return false;
    }

    MTLRenderPipelineDescriptor *desc =
        [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vertexFunc;
    desc.fragmentFunction = fragmentFunc;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    desc.colorAttachments[0].blendingEnabled = NO;
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    desc.label = @"TerrainColorPipeline";

    NSError *error = nil;
    ctx->terrainPipeline =
        [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!ctx->terrainPipeline) {
      printf("[MetalRender] Failed to create terrain pipeline: %s\n",
             error ? [[error localizedDescription] UTF8String]
                   : "unknown error");
      return false;
    }

    desc.colorAttachments[0].blendingEnabled = NO;
    desc.label = @"OpaqueTerrainPipeline";
    ctx->opaqueTerrainPipeline =
        [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];

    desc.colorAttachments[0].blendingEnabled = NO;
    desc.label = @"CutoutTerrainPipeline";
    ctx->cutoutTerrainPipeline =
        [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];

    desc.colorAttachments[0].blendingEnabled = YES;
    desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    desc.colorAttachments[0].destinationRGBBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    desc.colorAttachments[0].destinationAlphaBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.label = @"TranslucentTerrainPipeline";
    ctx->translucentTerrainPipeline =
        [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];

    printf("[MetalRender] Created terrain render pipelines "
           "(opaque/cutout/translucent)\n");
  }

  if (!ctx->depthState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction = MTLCompareFunctionLessEqual;
    depthDesc.depthWriteEnabled = YES;
    ctx->depthState =
        [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
    if (!ctx->depthState) {
      printf("[MetalRender] Failed to create depth stencil state\n");
      return false;
    }
  }

  if (!ctx->terrainSampler) {
    MTLSamplerDescriptor *samplerDesc = [[MTLSamplerDescriptor alloc] init];
    samplerDesc.minFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.magFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.mipFilter = MTLSamplerMipFilterNearest;
    samplerDesc.sAddressMode = MTLSamplerAddressModeRepeat;
    samplerDesc.tAddressMode = MTLSamplerAddressModeRepeat;
    ctx->terrainSampler =
        [ctx->device newSamplerStateWithDescriptor:samplerDesc];
    if (!ctx->terrainSampler) {
      printf("[MetalRender] Failed to create terrain sampler\n");
      return false;
    }
  }

  return true;
}

struct EntityFrameUniforms {
  float viewProj[16];
  float cameraPos[4];
  float fogColor[4];
  float fogParams[4];
  float lightParams[4];
};

static bool ensureEntityPipeline(MetalContext *ctx) {
  if (!ctx || !ctx->device)
    return false;
  if (ctx->entityPipeline && ctx->entityDepthState && ctx->entitySampler)
    return true;

  if (!ctx->terrainLibrary) {
    if (ctx->shadersPath.empty()) {
      printf("[MetalRender] No shaders path set for entity pipeline\n");
      return false;
    }
    NSString *pathString =
        [NSString stringWithUTF8String:ctx->shadersPath.c_str()];
    NSError *error = nil;
    ctx->terrainLibrary =
        [ctx->device newLibraryWithURL:[NSURL fileURLWithPath:pathString]
                                 error:&error];
    if (!ctx->terrainLibrary) {
      fprintf(stderr,
              "[MetalRender] Failed to load library for entities from %s: %s\n",
              ctx->shadersPath.c_str(),
              error ? [[error localizedDescription] UTF8String] : "unknown");
      return false;
    }
  }

  if (!ctx->entityPipeline) {
    id<MTLFunction> vertexFunc =
        [ctx->terrainLibrary newFunctionWithName:@"entity_color_vertex"];
    id<MTLFunction> fragmentFunc =
        [ctx->terrainLibrary newFunctionWithName:@"entity_color_fragment"];
    if (!vertexFunc || !fragmentFunc) {
      printf("[MetalRender] Failed to find entity shader functions (vertex=%p, "
             "frag=%p)\n",
             vertexFunc, fragmentFunc);
      return false;
    }

    MTLRenderPipelineDescriptor *desc =
        [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vertexFunc;
    desc.fragmentFunction = fragmentFunc;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    desc.colorAttachments[0].blendingEnabled = YES;
    desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    desc.colorAttachments[0].destinationRGBBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    desc.colorAttachments[0].destinationAlphaBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    desc.label = @"EntityColorPipeline";

    NSError *error = nil;
    ctx->entityPipeline =
        [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!ctx->entityPipeline) {
      printf("[MetalRender] Failed to create entity pipeline: %s\n",
             error ? [[error localizedDescription] UTF8String] : "unknown");
      return false;
    }
    printf("[MetalRender] Created entity render pipeline\n");
  }

  if (!ctx->entityColorOnlyPipeline) {
    id<MTLFunction> vertexFunc =
        [ctx->terrainLibrary newFunctionWithName:@"entity_color_vertex"];
    id<MTLFunction> fragmentFunc =
        [ctx->terrainLibrary newFunctionWithName:@"entity_color_only_fragment"];
    if (vertexFunc && fragmentFunc) {
      MTLRenderPipelineDescriptor *desc =
          [[MTLRenderPipelineDescriptor alloc] init];
      desc.vertexFunction = vertexFunc;
      desc.fragmentFunction = fragmentFunc;
      desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      desc.colorAttachments[0].blendingEnabled = YES;
      desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
      desc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      desc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      desc.label = @"EntityColorOnlyPipeline";

      NSError *error = nil;
      ctx->entityColorOnlyPipeline =
          [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
      if (ctx->entityColorOnlyPipeline) {
        printf("[MetalRender] Created entity color-only render pipeline\n");
      }
    }
  }

  if (!ctx->whiteTexture) {
    MTLTextureDescriptor *texDesc = [[MTLTextureDescriptor alloc] init];
    texDesc.textureType = MTLTextureType2D;
    texDesc.pixelFormat = MTLPixelFormatRGBA8Unorm;
    texDesc.width = 1;
    texDesc.height = 1;
    texDesc.mipmapLevelCount = 1;
    texDesc.usage = MTLTextureUsageShaderRead;
    ctx->whiteTexture = [ctx->device newTextureWithDescriptor:texDesc];
    if (ctx->whiteTexture) {
      uint8_t white[4] = {255, 255, 255, 255};
      MTLRegion region = MTLRegionMake2D(0, 0, 1, 1);
      [ctx->whiteTexture replaceRegion:region
                           mipmapLevel:0
                             withBytes:white
                           bytesPerRow:4];
      printf("[MetalRender] Created fallback white texture\n");
    }
  }

  if (!ctx->entityDepthState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction = MTLCompareFunctionLess;
    depthDesc.depthWriteEnabled = YES;
    ctx->entityDepthState =
        [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
    if (!ctx->entityDepthState) {
      printf("[MetalRender] Failed to create entity depth stencil state\n");
      return false;
    }
    printf("[MetalRender] Created entity depth state (depth test enabled)\n");
  }

  if (!ctx->guiPipeline) {
    id<MTLFunction> vertexFunc =
        [ctx->terrainLibrary newFunctionWithName:@"entity_color_vertex"];
    id<MTLFunction> fragmentFunc =
        [ctx->terrainLibrary newFunctionWithName:@"entity_color_fragment"];
    if (vertexFunc && fragmentFunc) {
      MTLRenderPipelineDescriptor *desc =
          [[MTLRenderPipelineDescriptor alloc] init];
      desc.vertexFunction = vertexFunc;
      desc.fragmentFunction = fragmentFunc;
      desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      desc.colorAttachments[0].blendingEnabled = YES;
      desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
      desc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      desc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;

      desc.depthAttachmentPixelFormat = MTLPixelFormatInvalid;
      desc.label = @"GuiPipeline";

      NSError *error = nil;
      ctx->guiPipeline =
          [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
      if (ctx->guiPipeline) {
        printf("[MetalRender] Created GUI render pipeline (no depth)\n");
      } else {
        printf("[MetalRender] Failed to create GUI pipeline: %s\n",
               error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
  }

  if (!ctx->entitySampler) {
    MTLSamplerDescriptor *samplerDesc = [[MTLSamplerDescriptor alloc] init];
    samplerDesc.minFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.magFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.mipFilter = MTLSamplerMipFilterNearest;
    samplerDesc.sAddressMode = MTLSamplerAddressModeClampToEdge;
    samplerDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
    ctx->entitySampler =
        [ctx->device newSamplerStateWithDescriptor:samplerDesc];
    if (!ctx->entitySampler) {
      printf("[MetalRender] Failed to create entity sampler\n");
      return false;
    }
  }

  return true;
}

static id<MTLBuffer> ensureBufferCapacity(id<MTLDevice> device,
                                          id<MTLBuffer> buffer,
                                          size_t byteSize) {
  if (buffer && [buffer length] >= byteSize) {
    return buffer;
  }
  return [device newBufferWithLength:byteSize
                             options:MTLResourceStorageModeShared];
}

static bool ensureIOSurface(MetalContext *ctx, uint32_t width,
                            uint32_t height) {
  if (!ctx || !ctx->device || width == 0 || height == 0) {
    return false;
  }

  if (ctx->ioSurface && ctx->ioSurfaceWidth == width &&
      ctx->ioSurfaceHeight == height) {
    return true;
  }

  if (ctx->ioSurfaceTexture) {
    ctx->ioSurfaceTexture = nil;
  }
  if (ctx->ioSurface) {
    CFRelease(ctx->ioSurface);
    ctx->ioSurface = nullptr;
  }
  if (ctx->colorTexture) {
    ctx->colorTexture = nil;
  }
  if (ctx->depthTexture) {
    ctx->depthTexture = nil;
  }

  uint32_t pixelFormat = 'BGRA';

  size_t bytesPerRow = width * 4;
  size_t align = 256;
  bytesPerRow = (bytesPerRow + align - 1) & ~(align - 1);
  size_t allocSize = bytesPerRow * height;

  NSDictionary *surfaceProperties = @{
    (NSString *)kIOSurfaceWidth : @(width),
    (NSString *)kIOSurfaceHeight : @(height),
    (NSString *)kIOSurfaceBytesPerElement : @(4),
    (NSString *)kIOSurfaceBytesPerRow : @(bytesPerRow),
    (NSString *)kIOSurfaceAllocSize : @(allocSize),
    (NSString *)kIOSurfacePixelFormat : @(pixelFormat),
    (NSString *)kIOSurfaceCacheMode : @(kIOMapWriteCombineCache)
  };

  ctx->ioSurface = IOSurfaceCreate((__bridge CFDictionaryRef)surfaceProperties);
  if (!ctx->ioSurface) {
    printf("[MetalRender] Failed to create IOSurface %ux%u\n", width, height);
    return false;
  }

  size_t actualBytesPerRow = IOSurfaceGetBytesPerRow(ctx->ioSurface);
  size_t actualWidth = IOSurfaceGetWidth(ctx->ioSurface);
  size_t actualHeight = IOSurfaceGetHeight(ctx->ioSurface);
  printf("[MetalRender] Created IOSurface: %zux%zu, BytesPerRow: %zu "
         "(Requested: %u)\n",
         actualWidth, actualHeight, actualBytesPerRow, width * 4);

  MTLTextureDescriptor *texDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                   width:width
                                  height:height
                               mipmapped:NO];
  texDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  texDesc.storageMode = MTLStorageModeShared;

  ctx->ioSurfaceTexture = [ctx->device newTextureWithDescriptor:texDesc
                                                      iosurface:ctx->ioSurface
                                                          plane:0];
  if (!ctx->ioSurfaceTexture) {
    printf("[MetalRender] Failed to create IOSurface-backed texture\n");
    CFRelease(ctx->ioSurface);
    ctx->ioSurface = nullptr;
    return false;
  }

  MTLTextureDescriptor *depthDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                   width:width
                                  height:height
                               mipmapped:NO];
  depthDesc.usage = MTLTextureUsageRenderTarget;
  depthDesc.storageMode = MTLStorageModePrivate;
  ctx->depthTexture = [ctx->device newTextureWithDescriptor:depthDesc];

  ctx->ioSurfaceWidth = width;
  ctx->ioSurfaceHeight = height;
  ctx->colorTexture = ctx->ioSurfaceTexture;

  printf("[MetalRender] Created IOSurface %ux%u successfully\\n", width,
         height);
  return true;
}

static bool supportsMeshShaders(id<MTLDevice> device) {
  if (!device)
    return false;
#ifdef __MAC_OS_X_VERSION_MAX_ALLOWED
  if (@available(macOS 14.0, *)) {
    if ([device respondsToSelector:@selector(supportsFamily:)]) {
#ifdef MTLGPUFamilyApple7
      if ([device supportsFamily:MTLGPUFamilyApple7]) {
        return true;
      }
#endif
#ifdef MTLGPUFamilyApple8
      if ([device supportsFamily:MTLGPUFamilyApple8]) {
        return true;
      }
#endif
#ifdef MTLGPUFamilyMetal3
      if ([device supportsFamily:MTLGPUFamilyMetal3]) {
        return true;
      }
#endif
#ifdef MTLGPUFamilyMac2
      if ([device supportsFamily:MTLGPUFamilyMac2]) {
        return true;
      }
#endif
    }
  }
#endif
  return false;
}

static float clampFloat(float value, float lo, float hi) {
  if (value < lo)
    return lo;
  if (value > hi)
    return hi;
  return value;
}

#if METALRENDER_HAS_METALFX
static bool isTruthyFlag(const char *value) {
  if (!value) {
    return false;
  }
  std::string flag(value);
  auto start = flag.find_first_not_of(" \t\n\r");
  if (start == std::string::npos) {
    return false;
  }
  auto end = flag.find_last_not_of(" \t\n\r");
  std::string trimmed = flag.substr(start, end - start + 1);
  if (trimmed.empty()) {
    return false;
  }
  for (char &c : trimmed) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  }
  return trimmed == "1" || trimmed == "true" || trimmed == "yes" ||
         trimmed == "on";
}

static bool isFalsyFlag(const char *value) {
  if (!value) {
    return false;
  }
  std::string flag(value);
  auto start = flag.find_first_not_of(" \t\n\r");
  if (start == std::string::npos) {
    return false;
  }
  auto end = flag.find_last_not_of(" \t\n\r");
  std::string trimmed = flag.substr(start, end - start + 1);
  if (trimmed.empty()) {
    return false;
  }
  for (char &c : trimmed) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  }
  return trimmed == "0" || trimmed == "false" || trimmed == "no" ||
         trimmed == "off";
}

static bool supportsMetalFX(id<MTLDevice> device) {
  if (!device)
    return false;
  const char *envValue = getenv("METALRENDER_ENABLE_METALFX");
  static bool envLogged = false;
  const char *rawValue = envValue ? envValue : "(not set - enabled by default)";
  if (!envLogged) {
    printf("[MetalRender] METALRENDER_ENABLE_METALFX=%s\n", rawValue);
    envLogged = true;
  }

  if (isFalsyFlag(envValue)) {
    fprintf(
        stderr,
        "[MetalRender] MetalFX explicitly disabled via environment variable\n");
    return false;
  }
  if (@available(macOS 13.0, *)) {

    bool deviceSupported = [MTLFXSpatialScalerDescriptor supportsDevice:device];
    if (deviceSupported) {
      fprintf(stderr,
              "[MetalRender] MetalFX Spatial support detected for device: %s\n",
              [[device name] UTF8String]);
    } else {
      printf("[MetalRender] MetalFX Spatial not supported by device: %s\n",
             [[device name] UTF8String]);
    }
    return deviceSupported;
  }
  printf("[MetalRender] MetalFX requires macOS 13.0 or later\n");
  return false;
}

static void destroyMetalFXResources(MetalContext *ctx) {
  if (!ctx)
    return;
  bool hadResources = ctx->metalFxScaler || ctx->metalFxColor ||
                      ctx->metalFxDepth || ctx->metalFxOutput;
  if (ctx->metalFxDestroyed && !hadResources) {
    return;
  }
  if (ctx->metalFxDestroyed && hadResources) {
    printf("[MetalRender] destroyMetalFXResources called twice "
           "without recreation; skipping\n");
    return;
  }
  ctx->metalFxDestroyed = true;
  printf("[MetalRender] Destroying MetalFX resources (hadScaler=%s)\n",
         ctx->metalFxScaler ? "yes" : "no");
  ctx->metalFxInputWidth = 0;
  ctx->metalFxInputHeight = 0;
  ctx->metalFxOutputWidth = 0;
  ctx->metalFxOutputHeight = 0;
  ctx->metalFxResetHistory = false;
  if (ctx->metalFxScaler) {
    printf("[MetalRender] Releasing metalFxScaler\n");
    ctx->metalFxScaler = nil;
  }
  if (ctx->metalFxColor) {
    printf("[MetalRender] Releasing metalFxColor\n");
    ctx->metalFxColor = nil;
  }
  if (ctx->metalFxDepth) {
    printf("[MetalRender] Releasing metalFxDepth\n");
    ctx->metalFxDepth = nil;
  }
  if (ctx->metalFxOutput) {
    printf("[MetalRender] Releasing metalFxOutput\n");
    ctx->metalFxOutput = nil;
  }
  printf("[MetalRender] MetalFX resources destroyed.\n");
}

static bool ensureMetalFXResources(MetalContext *ctx, uint32_t outputWidth,
                                   uint32_t outputHeight, float scale) {
  if (!ctx || !ctx->metalFxSupported || !ctx->metalFxEnabled || !ctx->device) {
    return false;
  }
  if (@available(macOS 13.0, *)) {
    outputWidth = std::max<uint32_t>(1, outputWidth);
    outputHeight = std::max<uint32_t>(1, outputHeight);
    float clampedScale = clampFloat(scale, 0.25F, 1.0F);
    uint32_t inputWidth = std::max<uint32_t>(
        1, static_cast<uint32_t>(std::lround(outputWidth * clampedScale)));
    uint32_t inputHeight = std::max<uint32_t>(
        1, static_cast<uint32_t>(std::lround(outputHeight * clampedScale)));

    if (ctx->metalFxScaler && ctx->metalFxInputWidth == inputWidth &&
        ctx->metalFxInputHeight == inputHeight &&
        ctx->metalFxOutputWidth == outputWidth &&
        ctx->metalFxOutputHeight == outputHeight && ctx->metalFxColor &&
        ctx->metalFxOutput) {
      ctx->metalFxDestroyed = false;
      return true;
    }

    destroyMetalFXResources(ctx);

    printf("[MetalRender] Allocating MetalFX Spatial resources: output=%ux%u "
           "scale=%.3f input=%ux%u\n",
           outputWidth, outputHeight, clampedScale, inputWidth, inputHeight);

    MTLFXSpatialScalerDescriptor *descriptor =
        [[MTLFXSpatialScalerDescriptor alloc] init];
    descriptor.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
    descriptor.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
    descriptor.inputWidth = inputWidth;
    descriptor.inputHeight = inputHeight;
    descriptor.outputWidth = outputWidth;
    descriptor.outputHeight = outputHeight;

    id<MTLFXSpatialScaler> scaler =
        [descriptor newSpatialScalerWithDevice:ctx->device];
    if (!scaler) {
      printf("[MetalRender] Failed to create MetalFX Spatial scaler\n");
      return false;
    }

    ctx->metalFxScaler = scaler;
    ctx->metalFxInputWidth = inputWidth;
    ctx->metalFxInputHeight = inputHeight;
    ctx->metalFxOutputWidth = outputWidth;
    ctx->metalFxOutputHeight = outputHeight;
    printf("[MetalRender] MetalFX Spatial scaler created (input=%ux%u, "
           "output=%ux%u, scale=%.3f)\n",
           inputWidth, inputHeight, outputWidth, outputHeight, clampedScale);

    MTLTextureDescriptor *colorDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:inputWidth
                                    height:inputHeight
                                 mipmapped:NO];
    colorDesc.storageMode = MTLStorageModePrivate;
    colorDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    ctx->metalFxColor = [ctx->device newTextureWithDescriptor:colorDesc];
    if (!ctx->metalFxColor) {
      fprintf(
          stderr,
          "[MetalRender] Failed to allocate MetalFX color texture (%u x %u)\n",
          inputWidth, inputHeight);
      destroyMetalFXResources(ctx);
      return false;
    }

    MTLTextureDescriptor *outputDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:outputWidth
                                    height:outputHeight
                                 mipmapped:NO];
    outputDesc.storageMode = MTLStorageModePrivate;
    outputDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead |
                       MTLTextureUsageShaderWrite;
    ctx->metalFxOutput = [ctx->device newTextureWithDescriptor:outputDesc];
    if (!ctx->metalFxOutput) {
      fprintf(
          stderr,
          "[MetalRender] Failed to allocate MetalFX output texture (%u x %u)\n",
          outputWidth, outputHeight);
      destroyMetalFXResources(ctx);
      return false;
    }

    scaler.colorTexture = ctx->metalFxColor;
    scaler.outputTexture = ctx->metalFxOutput;
    ctx->metalFxDestroyed = false;
    return true;
  }
  return false;
}
#else
static bool supportsMetalFX(id<MTLDevice>) { return false; }
static void destroyMetalFXResources(MetalContext *) {}
static bool ensureMetalFXResources(MetalContext *, uint32_t, uint32_t, float) {
  return false;
}
#endif

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nIsAvailable(JNIEnv *, jclass) {
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  if (!device) {
    return JNI_FALSE;
  }
  device = nil;
  return JNI_TRUE;
}

struct DrawUniforms {
  float originX;
  float originY;
  float originZ;
  float renderLayer;
};

struct FrameUniforms {
  float viewProj[16];
  float cameraX, cameraY, cameraZ, cameraPad;
  float fogR, fogG, fogB, fogA;
  float fogStart, fogEnd, fogPad1, fogPad2;
  float texShrinkU, texShrinkV, texShrinkPad1, texShrinkPad2;
  float lightParams[4];
};

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_NativeBridge_nInit(
    JNIEnv *env, jclass, jint width, jint height, jfloat scale) {
  (void)width;
  (void)height;
  (void)scale;
  (void)env;
  std::lock_guard<std::mutex> lock(gMutex);
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  if (!device) {
    return 0;
  }
  MetalContext *ctx = new MetalContext();
  ctx->device = device;
  ctx->graphicsQueue = [device newCommandQueue];
  ctx->computeQueue = [device newCommandQueue];
  ctx->backgroundComputeQueue = [device newCommandQueue];
  ctx->library = createLibraryFromSource(device, @"MetalRenderOcclusion");
  if (ctx->library) {
    ensureOcclusionPipeline(ctx);
  }
  ctx->persistentBuffer =
      [device newBufferWithLength:ctx->persistentCapacity
                          options:MTLResourceStorageModeShared];
  ctx->indirectArgs = [device
      newBufferWithLength:ctx->maxIndirectCommands * sizeof(uint32_t) * 5
                  options:MTLResourceStorageModeShared];
  NSString *name = [device name];
  if (name)
    ctx->deviceName = [name UTF8String];
  ctx->meshShadersSupported = supportsMeshShaders(device);

  if (@available(macOS 12.0, *)) {
    ctx->icbSupported = true;
  } else {
    ctx->icbSupported = false;
  }

  ctx->ringFrameSemaphore =
      dispatch_semaphore_create(MetalContext::FRAME_OVERLAP);
  for (uint32_t i = 0; i < MetalContext::FRAME_OVERLAP; i++) {
    ctx->ringDrawUniforms[i] =
        [device newBufferWithLength:16384 * sizeof(DrawUniforms)
                            options:MTLResourceStorageModeShared];
    ctx->ringFrameUniforms[i] =
        [device newBufferWithLength:sizeof(FrameUniforms)
                            options:MTLResourceStorageModeShared];
  }
  ctx->ringIndex = 0;

  ctx->entityStagingCapacity = MetalContext::ENTITY_STAGING_SIZE;
  ctx->entityStagingBuffer =
      [device newBufferWithLength:ctx->entityStagingCapacity
                          options:MTLResourceStorageModeShared];
  ctx->entityStagingOffset = 0;

#if METALRENDER_HAS_METALFX
  ctx->metalFxSupported = supportsMetalFX(device);
#endif

  return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nResize(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || width <= 0 || height <= 0)
    return;

  float clampedScale = std::max(0.25f, std::min(2.0f, scale));
  uint32_t ioSurfaceWidth =
      std::max(1u, static_cast<uint32_t>(width * clampedScale));
  uint32_t ioSurfaceHeight =
      std::max(1u, static_cast<uint32_t>(height * clampedScale));

  ctx->renderScale = clampedScale;
  ctx->fullWidth = static_cast<uint32_t>(width);
  ctx->fullHeight = static_cast<uint32_t>(height);

  if (!ensureIOSurface(ctx, ioSurfaceWidth, ioSurfaceHeight)) {
    printf("[MetalRender] nResize: Failed to ensure IOSurface %ux%u "
           "(scale=%.2f)\n",
           ioSurfaceWidth, ioSurfaceHeight, clampedScale);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginFrame(
    JNIEnv *env, jclass, jlong handle, jfloatArray jViewProj,
    jfloatArray jCameraPos, jfloat fogStart, jfloat fogEnd) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->persistentBuffer ||
      !ctx->indirectArgs)
    return;

  if (jViewProj) {
    jfloat *viewProj = env->GetFloatArrayElements(jViewProj, nullptr);
    if (viewProj) {
      memcpy(ctx->viewProj, viewProj, 16 * sizeof(float));
      ctx->hasViewProj = true;
      env->ReleaseFloatArrayElements(jViewProj, viewProj, JNI_ABORT);
    }
  }

  if (jCameraPos && env->GetArrayLength(jCameraPos) >= 3) {
    jfloat *cameraPos = env->GetFloatArrayElements(jCameraPos, nullptr);
    if (cameraPos) {
      ctx->cameraX = cameraPos[0];
      ctx->cameraY = cameraPos[1];
      ctx->cameraZ = cameraPos[2];
      env->ReleaseFloatArrayElements(jCameraPos, cameraPos, JNI_ABORT);
    }
  }

  ctx->fogStart = fogStart;
  ctx->fogEnd = fogEnd;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain(JNIEnv *, jclass,
                                                            jlong handle,
                                                            jint pass) {
  (void)pass;

  static int traceCounter = 0;
  traceCounter++;

  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->ioSurfaceTexture ||
      !ctx->depthTexture) {
    printf("[MetalRender] nDrawTerrain: missing resources (ctx=%p, q=%p, "
           "tex=%p, depth=%p)\n",
           ctx, ctx ? ctx->graphicsQueue : 0, ctx ? ctx->ioSurfaceTexture : 0,
           ctx ? ctx->depthTexture : 0);
    return;
  }

  if (!ensureTerrainPipeline(ctx)) {

    MTLRenderPassDescriptor *passDesc =
        [MTLRenderPassDescriptor renderPassDescriptor];
    passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
    passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
    passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

    passDesc.colorAttachments[0].clearColor =
        MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    passDesc.depthAttachment.texture = ctx->depthTexture;
    passDesc.depthAttachment.loadAction = MTLLoadActionClear;
    passDesc.depthAttachment.storeAction = MTLStoreActionStore;
    passDesc.depthAttachment.clearDepth = 1.0;

    id<MTLCommandBuffer> cb = [ctx->graphicsQueue commandBuffer];
    id<MTLRenderCommandEncoder> enc =
        [cb renderCommandEncoderWithDescriptor:passDesc];
    [enc endEncoding];
    [cb commit];

    ctx->lastTerrainCommandBuffer = cb;
    return;
  }

  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

  passDesc.colorAttachments[0].clearColor =
      MTLClearColorMake(0.0, 0.0, 0.0, 0.0);

  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction = MTLLoadActionClear;
  passDesc.depthAttachment.storeAction = MTLStoreActionStore;
  passDesc.depthAttachment.clearDepth = 1.0;

  id<MTLCommandBuffer> commandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!commandBuffer) {
    printf("[MetalRender] nDrawTerrain: failed to create command buffer\n");
    return;
  }

  id<MTLRenderCommandEncoder> encoder =
      [commandBuffer renderCommandEncoderWithDescriptor:passDesc];
  if (!encoder) {
    printf("[MetalRender] nDrawTerrain: failed to create encoder\n");
    [commandBuffer commit];
    return;
  }

  [encoder setRenderPipelineState:ctx->terrainPipeline];
  [encoder setDepthStencilState:ctx->depthState];
  [encoder setCullMode:MTLCullModeNone];
  [encoder setFrontFacingWinding:MTLWindingCounterClockwise];

  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [encoder setViewport:viewport];

  FrameUniforms frameUniforms;
  memcpy(frameUniforms.viewProj, ctx->viewProj, sizeof(ctx->viewProj));
  frameUniforms.cameraX = ctx->cameraX;
  frameUniforms.cameraY = ctx->cameraY;
  frameUniforms.cameraZ = ctx->cameraZ;
  frameUniforms.cameraPad = 0;
  frameUniforms.fogR = ctx->fogR;
  frameUniforms.fogG = ctx->fogG;
  frameUniforms.fogB = ctx->fogB;
  frameUniforms.fogA = ctx->fogA;
  frameUniforms.fogStart = ctx->fogStart;
  frameUniforms.fogEnd = ctx->fogEnd;
  frameUniforms.fogPad1 = 0;
  frameUniforms.fogPad2 = 0;
  frameUniforms.texShrinkU = ctx->texShrinkU;
  frameUniforms.texShrinkV = ctx->texShrinkV;
  frameUniforms.texShrinkPad1 = 0;
  frameUniforms.texShrinkPad2 = 0;
  frameUniforms.lightParams[0] = ctx->dayBrightness;
  frameUniforms.lightParams[1] = ctx->ambientLight;
  frameUniforms.lightParams[2] = ctx->skyAngle;
  frameUniforms.lightParams[3] = 0.0f;

  [encoder setVertexBytes:&frameUniforms
                   length:sizeof(frameUniforms)
                  atIndex:2];
  [encoder setFragmentBytes:&frameUniforms
                     length:sizeof(frameUniforms)
                    atIndex:2];

  if (ctx->atlasTexture) {
    [encoder setFragmentTexture:ctx->atlasTexture atIndex:0];
  } else {

    static bool atlasWarned = false;
    if (!atlasWarned) {
      fprintf(
          stderr,
          "[MetalRender] nDrawTerrain: WARNING - no atlas texture bound!\n");
      atlasWarned = true;
    }
  }

  std::vector<uint32_t> sortedIndices;
  sortedIndices.reserve(ctx->currentIndirectCount);
  for (uint32_t i = 0;
       i < ctx->currentIndirectCount && i < ctx->drawCommands.size(); i++) {
    if (ctx->drawCommands[i].vertexCount > 0) {
      sortedIndices.push_back(i);
    }
  }

  std::sort(sortedIndices.begin(), sortedIndices.end(),
            [&](uint32_t a, uint32_t b) {
              const auto &ca = ctx->drawCommands[a];
              const auto &cb = ctx->drawCommands[b];
              if (ca.lodLevel != cb.lodLevel)
                return ca.lodLevel < cb.lodLevel;
              return ca.renderLayer < cb.renderLayer;
            });

  uint32_t drawCount = static_cast<uint32_t>(sortedIndices.size());
  if (drawCount == 0) {
    [encoder endEncoding];
    id<MTLBlitCommandEncoder> blitEncoder = [commandBuffer blitCommandEncoder];
    [blitEncoder synchronizeResource:ctx->ioSurfaceTexture];
    [blitEncoder endEncoding];
    [commandBuffer commit];
    ctx->lastTerrainCommandBuffer = commandBuffer;
    return;
  }

  size_t requiredSize = drawCount * sizeof(DrawUniforms);
  if (!ctx->drawUniformsBuffer ||
      ctx->drawUniformsBuffer.length < requiredSize) {

    uint32_t allocDraws = std::max(drawCount, 4096u);

    allocDraws--;
    allocDraws |= allocDraws >> 1;
    allocDraws |= allocDraws >> 2;
    allocDraws |= allocDraws >> 4;
    allocDraws |= allocDraws >> 8;
    allocDraws |= allocDraws >> 16;
    allocDraws++;
    size_t allocSize = allocDraws * sizeof(DrawUniforms);
    ctx->drawUniformsBuffer =
        [ctx->device newBufferWithLength:allocSize
                                 options:MTLResourceStorageModeShared];
    if (!ctx->drawUniformsBuffer) {
      fprintf(stderr,
              "[MetalRender] Failed to allocate drawUniformsBuffer (%zu "
              "bytes)\n",
              allocSize);
    }
  }

  if (ctx->drawUniformsBuffer) {
    DrawUniforms *uniforms =
        static_cast<DrawUniforms *>(ctx->drawUniformsBuffer.contents);
    for (uint32_t i = 0; i < drawCount; i++) {
      const DrawCommandData &cmd = ctx->drawCommands[sortedIndices[i]];
      uniforms[i].originX = cmd.originX;
      uniforms[i].originY = cmd.originY;
      uniforms[i].originZ = cmd.originZ;
      uniforms[i].renderLayer = static_cast<float>(cmd.renderLayer);
    }
  }

  uint32_t actualDraws = 0;
  int32_t lastRenderLayer = -1;
  int32_t lastLodLevel = -1;

  [encoder setVertexBuffer:ctx->drawUniformsBuffer offset:0 atIndex:1];
  [encoder setFragmentBuffer:ctx->drawUniformsBuffer offset:0 atIndex:0];

  [encoder setVertexBuffer:ctx->persistentBuffer offset:0 atIndex:0];

  uint32_t batchStart = 0;
  while (batchStart < drawCount) {
    const DrawCommandData &firstCmd =
        ctx->drawCommands[sortedIndices[batchStart]];
    int32_t batchLayer = static_cast<int32_t>(firstCmd.renderLayer);
    int32_t batchLod = static_cast<int32_t>(firstCmd.lodLevel);

    if (batchLod != lastLodLevel) {

      uint32_t clampedLod = static_cast<uint32_t>(
          std::min(std::max(batchLod, 0),
                   static_cast<int32_t>(MetalContext::LOD_COUNT - 1)));

      if (clampedLod < MetalContext::LOD_COUNT &&
          ctx->lodTerrainPipelines[clampedLod]) {
        [encoder setRenderPipelineState:ctx->lodTerrainPipelines[clampedLod]];
      } else {
        [encoder setRenderPipelineState:ctx->terrainPipeline];
      }
      lastLodLevel = batchLod;
    }

    if (batchLayer != lastRenderLayer) {
      if (batchLayer == 0) {
        if (ctx->opaqueTerrainPipeline && lastLodLevel <= 0) {
          [encoder setRenderPipelineState:ctx->opaqueTerrainPipeline];
        }
        [encoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
      } else if (batchLayer == 1) {
        if (ctx->cutoutTerrainPipeline && lastLodLevel <= 0) {
          [encoder setRenderPipelineState:ctx->cutoutTerrainPipeline];
        }
        [encoder setDepthBias:-1.0f slopeScale:-1.0f clamp:-0.01f];
      } else {
        if (ctx->translucentTerrainPipeline && lastLodLevel <= 0) {
          [encoder setRenderPipelineState:ctx->translucentTerrainPipeline];
        }
        [encoder setDepthBias:-1.0f slopeScale:-1.0f clamp:-0.01f];
      }
      lastRenderLayer = batchLayer;
    }

    uint32_t batchEnd = batchStart + 1;
    while (batchEnd < drawCount) {
      const DrawCommandData &nextCmd =
          ctx->drawCommands[sortedIndices[batchEnd]];
      if (static_cast<int32_t>(nextCmd.renderLayer) != batchLayer ||
          static_cast<int32_t>(nextCmd.lodLevel) != batchLod) {
        break;
      }
      batchEnd++;
    }

    for (uint32_t i = batchStart; i < batchEnd; i++) {
      const DrawCommandData &cmd = ctx->drawCommands[sortedIndices[i]];
      size_t uniformOffset = i * sizeof(DrawUniforms);
      [encoder setVertexBufferOffset:uniformOffset atIndex:1];
      [encoder setFragmentBufferOffset:uniformOffset atIndex:0];
      [encoder setVertexBufferOffset:cmd.bufferOffset atIndex:0];
      [encoder drawPrimitives:MTLPrimitiveTypeTriangle
                  vertexStart:0
                  vertexCount:cmd.vertexCount];
      actualDraws++;
    }

    batchStart = batchEnd;
  }

  [encoder endEncoding];

  id<MTLBlitCommandEncoder> blitEncoder = [commandBuffer blitCommandEncoder];
  [blitEncoder synchronizeResource:ctx->ioSurfaceTexture];
  [blitEncoder endEncoding];

  [commandBuffer commit];

  ctx->lastTerrainCommandBuffer = commandBuffer;
}
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPrewarmPipelines(JNIEnv *env,
                                                                 jclass,
                                                                 jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device || !ctx->terrainLibrary)
    return;

  struct LodConfig {
    uint32_t lodLevel;
    bool blockLight;
    bool texSample;
    bool fog;
  };

  LodConfig configs[6] = {
      {0, true, true, true},  {1, true, true, true},  {2, true, true, true},
      {3, false, true, true}, {4, false, true, true}, {5, false, false, true},
  };

  for (int i = 0; i < 6; i++) {
    if (ctx->lodTerrainPipelines[i])
      continue;

    MTLFunctionConstantValues *constants =
        [[MTLFunctionConstantValues alloc] init];
    [constants setConstantValue:&configs[i].lodLevel
                           type:MTLDataTypeUInt
                        atIndex:0];
    [constants setConstantValue:&configs[i].blockLight
                           type:MTLDataTypeBool
                        atIndex:1];
    [constants setConstantValue:&configs[i].texSample
                           type:MTLDataTypeBool
                        atIndex:2];
    [constants setConstantValue:&configs[i].fog type:MTLDataTypeBool atIndex:3];

    NSError *error = nil;
    id<MTLFunction> vertexFunc =
        [ctx->terrainLibrary newFunctionWithName:@"terrain_color_vertex"
                                  constantValues:constants
                                           error:&error];
    id<MTLFunction> fragmentFunc =
        [ctx->terrainLibrary newFunctionWithName:@"terrain_color_fragment"
                                  constantValues:constants
                                           error:&error];
    if (!vertexFunc || !fragmentFunc)
      continue;

    MTLRenderPipelineDescriptor *desc =
        [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vertexFunc;
    desc.fragmentFunction = fragmentFunc;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    if (i <= 1) {
      desc.colorAttachments[0].blendingEnabled = NO;
    } else {
      desc.colorAttachments[0].blendingEnabled = YES;
      desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
      desc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      desc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
    }
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    desc.label = [NSString stringWithFormat:@"TerrainLOD%dPrewarmed", i];

    ctx->lodTerrainPipelines[i] =
        [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (ctx->lodTerrainPipelines[i]) {
      printf("[MetalRender] Prewarmed LOD %d pipeline (blockLight=%d, tex=%d, "
             "fog=%d)\n",
             i, configs[i].blockLight, configs[i].texSample, configs[i].fog);
    }
  }
}
JNIEXPORT jintArray JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetPipelineCacheStats(
    JNIEnv *env, jclass, jlong handle) {
  jintArray result = env->NewIntArray(0);
  return result;
}
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nResetPipelineCache(
    JNIEnv *, jclass, jlong handle) {}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawOverlay(JNIEnv *, jclass,
                                                            jlong, jint) {}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOnWorldLoaded(JNIEnv *, jclass,
                                                              jlong) {}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOnWorldUnloaded(JNIEnv *,
                                                                jclass, jlong) {
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDestroy(
    JNIEnv *, jclass, jlong handle) {
  std::lock_guard<std::mutex> lock(gMutex);
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  destroyMetalFXResources(ctx);

  if (ctx->ioSurfaceTexture)
    ctx->ioSurfaceTexture = nil;
  if (ctx->ioSurface) {
    CFRelease(ctx->ioSurface);
    ctx->ioSurface = nullptr;
  }
  if (ctx->colorTexture)
    ctx->colorTexture = nil;
  if (ctx->depthTexture)
    ctx->depthTexture = nil;
  if (ctx->terrainPipeline)
    ctx->terrainPipeline = nil;
  if (ctx->depthState)
    ctx->depthState = nil;
  if (ctx->aabbBuffer)
    ctx->aabbBuffer = nil;
  if (ctx->occlusionResultBuffer)
    ctx->occlusionResultBuffer = nil;
  if (ctx->occlusionConstants)
    ctx->occlusionConstants = nil;
  if (ctx->occlusionPipeline)
    ctx->occlusionPipeline = nil;
  if (ctx->library)
    ctx->library = nil;
  if (ctx->graphicsQueue)
    ctx->graphicsQueue = nil;
  if (ctx->computeQueue)
    ctx->computeQueue = nil;
  if (ctx->persistentBuffer)
    ctx->persistentBuffer = nil;
  if (ctx->indirectArgs)
    ctx->indirectArgs = nil;
  if (ctx->device)
    ctx->device = nil;
  delete ctx;
}

JNIEXPORT jstring JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetDeviceName(JNIEnv *env,
                                                              jclass,
                                                              jlong handle) {
  printf("[MetalRender Native] nGetDeviceName called with handle: %lld\n",
         (long long)handle);
  MetalContext *ctx = getContext(handle);
  if (!ctx) {
    return env->NewStringUTF("Unknown");
  }
  return env->NewStringUTF(ctx->deviceName.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsIndirect(JNIEnv *,
                                                                 jclass) {
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsMeshShaders(JNIEnv *,
                                                                    jclass) {
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  bool supported = supportsMeshShaders(device);
  device = nil;
  return supported ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsMetalFX(JNIEnv *,
                                                                jclass) {
#if METALRENDER_HAS_METALFX
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  bool supported = supportsMetalFX(device);
  device = nil;
  return supported ? JNI_TRUE : JNI_FALSE;
#else
  return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsHiZ(JNIEnv *, jclass,
                                                            jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return JNI_FALSE;
  return ensureOcclusionPipeline(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetMetalFXEnabled(
    JNIEnv *, jclass, jlong handle, jboolean enabled) {
#if METALRENDER_HAS_METALFX
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  bool newEnabled = (enabled == JNI_TRUE);
  if (ctx->metalFxEnabled != newEnabled) {
    ctx->metalFxEnabled = newEnabled;
    if (!newEnabled) {
      destroyMetalFXResources(ctx);
    }
    printf("[MetalRender] MetalFX %s\n", newEnabled ? "enabled" : "disabled");
  }
#endif
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nConfigureMetalFX(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {
#if METALRENDER_HAS_METALFX
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  if (!ctx->metalFxSupported) {
    ctx->metalFxSupported = supportsMetalFX(ctx->device);
  }
  if (ctx->metalFxSupported && ctx->metalFxEnabled) {
    ensureMetalFXResources(ctx, static_cast<uint32_t>(width),
                           static_cast<uint32_t>(height), scale);
  }
#endif
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEnsureHiZ(JNIEnv *, jclass,
                                                          jlong handle,
                                                          jint width,
                                                          jint height) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || width <= 0 || height <= 0)
    return 0;
  HiZResources *hiz = new HiZResources();
  hiz->width = static_cast<uint32_t>(width);
  hiz->height = static_cast<uint32_t>(height);
  MTLTextureDescriptor *depthDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                   width:width
                                  height:height
                               mipmapped:YES];
  depthDesc.storageMode = MTLStorageModePrivate;
  depthDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  hiz->depthTexture = [ctx->device newTextureWithDescriptor:depthDesc];
  MTLTextureDescriptor *pyramidDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                   width:width
                                  height:height
                               mipmapped:YES];
  pyramidDesc.storageMode = MTLStorageModePrivate;
  pyramidDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
  hiz->pyramidTexture = [ctx->device newTextureWithDescriptor:pyramidDesc];
  return reinterpret_cast<jlong>(hiz);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyHiZ(JNIEnv *, jclass,
                                                           jlong,
                                                           jlong hizHandle) {
  HiZResources *hiz = getHiZ(hizHandle);
  destroyHiZImpl(hiz);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOcclusionBegin(
    JNIEnv *env, jclass, jlong handle, jlong, jfloatArray matrixArray) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ensureOcclusionPipeline(ctx);
  ctx->hasViewProj = false;
  if (matrixArray && env->GetArrayLength(matrixArray) >= 16) {
    env->GetFloatArrayRegion(matrixArray, 0, 16, ctx->viewProj);
    ctx->hasViewProj = true;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOcclusionEvaluate(
    JNIEnv *env, jclass, jlong handle, jlong hizHandle, jobject aabbBuffer,
    jint queryCount, jobject resultBuffer) {
  MetalContext *ctx = getContext(handle);
  HiZResources *hiz = getHiZ(hizHandle);
  if (!ctx || queryCount <= 0) {
    return;
  }
  if (!ensureOcclusionPipeline(ctx)) {
    return;
  }
  void *aabbPtr = env->GetDirectBufferAddress(aabbBuffer);
  void *resultPtr = env->GetDirectBufferAddress(resultBuffer);
  if (!aabbPtr || !resultPtr) {
    return;
  }
  struct OcclusionConstants {
    uint32_t count;
    float hiZWidth;
    float hiZHeight;
  };

  size_t aabbBytes = static_cast<size_t>(queryCount) * sizeof(float) * 6;
  ctx->aabbBuffer =
      ensureBufferCapacity(ctx->device, ctx->aabbBuffer, aabbBytes);
  ctx->occlusionResultBuffer =
      ensureBufferCapacity(ctx->device, ctx->occlusionResultBuffer, queryCount);
  ctx->occlusionConstants = ensureBufferCapacity(
      ctx->device, ctx->occlusionConstants, sizeof(OcclusionConstants));

  memcpy([ctx->aabbBuffer contents], aabbPtr, aabbBytes);

  OcclusionConstants *constantPtr = reinterpret_cast<OcclusionConstants *>(
      [ctx->occlusionConstants contents]);
  constantPtr->count = static_cast<uint32_t>(queryCount);
  constantPtr->hiZWidth =
      hiz && hiz->pyramidTexture ? (float)[hiz->pyramidTexture width] : 1.0f;
  constantPtr->hiZHeight =
      hiz && hiz->pyramidTexture ? (float)[hiz->pyramidTexture height] : 1.0f;

  id<MTLCommandBuffer> commandBuffer = [ctx->computeQueue commandBuffer];
  if (!commandBuffer) {
    return;
  }
  id<MTLComputeCommandEncoder> encoder = [commandBuffer computeCommandEncoder];
  if (!encoder) {
    [commandBuffer commit];
    [commandBuffer waitUntilCompleted];
    return;
  }
  [encoder setComputePipelineState:ctx->occlusionPipeline];
  [encoder setBuffer:ctx->aabbBuffer offset:0 atIndex:0];
  [encoder setBuffer:ctx->occlusionConstants offset:0 atIndex:3];
  const float *matrixPtr = ctx->hasViewProj ? ctx->viewProj : nullptr;
  float fallback[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  if (!matrixPtr)
    matrixPtr = fallback;
  [encoder setBytes:matrixPtr length:sizeof(float) * 16 atIndex:1];
  [encoder setBuffer:ctx->occlusionResultBuffer offset:0 atIndex:2];

  if (hiz && hiz->pyramidTexture) {
    [encoder setTexture:hiz->pyramidTexture atIndex:0];
  }

  NSUInteger threadCount = ctx->occlusionPipeline.threadExecutionWidth;
  NSUInteger groups = (queryCount + threadCount - 1) / threadCount;
  MTLSize grid = MTLSizeMake(groups * threadCount, 1, 1);
  MTLSize threadsPerGroup = MTLSizeMake(threadCount, 1, 1);
  [encoder dispatchThreads:grid threadsPerThreadgroup:threadsPerGroup];
  [encoder endEncoding];

  [commandBuffer commit];
  [commandBuffer waitUntilCompleted];

  memcpy(resultPtr, [ctx->occlusionResultBuffer contents], queryCount);
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nUploadAtlas(JNIEnv *env, jclass,
                                                            jlong handle,
                                                            jobject dataBuffer,
                                                            jint width,
                                                            jint height) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device || !dataBuffer || width <= 0 || height <= 0) {
    printf("[MetalRender] nUploadAtlas: invalid parameters\n");
    return JNI_FALSE;
  }

  void *data = env->GetDirectBufferAddress(dataBuffer);
  jlong bufferSize = env->GetDirectBufferCapacity(dataBuffer);

  if (!data || bufferSize < (jlong)(width * height * 4)) {
    printf("[MetalRender] nUploadAtlas: invalid buffer (data=%p, size=%lld, "
           "needed=%d)\n",
           data, (long long)bufferSize, width * height * 4);
    return JNI_FALSE;
  }

  if (ctx->atlasTexture) {
    ctx->atlasTexture = nil;
  }

  NSUInteger maxDim = std::max((NSUInteger)width, (NSUInteger)height);
  NSUInteger mipLevels = 1;
  NSUInteger dim = maxDim;
  while (dim > 1) {
    dim >>= 1;
    mipLevels++;
  }

  MTLTextureDescriptor *texDesc = [[MTLTextureDescriptor alloc] init];
  texDesc.textureType = MTLTextureType2D;
  texDesc.pixelFormat = MTLPixelFormatBGRA8Unorm;
  texDesc.width = (NSUInteger)width;
  texDesc.height = (NSUInteger)height;
  texDesc.mipmapLevelCount = mipLevels;
  texDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;

  texDesc.storageMode = MTLStorageModeShared;

  ctx->atlasTexture = [ctx->device newTextureWithDescriptor:texDesc];
  if (!ctx->atlasTexture) {
    printf("[MetalRender] nUploadAtlas: failed to create texture %dx%d\n",
           width, height);
    return JNI_FALSE;
  }

  MTLRegion region =
      MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);
  NSUInteger bytesPerRow = (NSUInteger)(width * 4);
  [ctx->atlasTexture replaceRegion:region
                       mipmapLevel:0
                         withBytes:data
                       bytesPerRow:bytesPerRow];

  id<MTLCommandBuffer> mipCmdBuf = [ctx->graphicsQueue commandBuffer];
  if (mipCmdBuf) {
    id<MTLBlitCommandEncoder> blitEnc = [mipCmdBuf blitCommandEncoder];
    if (blitEnc) {
      [blitEnc generateMipmapsForTexture:ctx->atlasTexture];
      [blitEnc endEncoding];
    }
    [mipCmdBuf commit];
    [mipCmdBuf waitUntilCompleted];
  }

  if (ctx->terrainSampler) {
    MTLSamplerDescriptor *samplerDesc = [[MTLSamplerDescriptor alloc] init];
    samplerDesc.minFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.magFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.mipFilter = MTLSamplerMipFilterNearest;
    samplerDesc.sAddressMode = MTLSamplerAddressModeRepeat;
    samplerDesc.tAddressMode = MTLSamplerAddressModeRepeat;
    samplerDesc.maxAnisotropy = 1;
    id<MTLSamplerState> newSampler =
        [ctx->device newSamplerStateWithDescriptor:samplerDesc];
    if (newSampler) {
      ctx->terrainSampler = newSampler;
    }
  }

  fprintf(stderr,
          "[MetalRender] nUploadAtlas: uploaded %dx%d atlas texture with %lu "
          "mip levels\n",
          width, height, (unsigned long)mipLevels);
  return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nMapPersistentBuffer(
    JNIEnv *env, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->persistentBuffer)
    return nullptr;
  return env->NewDirectByteBuffer([ctx->persistentBuffer contents],
                                  ctx -> persistentCapacity);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPersistentCapacity(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return 0;
  return static_cast<jint>(ctx->persistentCapacity);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPersistentAlign(JNIEnv *,
                                                                jclass,
                                                                jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return 256;
  return static_cast<jint>(ctx->persistentAlignment);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPersistentAdvance(JNIEnv *,
                                                                  jclass,
                                                                  jlong handle,
                                                                  jint bytes) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  size_t value = static_cast<size_t>(std::max(0, bytes));
  if (ctx->persistentCapacity == 0) {
    value = 0;
  } else if (value > ctx->persistentCapacity) {
    value = value % ctx->persistentCapacity;
  }
  ctx->persistentCursor = value;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nClearIndirectCommands(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->currentIndirectCount = 0;
  ctx->drawCommands.clear();
  if (ctx->indirectArgs) {
    memset([ctx->indirectArgs contents], 0, [ctx->indirectArgs length]);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nQueueIndirectDraw(
    JNIEnv *, jclass, jlong handle, jint commandIndex, jlong bufferOffset,
    jlong, jint vertexCount, jint chunkX, jint chunkY, jint chunkZ,
    jint renderLayer) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  if (commandIndex < 0 ||
      commandIndex >= static_cast<jint>(ctx->maxIndirectCommands))
    return;

  DrawCommandData cmd;
  cmd.bufferOffset = static_cast<uint32_t>(bufferOffset);
  cmd.vertexCount = static_cast<uint32_t>(vertexCount);
  cmd.originX = static_cast<float>(chunkX);
  cmd.originY = static_cast<float>(chunkY);
  cmd.originZ = static_cast<float>(chunkZ);
  cmd.renderLayer = static_cast<uint32_t>(renderLayer);

  if (static_cast<size_t>(commandIndex) >= ctx->drawCommands.size()) {
    ctx->drawCommands.resize(commandIndex + 1);
  }
  ctx->drawCommands[commandIndex] = cmd;

  uint32_t nextCount = static_cast<uint32_t>(commandIndex + 1);
  ctx->currentIndirectCount = std::max(ctx->currentIndirectCount, nextCount);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBatchDrawCommands(
    JNIEnv *env, jclass, jlong handle, jobject buffer, jint commandCount) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || commandCount <= 0)
    return;

  uint8_t *data = (uint8_t *)env->GetDirectBufferAddress(buffer);
  if (!data)
    return;

  uint32_t count = static_cast<uint32_t>(commandCount);
  if (count > ctx->maxIndirectCommands)
    count = ctx->maxIndirectCommands;

  ctx->drawCommands.resize(count);
  ctx->currentIndirectCount = count;

  static const size_t ENTRY_SIZE = 32;
  for (uint32_t i = 0; i < count; i++) {
    uint8_t *entry = data + i * ENTRY_SIZE;
    DrawCommandData &cmd = ctx->drawCommands[i];

    uint32_t offset, vertexCount, renderLayer, lodLevel;
    float ox, oy, oz;
    memcpy(&offset, entry + 0, 4);
    memcpy(&vertexCount, entry + 4, 4);
    memcpy(&ox, entry + 8, 4);
    memcpy(&oy, entry + 12, 4);
    memcpy(&oz, entry + 16, 4);
    memcpy(&renderLayer, entry + 20, 4);

    memcpy(&lodLevel, entry + 28, 4);

    cmd.bufferOffset = offset;
    cmd.vertexCount = vertexCount;
    cmd.originX = ox;
    cmd.originY = oy;
    cmd.originZ = oz;
    cmd.renderLayer = renderLayer;
    cmd.lodLevel = lodLevel;
  }
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nExecuteIndirect(JNIEnv *,
                                                                jclass,
                                                                jlong handle,
                                                                jint) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->persistentBuffer ||
      !ctx->indirectArgs)
    return 0;
  if (ctx->currentIndirectCount == 0)
    return 0;

  id<MTLCommandBuffer> commandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!commandBuffer)
    return 0;

#if METALRENDER_HAS_METALFX
  if (ctx->metalFxEnabled && ctx->metalFxScaler && ctx->metalFxColor &&
      ctx->metalFxOutput) {

    MTLRenderPassDescriptor *passDesc =
        [MTLRenderPassDescriptor renderPassDescriptor];
    passDesc.colorAttachments[0].texture = ctx->metalFxColor;
    passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
    passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
    passDesc.colorAttachments[0].clearColor =
        MTLClearColorMake(0.0, 0.0, 0.0, 0.0);

    id<MTLRenderCommandEncoder> encoder =
        [commandBuffer renderCommandEncoderWithDescriptor:passDesc];
    if (encoder) {
      [encoder endEncoding];
    }

    ctx->metalFxScaler.colorTexture = ctx->metalFxColor;
    ctx->metalFxScaler.outputTexture = ctx->metalFxOutput;
    [ctx->metalFxScaler encodeToCommandBuffer:commandBuffer];

    [commandBuffer commit];
    return static_cast<jint>(ctx->currentIndirectCount);
  }
#endif
  [commandBuffer commit];
  return static_cast<jint>(ctx->currentIndirectCount);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetTemporalJitter(
    JNIEnv *, jclass, jlong handle, jfloat jitterX, jfloat jitterY,
    jfloat blendFactor) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->temporalJitterX = jitterX;
  ctx->temporalJitterY = jitterY;
  ctx->temporalBlend = blendFactor;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetLightParams(
    JNIEnv *, jclass, jlong handle, jfloat dayBrightness, jfloat ambientLight,
    jfloat skyAngle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->dayBrightness = dayBrightness;
  ctx->ambientLight = ambientLight;
  ctx->skyAngle = skyAngle;
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetIOSurfaceWidth(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->ioSurface)
    return 0;
  return static_cast<jint>(ctx->ioSurfaceWidth);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetIOSurfaceHeight(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->ioSurface)
    return 0;
  return static_cast<jint>(ctx->ioSurfaceHeight);
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBindIOSurfaceToTexture(
    JNIEnv *, jclass, jlong handle, jint glTexture) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->ioSurface || glTexture <= 0)
    return JNI_FALSE;

  CGLContextObj cglContext = CGLGetCurrentContext();
  if (!cglContext) {
    printf("[MetalRender] No current CGL context for IOSurface binding\n");
    return JNI_FALSE;
  }

  GLenum target = GL_TEXTURE_RECTANGLE;
  glBindTexture(target, static_cast<GLuint>(glTexture));

  CGLError err = CGLTexImageIOSurface2D(
      cglContext, target, GL_RGBA, static_cast<GLsizei>(ctx->ioSurfaceWidth),
      static_cast<GLsizei>(ctx->ioSurfaceHeight), GL_BGRA,
      GL_UNSIGNED_INT_8_8_8_8_REV, ctx->ioSurface, 0);

  if (err != kCGLNoError) {
    printf("[MetalRender] CGLTexImageIOSurface2D failed: %d\n", err);
    return JNI_FALSE;
  }

  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nWaitForRender(JNIEnv *, jclass,
                                                              jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue) {
    return;
  }

  if (ctx->lastTerrainCommandBuffer) {
    [ctx->lastTerrainCommandBuffer waitUntilCompleted];
    ctx->lastTerrainCommandBuffer = nil;
  }

  if (ctx->lastEntityCommandBuffer) {
    [ctx->lastEntityCommandBuffer waitUntilCompleted];
    ctx->lastEntityCommandBuffer = nil;
  }

  if (ctx->lastGuiCommandBuffer) {
    [ctx->lastGuiCommandBuffer waitUntilCompleted];
    ctx->lastGuiCommandBuffer = nil;
  }
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nReadbackPixels(JNIEnv *env,
                                                               jclass,
                                                               jlong handle,
                                                               jobject buffer) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->ioSurfaceTexture) {
    printf("[MetalRender] nReadbackPixels: no texture available\n");
    return JNI_FALSE;
  }

  if (!buffer) {
    printf("[MetalRender] nReadbackPixels: null buffer\n");
    return JNI_FALSE;
  }

  void *bufferPtr = env->GetDirectBufferAddress(buffer);
  jlong bufferCapacity = env->GetDirectBufferCapacity(buffer);

  if (!bufferPtr) {
    printf("[MetalRender] nReadbackPixels: not a direct buffer\n");
    return JNI_FALSE;
  }

  uint32_t width = ctx->ioSurfaceWidth;
  uint32_t height = ctx->ioSurfaceHeight;
  size_t requiredSize = width * height * 4;

  if (bufferCapacity < (jlong)requiredSize) {
    printf("[MetalRender] nReadbackPixels: buffer too small (%lld < %zu)\n",
           bufferCapacity, requiredSize);
    return JNI_FALSE;
  }

  IOReturn lockResult =
      IOSurfaceLock(ctx->ioSurface, kIOSurfaceLockReadOnly, nullptr);
  if (lockResult != kIOReturnSuccess) {
    fprintf(
        stderr,
        "[MetalRender] nReadbackPixels: Failed to lock IOSurface (error %x)\n",
        lockResult);
    return JNI_FALSE;
  }

  void *baseAddr = IOSurfaceGetBaseAddress(ctx->ioSurface);
  size_t sourceStride = IOSurfaceGetBytesPerRow(ctx->ioSurface);

  uint8_t *src = (uint8_t *)baseAddr;
  uint8_t *dst = (uint8_t *)bufferPtr;
  size_t rowBytes = width * 4;

  if (sourceStride < rowBytes) {

    rowBytes = sourceStride;
  }

  for (uint32_t y = 0; y < height; y++) {
    memcpy(dst + y * (width * 4), src + y * sourceStride, rowBytes);
  }

  IOSurfaceUnlock(ctx->ioSurface, kIOSurfaceLockReadOnly, nullptr);

  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetShadersPath(JNIEnv *env,
                                                               jclass,
                                                               jlong handle,
                                                               jstring jpath) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  if (!jpath) {
    ctx->shadersPath.clear();
    return;
  }

  const char *cpath = env->GetStringUTFChars(jpath, nullptr);
  if (cpath) {
    ctx->shadersPath = cpath;
    printf("[MetalRender] Shaders path set to: %s\n", ctx->shadersPath.c_str());
    env->ReleaseStringUTFChars(jpath, cpath);
  }
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nAttachSurface(
    JNIEnv *, jclass, jlong handle, jlong cocoaWindow) {
  printf("[MetalRender] *** nAttachSurface CALLED *** handle=%lld, "
         "cocoaWindow=%lld\\n",
         handle, cocoaWindow);

  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device) {
    printf("[MetalRender] nAttachSurface: no context or device\\n");
    return JNI_FALSE;
  }

  printf("[MetalRender] nAttachSurface: ctx=%p, device=%p, ioSurfaceWidth=%u, "
         "ioSurfaceHeight=%u\\n",
         ctx, (void *)ctx->device, ctx->ioSurfaceWidth, ctx->ioSurfaceHeight);

  @autoreleasepool {
    NSWindow *window = (__bridge NSWindow *)(void *)cocoaWindow;
    if (!window) {
      printf("[MetalRender] nAttachSurface: invalid window handle\n");
      return JNI_FALSE;
    }

    NSView *contentView = [window contentView];
    if (!contentView) {
      printf("[MetalRender] nAttachSurface: no content view\n");
      return JNI_FALSE;
    }

    CAMetalLayer *metalLayer = [CAMetalLayer layer];
    metalLayer.device = ctx->device;
    metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    metalLayer.framebufferOnly = NO;
    metalLayer.drawableSize =
        CGSizeMake(ctx->ioSurfaceWidth, ctx->ioSurfaceHeight);
    metalLayer.opaque = YES;
    metalLayer.frame = contentView.bounds;
    metalLayer.contentsScale = contentView.window.backingScaleFactor;

    metalLayer.displaySyncEnabled = NO;

    metalLayer.maximumDrawableCount = 3;

    metalLayer.allowsNextDrawableTimeout = YES;

    __block bool layerAdded = false;
    if ([NSThread isMainThread]) {

      [contentView setWantsLayer:YES];
      metalLayer.zPosition = 1000;
      [[contentView layer] addSublayer:metalLayer];
      layerAdded = true;
      printf("[MetalRender] CAMetalLayer added on main thread (direct)\n");
    } else {

      dispatch_sync(dispatch_get_main_queue(), ^{
        [contentView setWantsLayer:YES];
        metalLayer.zPosition = 1000;
        [[contentView layer] addSublayer:metalLayer];
        layerAdded = true;
        printf(
            "[MetalRender] CAMetalLayer added on main thread (dispatched)\n");
      });
    }

    if (!layerAdded) {
      printf("[MetalRender] nAttachSurface: FAILED to add layer\n");
      return JNI_FALSE;
    }

    ctx->metalLayer = metalLayer;
    ctx->directPresentEnabled = true;

    printf("[MetalRender] nAttachSurface: CAMetalLayer added as sublayer, "
           "size=%ux%u\n",
           ctx->ioSurfaceWidth, ctx->ioSurfaceHeight);
    return JNI_TRUE;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDetachSurface(JNIEnv *, jclass,
                                                              jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (ctx) {
    ctx->metalLayer = nil;
    ctx->directPresentEnabled = false;
  }
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPresentFrame(JNIEnv *, jclass,
                                                             jlong handle) {
  MetalContext *ctx = getContext(handle);

  static int presentFrameCount = 0;
  presentFrameCount++;

  if (!ctx || !ctx->directPresentEnabled || !ctx->metalLayer ||
      !ctx->ioSurfaceTexture) {
    return JNI_FALSE;
  }

  @autoreleasepool {

    CGSize currentSize = ctx->metalLayer.drawableSize;
    if (currentSize.width != ctx->ioSurfaceWidth ||
        currentSize.height != ctx->ioSurfaceHeight) {
      ctx->metalLayer.drawableSize =
          CGSizeMake(ctx->ioSurfaceWidth, ctx->ioSurfaceHeight);
    }

    id<CAMetalDrawable> drawable = [ctx->metalLayer nextDrawable];
    if (!drawable) {
      return JNI_FALSE;
    }

    id<MTLCommandBuffer> commandBuffer = [ctx->graphicsQueue commandBuffer];
    if (!commandBuffer) {
      return JNI_FALSE;
    }

    id<MTLBlitCommandEncoder> blitEncoder = [commandBuffer blitCommandEncoder];
    [blitEncoder copyFromTexture:ctx->ioSurfaceTexture
                     sourceSlice:0
                     sourceLevel:0
                    sourceOrigin:MTLOriginMake(0, 0, 0)
                      sourceSize:MTLSizeMake(ctx->ioSurfaceWidth,
                                             ctx->ioSurfaceHeight, 1)
                       toTexture:drawable.texture
                destinationSlice:0
                destinationLevel:0
               destinationOrigin:MTLOriginMake(0, 0, 0)];
    [blitEncoder endEncoding];

    [commandBuffer presentDrawable:drawable];
    [commandBuffer commit];

    return JNI_TRUE;
  }
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetDeviceMemory(JNIEnv *,
                                                                jclass,
                                                                jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device)
    return 0;

  if (@available(macOS 10.13, *)) {
    return static_cast<jlong>([ctx->device recommendedMaxWorkingSetSize]);
  }
  return 0;
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetMemoryUsage(JNIEnv *, jclass,
                                                               jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device)
    return 0;

  if (@available(macOS 10.13, *)) {
    return static_cast<jlong>([ctx->device currentAllocatedSize]);
  }
  return 0;
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nCreateFence(JNIEnv *, jclass,
                                                            jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device)
    return 0;
  id<MTLFence> fence = [ctx->device newFence];
  if (!fence)
    return 0;

  return reinterpret_cast<jlong>((__bridge_retained void *)fence);
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPollFence(JNIEnv *, jclass,
                                                          jlong handle,
                                                          jlong fenceHandle) {
  (void)handle;
  if (fenceHandle == 0)
    return JNI_TRUE;

  return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nWaitFence(JNIEnv *, jclass,
                                                          jlong handle,
                                                          jlong fenceHandle,
                                                          jlong timeout) {
  (void)handle;
  (void)timeout;
  if (fenceHandle == 0)
    return 0;

  return 0;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyFence(
    JNIEnv *, jclass, jlong handle, jlong fenceHandle) {
  (void)handle;
  if (fenceHandle == 0)
    return;
  id<MTLFence> fence = (__bridge_transfer id<MTLFence>)(void *)fenceHandle;
  fence = nil;
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nUploadEntityTexture(
    JNIEnv *env, jclass, jlong handle, jobject pixels, jint width,
    jint height) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device) {
    fprintf(stderr, "[metalrender] nUploadEntityTexture: no device\n");
    return 0;
  }

  void *pixelData = env->GetDirectBufferAddress(pixels);
  if (!pixelData) {
    fprintf(stderr, "[metalrender] nUploadEntityTexture: invalid buffer\n");
    return 0;
  }

  MTLTextureDescriptor *desc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                   width:width
                                  height:height
                               mipmapped:NO];
  desc.usage = MTLTextureUsageShaderRead;
  desc.storageMode = MTLStorageModeShared;

  id<MTLTexture> texture = [ctx->device newTextureWithDescriptor:desc];
  if (!texture) {
    fprintf(stderr,
            "[metalrender] nUploadEntityTexture: failed to create texture\n");
    return 0;
  }

  MTLRegion region = MTLRegionMake2D(0, 0, width, height);
  [texture replaceRegion:region
             mipmapLevel:0
               withBytes:pixelData
             bytesPerRow:width * 4];

  uint64_t texHandle =
      reinterpret_cast<uint64_t>((__bridge_retained void *)texture);
  ctx->entityTextures[texHandle] = texture;

  return static_cast<jlong>(texHandle);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyEntityTexture(
    JNIEnv *, jclass, jlong handle, jlong textureHandle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  auto it = ctx->entityTextures.find(static_cast<uint64_t>(textureHandle));
  if (it != ctx->entityTextures.end()) {
    ctx->entityTextures.erase(it);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginEntityPass(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj,
    jfloatArray cameraPos) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->ioSurfaceTexture ||
      !ctx->depthTexture) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr, "[metalrender] nBeginEntityPass: missing resources\n");
    }
    return;
  }

  if (!ensureEntityPipeline(ctx)) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr,
              "[metalrender] nBeginEntityPass: failed to create pipeline\n");
    }
    return;
  }

  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    ctx->lastEntityCommandBuffer = ctx->currentEntityCommandBuffer;
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
  }

  if (ctx->lastTerrainCommandBuffer) {
    [ctx->lastTerrainCommandBuffer waitUntilCompleted];
    ctx->lastTerrainCommandBuffer = nil;
  }

  if (ctx->lastEntityCommandBuffer) {
    [ctx->lastEntityCommandBuffer waitUntilCompleted];
    ctx->lastEntityCommandBuffer = nil;
  }

  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction = MTLLoadActionLoad;
  passDesc.depthAttachment.storeAction = MTLStoreActionStore;

  ctx->currentEntityCommandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!ctx->currentEntityCommandBuffer) {
    fprintf(
        stderr,
        "[metalrender] nBeginEntityPass: failed to create command buffer\n");
    return;
  }

  ctx->currentEntityEncoder = [ctx->currentEntityCommandBuffer
      renderCommandEncoderWithDescriptor:passDesc];
  if (!ctx->currentEntityEncoder) {
    fprintf(stderr,
            "[metalrender] nBeginEntityPass: failed to create encoder\n");
    [ctx->currentEntityCommandBuffer commit];
    ctx->currentEntityCommandBuffer = nil;
    return;
  }

  [ctx->currentEntityEncoder setRenderPipelineState:ctx->entityPipeline];
  [ctx->currentEntityEncoder setDepthStencilState:ctx->entityDepthState];
  [ctx->currentEntityEncoder setCullMode:MTLCullModeNone];

  [ctx->currentEntityEncoder setFrontFacingWinding:MTLWindingCounterClockwise];

  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [ctx->currentEntityEncoder setViewport:viewport];

  EntityFrameUniforms frameUniforms;
  memset(&frameUniforms, 0, sizeof(frameUniforms));

  if (viewProj && env->GetArrayLength(viewProj) >= 16) {
    jfloat *vp = env->GetFloatArrayElements(viewProj, nullptr);
    if (vp) {
      memcpy(frameUniforms.viewProj, vp, 16 * sizeof(float));
      env->ReleaseFloatArrayElements(viewProj, vp, JNI_ABORT);
    }
  }

  if (cameraPos && env->GetArrayLength(cameraPos) >= 3) {
    jfloat *cp = env->GetFloatArrayElements(cameraPos, nullptr);
    if (cp) {
      frameUniforms.cameraPos[0] = cp[0];
      frameUniforms.cameraPos[1] = cp[1];
      frameUniforms.cameraPos[2] = cp[2];
      frameUniforms.cameraPos[3] = 0.0f;
      env->ReleaseFloatArrayElements(cameraPos, cp, JNI_ABORT);
    }
  }

  frameUniforms.fogColor[0] = ctx->fogR;
  frameUniforms.fogColor[1] = ctx->fogG;
  frameUniforms.fogColor[2] = ctx->fogB;
  frameUniforms.fogColor[3] = ctx->fogA;
  frameUniforms.fogParams[0] = ctx->fogStart;
  frameUniforms.fogParams[1] = ctx->fogEnd;
  frameUniforms.fogParams[2] = 0.0f;
  frameUniforms.fogParams[3] = 0.0f;

  frameUniforms.lightParams[0] = ctx->dayBrightness;
  frameUniforms.lightParams[1] = ctx->ambientLight;
  frameUniforms.lightParams[2] = ctx->skyAngle;
  frameUniforms.lightParams[3] = 0.0f;

  [ctx->currentEntityEncoder setVertexBytes:&frameUniforms
                                     length:sizeof(frameUniforms)
                                    atIndex:1];
  [ctx->currentEntityEncoder setFragmentBytes:&frameUniforms
                                       length:sizeof(frameUniforms)
                                      atIndex:1];

  ctx->entityPassActive = true;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawEntity(
    JNIEnv *env, jclass, jlong handle, jobject vertices, jint vertexCount,
    jlong textureHandle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->entityPassActive || !ctx->currentEntityEncoder) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr, "[metalrender] nDrawEntity: no active entity pass\n");
    }
    return;
  }

  if (vertexCount <= 0)
    return;

  void *vertexData = env->GetDirectBufferAddress(vertices);
  if (!vertexData) {
    fprintf(stderr, "[metalrender] nDrawEntity: invalid vertex buffer\n");
    return;
  }

  size_t vertexDataSize = static_cast<size_t>(vertexCount) * 32;

  id<MTLBuffer> vertexBuffer =
      [ctx->device newBufferWithBytes:vertexData
                               length:vertexDataSize
                              options:MTLResourceStorageModeShared];
  if (!vertexBuffer) {
    fprintf(stderr,
            "[metalrender] nDrawEntity: failed to create vertex buffer\n");
    return;
  }

  [ctx->currentEntityEncoder setVertexBuffer:vertexBuffer offset:0 atIndex:0];

  id<MTLTexture> textureToUse = nil;

  if (textureHandle != 0) {
    auto it = ctx->entityTextures.find(static_cast<uint64_t>(textureHandle));
    if (it != ctx->entityTextures.end() && it->second) {
      textureToUse = it->second;
    }
  }

  if (!textureToUse && ctx->whiteTexture) {
    textureToUse = ctx->whiteTexture;
  }

  if (textureToUse) {
    [ctx->currentEntityEncoder setFragmentTexture:textureToUse atIndex:0];
  }

  [ctx->currentEntityEncoder
      drawPrimitives:MTLPrimitiveTypeTriangle
         vertexStart:0
         vertexCount:static_cast<NSUInteger>(vertexCount)];
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEndEntityPass(JNIEnv *, jclass,
                                                              jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];

    ctx->lastEntityCommandBuffer = ctx->currentEntityCommandBuffer;

    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;
  }
}

static bool itemPassActive = false;
static bool currentItemDepthTest = false;

static id<MTLDepthStencilState> itemDepthTestState = nil;
static id<MTLDepthStencilState> itemNoDepthTestState = nil;

static void ensureItemDepthStates(MetalContext *ctx) {
  if (!itemDepthTestState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction = MTLCompareFunctionLessEqual;
    depthDesc.depthWriteEnabled = YES;
    itemDepthTestState =
        [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
  }
  if (!itemNoDepthTestState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction = MTLCompareFunctionAlways;
    depthDesc.depthWriteEnabled = NO;
    itemNoDepthTestState =
        [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginItemPass(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj,
    jfloatArray cameraPos) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->ioSurfaceTexture) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr, "[metalrender] nBeginItemPass: missing resources\n");
    }
    return;
  }

  if (!ensureEntityPipeline(ctx)) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr,
              "[metalrender] nBeginItemPass: failed to create pipeline\n");
    }
    return;
  }

  ensureItemDepthStates(ctx);

  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;
  }

  jfloat *viewProjData = env->GetFloatArrayElements(viewProj, nullptr);
  jfloat *cameraPosData = env->GetFloatArrayElements(cameraPos, nullptr);

  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction = MTLLoadActionLoad;
  passDesc.depthAttachment.storeAction = MTLStoreActionStore;

  id<MTLCommandBuffer> commandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!commandBuffer) {
    fprintf(stderr,
            "[metalrender] nBeginItemPass: failed to create command buffer\n");
    env->ReleaseFloatArrayElements(viewProj, viewProjData, JNI_ABORT);
    env->ReleaseFloatArrayElements(cameraPos, cameraPosData, JNI_ABORT);
    return;
  }

  id<MTLRenderCommandEncoder> encoder =
      [commandBuffer renderCommandEncoderWithDescriptor:passDesc];
  if (!encoder) {
    fprintf(stderr, "[metalrender] nBeginItemPass: failed to create encoder\n");
    [commandBuffer commit];
    env->ReleaseFloatArrayElements(viewProj, viewProjData, JNI_ABORT);
    env->ReleaseFloatArrayElements(cameraPos, cameraPosData, JNI_ABORT);
    return;
  }

  ctx->currentEntityEncoder = encoder;
  ctx->currentEntityCommandBuffer = commandBuffer;

  [encoder setRenderPipelineState:ctx->entityPipeline];
  [encoder setCullMode:MTLCullModeNone];
  [encoder setFrontFacingWinding:MTLWindingCounterClockwise];

  [encoder setDepthStencilState:itemNoDepthTestState];
  currentItemDepthTest = false;

  EntityFrameUniforms uniforms;
  memcpy(uniforms.viewProj, viewProjData, 16 * sizeof(float));
  uniforms.cameraPos[0] = cameraPosData[0];
  uniforms.cameraPos[1] = cameraPosData[1];
  uniforms.cameraPos[2] = cameraPosData[2];
  uniforms.cameraPos[3] = 1.0f;

  uniforms.fogColor[0] = 1.0f;
  uniforms.fogColor[1] = 1.0f;
  uniforms.fogColor[2] = 1.0f;
  uniforms.fogColor[3] = 0.0f;
  uniforms.fogParams[0] = 1000.0f;
  uniforms.fogParams[1] = 10000.0f;
  uniforms.fogParams[2] = 0.0f;
  uniforms.fogParams[3] = 0.0f;

  uniforms.lightParams[0] = 1.0f;
  uniforms.lightParams[1] = 0.1f;
  uniforms.lightParams[2] = 0.0f;
  uniforms.lightParams[3] = 0.0f;
  [encoder setVertexBytes:&uniforms
                   length:sizeof(EntityFrameUniforms)
                  atIndex:1];
  [encoder setFragmentBytes:&uniforms
                     length:sizeof(EntityFrameUniforms)
                    atIndex:1];

  env->ReleaseFloatArrayElements(viewProj, viewProjData, JNI_ABORT);
  env->ReleaseFloatArrayElements(cameraPos, cameraPosData, JNI_ABORT);

  itemPassActive = true;
  ctx->entityPassActive = true;
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDrawItem(
    JNIEnv *env, jclass, jlong handle, jobject vertices, jint vertexCount,
    jlong textureHandle, jboolean useDepthTest) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->entityPassActive || !ctx->currentEntityEncoder) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr, "[metalrender] nDrawItem: no active item pass\n");
    }
    return;
  }

  if (vertexCount <= 0)
    return;

  void *vertexData = env->GetDirectBufferAddress(vertices);
  if (!vertexData) {
    fprintf(stderr, "[metalrender] nDrawItem: invalid vertex buffer\n");
    return;
  }

  bool needsDepth = (useDepthTest == JNI_TRUE);
  if (needsDepth != currentItemDepthTest) {
    if (needsDepth) {
      [ctx->currentEntityEncoder setDepthStencilState:itemDepthTestState];
    } else {
      [ctx->currentEntityEncoder setDepthStencilState:itemNoDepthTestState];
    }
    currentItemDepthTest = needsDepth;
  }

  size_t vertexDataSize = static_cast<size_t>(vertexCount) * 32;

  id<MTLBuffer> vertexBuffer =
      [ctx->device newBufferWithBytes:vertexData
                               length:vertexDataSize
                              options:MTLResourceStorageModeShared];
  if (!vertexBuffer) {
    fprintf(stderr,
            "[metalrender] nDrawItem: failed to create vertex buffer\n");
    return;
  }

  [ctx->currentEntityEncoder setVertexBuffer:vertexBuffer offset:0 atIndex:0];

  id<MTLTexture> textureToUse = nil;

  if (textureHandle != 0) {
    auto it = ctx->entityTextures.find(static_cast<uint64_t>(textureHandle));
    if (it != ctx->entityTextures.end() && it->second) {
      textureToUse = it->second;
    }
  }

  if (!textureToUse && ctx->whiteTexture) {
    textureToUse = ctx->whiteTexture;
  }

  if (textureToUse) {
    [ctx->currentEntityEncoder setFragmentTexture:textureToUse atIndex:0];
  }

  [ctx->currentEntityEncoder
      drawPrimitives:MTLPrimitiveTypeTriangle
         vertexStart:0
         vertexCount:static_cast<NSUInteger>(vertexCount)];
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEndItemPass(JNIEnv *, jclass,
                                                            jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  if (itemPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];

    ctx->lastEntityCommandBuffer = ctx->currentEntityCommandBuffer;
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;
    itemPassActive = false;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nClearDepthForHand(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->depthTexture) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr, "[metalrender] nClearDepthForHand: missing resources\n");
    }
    return;
  }

  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];

  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction = MTLLoadActionClear;
  passDesc.depthAttachment.storeAction = MTLStoreActionStore;
  passDesc.depthAttachment.clearDepth = 1.0;

  id<MTLCommandBuffer> commandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!commandBuffer) {
    fprintf(
        stderr,
        "[metalrender] nClearDepthForHand: failed to create command buffer\n");
    return;
  }

  id<MTLRenderCommandEncoder> encoder =
      [commandBuffer renderCommandEncoderWithDescriptor:passDesc];
  if (!encoder) {
    fprintf(stderr,
            "[metalrender] nClearDepthForHand: failed to create encoder\n");
    [commandBuffer commit];
    return;
  }

  [encoder endEncoding];
  [commandBuffer commit];
}

static id<MTLDepthStencilState> guiDepthState = nil;

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginGuiPass(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj,
    jfloatArray cameraPos) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->ioSurfaceTexture) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr, "[metalrender] nBeginGuiPass: missing resources\n");
    }
    return;
  }

  if (!ensureEntityPipeline(ctx)) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr,
              "[metalrender] nBeginGuiPass: failed to create pipeline\n");
    }
    return;
  }

  if (!guiDepthState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction = MTLCompareFunctionAlways;
    depthDesc.depthWriteEnabled = NO;
    guiDepthState = [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
    if (!guiDepthState) {
      printf("[MetalRender] Failed to create GUI depth stencil state\n");
      return;
    }
    printf("[MetalRender] Created GUI depth state (depth test disabled)\n");
  }

  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    ctx->lastEntityCommandBuffer = ctx->currentEntityCommandBuffer;
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
  }

  if (ctx->lastTerrainCommandBuffer) {
    [ctx->lastTerrainCommandBuffer waitUntilCompleted];
    ctx->lastTerrainCommandBuffer = nil;
  }
  if (ctx->lastEntityCommandBuffer) {
    [ctx->lastEntityCommandBuffer waitUntilCompleted];
    ctx->lastEntityCommandBuffer = nil;
  }

  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

  passDesc.depthAttachment.texture = nil;

  ctx->currentEntityCommandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!ctx->currentEntityCommandBuffer) {
    fprintf(stderr,
            "[metalrender] nBeginGuiPass: failed to create command buffer\n");
    return;
  }

  ctx->currentEntityEncoder = [ctx->currentEntityCommandBuffer
      renderCommandEncoderWithDescriptor:passDesc];
  if (!ctx->currentEntityEncoder) {
    fprintf(stderr, "[metalrender] nBeginGuiPass: failed to create encoder\n");
    [ctx->currentEntityCommandBuffer commit];
    ctx->currentEntityCommandBuffer = nil;
    return;
  }

  if (ctx->guiPipeline) {
    [ctx->currentEntityEncoder setRenderPipelineState:ctx->guiPipeline];
  } else {

    printf("[metalrender] WARNING: guiPipeline not created, using "
           "entityPipeline\n");
    [ctx->currentEntityEncoder setRenderPipelineState:ctx->entityPipeline];
  }
  [ctx->currentEntityEncoder setDepthStencilState:guiDepthState];
  [ctx->currentEntityEncoder setCullMode:MTLCullModeNone];
  [ctx->currentEntityEncoder setFrontFacingWinding:MTLWindingCounterClockwise];

  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [ctx->currentEntityEncoder setViewport:viewport];

  EntityFrameUniforms frameUniforms;
  memset(&frameUniforms, 0, sizeof(frameUniforms));

  if (viewProj && env->GetArrayLength(viewProj) >= 16) {
    jfloat *vp = env->GetFloatArrayElements(viewProj, nullptr);
    if (vp) {
      memcpy(frameUniforms.viewProj, vp, 16 * sizeof(float));
      env->ReleaseFloatArrayElements(viewProj, vp, JNI_ABORT);
    }
  }

  if (cameraPos && env->GetArrayLength(cameraPos) >= 3) {
    jfloat *cp = env->GetFloatArrayElements(cameraPos, nullptr);
    if (cp) {
      frameUniforms.cameraPos[0] = cp[0];
      frameUniforms.cameraPos[1] = cp[1];
      frameUniforms.cameraPos[2] = cp[2];
      frameUniforms.cameraPos[3] = 0.0f;
      env->ReleaseFloatArrayElements(cameraPos, cp, JNI_ABORT);
    }
  }

  frameUniforms.fogColor[0] = 0.0f;
  frameUniforms.fogColor[1] = 0.0f;
  frameUniforms.fogColor[2] = 0.0f;
  frameUniforms.fogColor[3] = 0.0f;
  frameUniforms.fogParams[0] = 10000.0f;
  frameUniforms.fogParams[1] = 10001.0f;
  frameUniforms.fogParams[2] = 0.0f;
  frameUniforms.fogParams[3] = 0.0f;

  frameUniforms.lightParams[0] = 1.0f;
  frameUniforms.lightParams[1] = 1.0f;
  frameUniforms.lightParams[2] = 0.0f;
  frameUniforms.lightParams[3] = 0.0f;

  [ctx->currentEntityEncoder setVertexBytes:&frameUniforms
                                     length:sizeof(frameUniforms)
                                    atIndex:1];
  [ctx->currentEntityEncoder setFragmentBytes:&frameUniforms
                                       length:sizeof(frameUniforms)
                                      atIndex:1];

  ctx->entityPassActive = true;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEndGuiPass(JNIEnv *, jclass,
                                                           jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];

    id<MTLBlitCommandEncoder> blitEncoder =
        [ctx->currentEntityCommandBuffer blitCommandEncoder];
    [blitEncoder synchronizeResource:ctx->ioSurfaceTexture];
    [blitEncoder endEncoding];

    [ctx->currentEntityCommandBuffer commit];

    ctx->lastGuiCommandBuffer = ctx->currentEntityCommandBuffer;

    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;
  }
}

static const char *kCompositeShaderSource = R"METAL(
#include <metal_stdlib>
using namespace metal;

struct CompositeVertexOut {
    float4 position [[position]];
    float2 texCoord;
};

vertex CompositeVertexOut composite_vertex(uint vid [[vertex_id]],
                                           constant float4 *vertices [[buffer(0)]]) {
    CompositeVertexOut out;
    float4 v = vertices[vid];
    out.position = float4(v.xy, 0.0, 1.0);
    out.texCoord = v.zw;
    return out;
}

fragment float4 composite_fragment(CompositeVertexOut in [[stage_in]],
                                   texture2d<float> terrainTex [[texture(0)]],
                                   texture2d<float> guiTex [[texture(1)]]) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest);

    float4 terrain = terrainTex.sample(texSampler, in.texCoord);
    float4 gui = guiTex.sample(texSampler, in.texCoord);

    float3 resultColor = gui.rgb * gui.a + terrain.rgb * (1.0 - gui.a);
    float resultAlpha = gui.a + terrain.a * (1.0 - gui.a);

    return float4(resultColor, resultAlpha);
}
)METAL";

static bool ensureCompositePipeline(MetalContext *ctx) {
  if (ctx->compositePipeline)
    return true;

  NSError *error = nil;
  MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
  options.fastMathEnabled = YES;
  id<MTLLibrary> compositeLibrary = [ctx->device
      newLibraryWithSource:[NSString
                               stringWithUTF8String:kCompositeShaderSource]
                   options:options
                     error:&error];
  if (!compositeLibrary || error) {
    printf("[MetalRender] Failed to compile composite shader: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  id<MTLFunction> vertexFunc =
      [compositeLibrary newFunctionWithName:@"composite_vertex"];
  id<MTLFunction> fragFunc =
      [compositeLibrary newFunctionWithName:@"composite_fragment"];
  if (!vertexFunc || !fragFunc) {
    printf("[MetalRender] Failed to get composite shader functions\n");
    return false;
  }

  MTLRenderPipelineDescriptor *pipelineDesc =
      [[MTLRenderPipelineDescriptor alloc] init];
  pipelineDesc.vertexFunction = vertexFunc;
  pipelineDesc.fragmentFunction = fragFunc;
  pipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;

  pipelineDesc.colorAttachments[0].blendingEnabled = NO;

  ctx->compositePipeline =
      [ctx->device newRenderPipelineStateWithDescriptor:pipelineDesc
                                                  error:&error];
  if (!ctx->compositePipeline || error) {
    printf("[MetalRender] Failed to create composite pipeline: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  float quadVerts[] = {
      -1.0f, -1.0f, 0.0f, 1.0f, 1.0f,  -1.0f, 1.0f, 1.0f,
      -1.0f, 1.0f,  0.0f, 0.0f, 1.0f,  -1.0f, 1.0f, 1.0f,
      1.0f,  1.0f,  1.0f, 0.0f, -1.0f, 1.0f,  0.0f, 0.0f,
  };
  ctx->compositeQuadVB =
      [ctx->device newBufferWithBytes:quadVerts
                               length:sizeof(quadVerts)
                              options:MTLResourceStorageModeShared];

  printf("[MetalRender] Created composite pipeline and quad buffer\n");
  return true;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nCompositeGui(JNIEnv *, jclass,
                                                             jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue)
    return;

  if (!ctx->guiNeedsComposite || !ctx->guiTexture) {
    return;
  }

  if (!ensureCompositePipeline(ctx)) {
    printf("[MetalRender] nCompositeGui: failed to create pipeline\n");
    return;
  }

  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

  id<MTLCommandBuffer> cb = [ctx->graphicsQueue commandBuffer];
  id<MTLRenderCommandEncoder> enc =
      [cb renderCommandEncoderWithDescriptor:passDesc];

  if (!enc) {
    printf("[MetalRender] nCompositeGui: failed to create encoder\n");
    [cb commit];
    return;
  }

  [enc setRenderPipelineState:ctx->compositePipeline];
  [enc setVertexBuffer:ctx->compositeQuadVB offset:0 atIndex:0];
  [enc setFragmentTexture:ctx->ioSurfaceTexture atIndex:0];
  [enc setFragmentTexture:ctx->guiTexture atIndex:1];

  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [enc setViewport:viewport];

  [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
  [enc endEncoding];

  [cb commit];
  [cb waitUntilCompleted];

  ctx->guiNeedsComposite = false;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nInitMultiICB(
    JNIEnv *, jclass, jlong handle, jint maxChunksPerLOD) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device || !ctx->icbSupported)
    return JNI_FALSE;

  if (ctx->multiICBInitialized)
    return JNI_TRUE;

  uint32_t maxPerLOD = std::max(1, static_cast<int>(maxChunksPerLOD));
  ctx->multiICBMaxPerLOD = maxPerLOD;

  if (@available(macOS 12.0, *)) {
    MTLIndirectCommandBufferDescriptor *icbDesc =
        [[MTLIndirectCommandBufferDescriptor alloc] init];
    icbDesc.commandTypes = MTLIndirectCommandTypeDraw;
    icbDesc.inheritBuffers = NO;
    icbDesc.inheritPipelineState = NO;
    icbDesc.maxVertexBufferBindCount = 3;
    icbDesc.maxFragmentBufferBindCount = 3;

    for (uint32_t i = 0; i < MetalContext::LOD_COUNT; i++) {
      ctx->lodICBs[i] = [ctx->device
          newIndirectCommandBufferWithDescriptor:icbDesc
                                 maxCommandCount:maxPerLOD
                                         options:MTLResourceStorageModeShared];
      if (!ctx->lodICBs[i]) {
        fprintf(stderr, "[MetalRender] Failed to create ICB for LOD %u\n", i);

        for (uint32_t j = 0; j < i; j++) {
          ctx->lodICBs[j] = nil;
        }
        return JNI_FALSE;
      }

      ctx->lodDrawUniformsBuffers[i] =
          [ctx->device newBufferWithLength:maxPerLOD * sizeof(DrawUniforms)
                                   options:MTLResourceStorageModeShared];
      if (!ctx->lodDrawUniformsBuffers[i]) {
        fprintf(
            stderr,
            "[MetalRender] Failed to create draw uniforms buffer for LOD %u\n",
            i);
        return JNI_FALSE;
      }
    }
  } else {
    return JNI_FALSE;
  }

  ctx->lodDrawCountsBuffer = [ctx->device
      newBufferWithLength:MetalContext::LOD_COUNT * sizeof(uint32_t)
                  options:MTLResourceStorageModeShared];
  if (!ctx->lodDrawCountsBuffer) {
    fprintf(stderr, "[MetalRender] Failed to create LOD draw counts buffer\n");
    return JNI_FALSE;
  }

  float defaultThresholds[6] = {32.0f, 64.0f, 128.0f, 256.0f, 512.0f, 4096.0f};
  ctx->lodThresholdsBuffer =
      [ctx->device newBufferWithBytes:defaultThresholds
                               length:6 * sizeof(float)
                              options:MTLResourceStorageModeShared];

  if (ctx->terrainLibrary) {
    id<MTLFunction> lodSelectFunc =
        [ctx->terrainLibrary newFunctionWithName:@"cull_and_encode_multi_lod"];
    if (lodSelectFunc) {
      NSError *error = nil;
      ctx->multiLodCullPipeline =
          [ctx->device newComputePipelineStateWithFunction:lodSelectFunc
                                                     error:&error];
      if (!ctx->multiLodCullPipeline) {
        fprintf(stderr,
                "[MetalRender] Failed to create multi-LOD cull pipeline: %s\n",
                error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
  }

  ctx->multiICBInitialized = true;
  printf("[MetalRender] Multi-ICB initialized: %u LOD tracks, %u max commands "
         "each\n",
         MetalContext::LOD_COUNT, maxPerLOD);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyMultiICB(JNIEnv *,
                                                                jclass,
                                                                jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  for (uint32_t i = 0; i < MetalContext::LOD_COUNT; i++) {
    ctx->lodICBs[i] = nil;
    ctx->lodDrawUniformsBuffers[i] = nil;
    ctx->lodTerrainPipelines[i] = nil;
  }
  ctx->lodDrawCountsBuffer = nil;
  ctx->lodThresholdsBuffer = nil;
  ctx->lodSelectPipeline = nil;
  ctx->multiLodCullPipeline = nil;
  ctx->multiICBInitialized = false;
  printf("[MetalRender] Multi-ICB destroyed\n");
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDispatchGpuLodSelect(
    JNIEnv *env, jclass, jlong handle, jfloatArray cameraPos,
    jfloatArray frustumPlanes, jint totalChunks) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->multiICBInitialized || !ctx->computeQueue)
    return;

  if (ctx->lodDrawCountsBuffer) {
    memset(ctx->lodDrawCountsBuffer.contents, 0,
           MetalContext::LOD_COUNT * sizeof(uint32_t));
  }

  float camPos[3] = {ctx->cameraX, ctx->cameraY, ctx->cameraZ};
  if (cameraPos && env->GetArrayLength(cameraPos) >= 3) {
    jfloat *cp = env->GetFloatArrayElements(cameraPos, nullptr);
    if (cp) {
      camPos[0] = cp[0];
      camPos[1] = cp[1];
      camPos[2] = cp[2];
      env->ReleaseFloatArrayElements(cameraPos, cp, JNI_ABORT);
    }
  }

  float *thresholds = static_cast<float *>(ctx->lodThresholdsBuffer.contents);

  uint32_t lodCounts[MetalContext::LOD_COUNT] = {};

  for (uint32_t i = 0;
       i < ctx->currentIndirectCount && i < ctx->drawCommands.size(); i++) {
    const DrawCommandData &cmd = ctx->drawCommands[i];
    if (cmd.vertexCount == 0)
      continue;

    float dx = cmd.originX - camPos[0];
    float dy = cmd.originY - camPos[1];
    float dz = cmd.originZ - camPos[2];
    float dist = sqrtf(dx * dx + dy * dy + dz * dz);

    uint32_t lodLevel = 5;
    for (uint32_t lod = 0; lod < MetalContext::LOD_COUNT; lod++) {
      if (dist < thresholds[lod]) {
        lodLevel = lod;
        break;
      }
    }

    uint32_t idx = lodCounts[lodLevel];
    if (idx < ctx->multiICBMaxPerLOD && ctx->lodDrawUniformsBuffers[lodLevel]) {
      DrawUniforms *uniforms = static_cast<DrawUniforms *>(
          ctx->lodDrawUniformsBuffers[lodLevel].contents);
      uniforms[idx].originX = cmd.originX;
      uniforms[idx].originY = cmd.originY;
      uniforms[idx].originZ = cmd.originZ;
      uniforms[idx].renderLayer = static_cast<float>(cmd.renderLayer);
      lodCounts[lodLevel]++;
    }
  }

  uint32_t *counts = static_cast<uint32_t *>(ctx->lodDrawCountsBuffer.contents);
  for (uint32_t i = 0; i < MetalContext::LOD_COUNT; i++) {
    counts[i] = lodCounts[i];
    ctx->lodDrawCounts[i] = static_cast<int>(lodCounts[i]);
  }
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nExecuteMultiICB(JNIEnv *,
                                                                jclass,
                                                                jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->multiICBInitialized || !ctx->graphicsQueue)
    return 0;
  if (!ctx->ioSurfaceTexture || !ctx->depthTexture)
    return 0;
  if (!ensureTerrainPipeline(ctx))
    return 0;

  int totalDraws = 0;

  id<MTLCommandBuffer> commandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!commandBuffer)
    return 0;

  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
  passDesc.colorAttachments[0].clearColor =
      MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction = MTLLoadActionClear;
  passDesc.depthAttachment.storeAction = MTLStoreActionStore;
  passDesc.depthAttachment.clearDepth = 1.0;

  id<MTLRenderCommandEncoder> encoder =
      [commandBuffer renderCommandEncoderWithDescriptor:passDesc];
  if (!encoder) {
    [commandBuffer commit];
    return 0;
  }

  id<MTLRenderPipelineState> pipeline = ctx->terrainPipeline;

  [encoder setRenderPipelineState:pipeline];
  [encoder setDepthStencilState:ctx->depthState];
  [encoder setCullMode:MTLCullModeNone];
  [encoder setFrontFacingWinding:MTLWindingCounterClockwise];

  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [encoder setViewport:viewport];

  FrameUniforms frameUniforms;
  memcpy(frameUniforms.viewProj, ctx->viewProj, sizeof(ctx->viewProj));
  frameUniforms.cameraX = ctx->cameraX;
  frameUniforms.cameraY = ctx->cameraY;
  frameUniforms.cameraZ = ctx->cameraZ;
  frameUniforms.cameraPad = 0;
  frameUniforms.fogR = ctx->fogR;
  frameUniforms.fogG = ctx->fogG;
  frameUniforms.fogB = ctx->fogB;
  frameUniforms.fogA = ctx->fogA;
  frameUniforms.fogStart = ctx->fogStart;
  frameUniforms.fogEnd = ctx->fogEnd;
  frameUniforms.fogPad1 = 0;
  frameUniforms.fogPad2 = 0;
  frameUniforms.texShrinkU = ctx->texShrinkU;
  frameUniforms.texShrinkV = ctx->texShrinkV;
  frameUniforms.texShrinkPad1 = 0;
  frameUniforms.texShrinkPad2 = 0;
  frameUniforms.lightParams[0] = ctx->dayBrightness;
  frameUniforms.lightParams[1] = ctx->ambientLight;
  frameUniforms.lightParams[2] = ctx->skyAngle;
  frameUniforms.lightParams[3] = 0.0f;

  [encoder setVertexBytes:&frameUniforms
                   length:sizeof(frameUniforms)
                  atIndex:2];
  [encoder setFragmentBytes:&frameUniforms
                     length:sizeof(frameUniforms)
                    atIndex:2];

  if (ctx->atlasTexture) {
    [encoder setFragmentTexture:ctx->atlasTexture atIndex:0];
  }

  [encoder setVertexBuffer:ctx->persistentBuffer offset:0 atIndex:0];

  for (uint32_t lod = 0; lod < MetalContext::LOD_COUNT; lod++) {
    uint32_t drawCount = static_cast<uint32_t>(ctx->lodDrawCounts[lod]);
    if (drawCount == 0)
      continue;

    if (ctx->lodTerrainPipelines[lod]) {
      [encoder setRenderPipelineState:ctx->lodTerrainPipelines[lod]];
    }

    if (ctx->lodDrawUniformsBuffers[lod]) {
      [encoder setVertexBuffer:ctx->lodDrawUniformsBuffers[lod]
                        offset:0
                       atIndex:1];
      [encoder setFragmentBuffer:ctx->lodDrawUniformsBuffers[lod]
                          offset:0
                         atIndex:0];
    }

    int32_t lastRenderLayer = -1;
    for (uint32_t i = 0; i < drawCount; i++) {

      size_t uniformOffset = i * sizeof(DrawUniforms);
      [encoder setVertexBufferOffset:uniformOffset atIndex:1];
      [encoder setFragmentBufferOffset:uniformOffset atIndex:0];

      DrawUniforms *uniforms = static_cast<DrawUniforms *>(
          ctx->lodDrawUniformsBuffers[lod].contents);
      float ox = uniforms[i].originX;
      float oy = uniforms[i].originY;
      float oz = uniforms[i].originZ;
      int32_t renderLayer = static_cast<int32_t>(uniforms[i].renderLayer);

      if (renderLayer != lastRenderLayer) {
        if (renderLayer == 1 || renderLayer == 2) {
          [encoder setDepthBias:-1.0f slopeScale:-1.0f clamp:-0.01f];
        } else {
          [encoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
        }
        lastRenderLayer = renderLayer;
      }

      for (uint32_t j = 0;
           j < ctx->currentIndirectCount && j < ctx->drawCommands.size(); j++) {
        const DrawCommandData &cmd = ctx->drawCommands[j];
        if (cmd.originX == ox && cmd.originY == oy && cmd.originZ == oz &&
            static_cast<int32_t>(cmd.renderLayer) == renderLayer &&
            cmd.vertexCount > 0) {
          [encoder setVertexBufferOffset:cmd.bufferOffset atIndex:0];
          [encoder drawPrimitives:MTLPrimitiveTypeTriangle
                      vertexStart:0
                      vertexCount:cmd.vertexCount];
          totalDraws++;
          break;
        }
      }
    }
  }

  [encoder endEncoding];

  id<MTLBlitCommandEncoder> blitEncoder = [commandBuffer blitCommandEncoder];
  [blitEncoder synchronizeResource:ctx->ioSurfaceTexture];
  [blitEncoder endEncoding];

  [commandBuffer commit];
  ctx->lastTerrainCommandBuffer = commandBuffer;

  return static_cast<jint>(totalDraws);
}

JNIEXPORT jintArray JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetLodDrawCounts(JNIEnv *env,
                                                                 jclass,
                                                                 jlong handle) {
  MetalContext *ctx = getContext(handle);
  jintArray result = env->NewIntArray(MetalContext::LOD_COUNT);
  if (!ctx)
    return result;

  jint counts[MetalContext::LOD_COUNT];
  for (uint32_t i = 0; i < MetalContext::LOD_COUNT; i++) {
    counts[i] = ctx->lodDrawCounts[i];
  }
  env->SetIntArrayRegion(result, 0, MetalContext::LOD_COUNT, counts);
  return result;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetLodThresholds(
    JNIEnv *env, jclass, jlong handle, jfloatArray thresholds) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !thresholds)
    return;

  jint len = env->GetArrayLength(thresholds);
  if (len < static_cast<jint>(MetalContext::LOD_COUNT))
    return;

  jfloat *data = env->GetFloatArrayElements(thresholds, nullptr);
  if (!data)
    return;

  if (ctx->lodThresholdsBuffer) {
    float *buf = static_cast<float *>(ctx->lodThresholdsBuffer.contents);
    for (uint32_t i = 0; i < MetalContext::LOD_COUNT; i++) {
      buf[i] = data[i];
    }
  }
  env->ReleaseFloatArrayElements(thresholds, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetLodFunctionConstants(
    JNIEnv *, jclass, jlong handle, jint lodLevel, jboolean enableBlockLight,
    jboolean enableTextureSample, jboolean enableFog) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device || !ctx->terrainLibrary)
    return;
  if (lodLevel < 0 || lodLevel >= static_cast<jint>(MetalContext::LOD_COUNT))
    return;

  MTLFunctionConstantValues *constants =
      [[MTLFunctionConstantValues alloc] init];

  uint32_t lodVal = static_cast<uint32_t>(lodLevel);
  bool blockLight = (enableBlockLight == JNI_TRUE);
  bool texSample = (enableTextureSample == JNI_TRUE);
  bool fog = (enableFog == JNI_TRUE);

  [constants setConstantValue:&lodVal type:MTLDataTypeUInt atIndex:0];
  [constants setConstantValue:&blockLight type:MTLDataTypeBool atIndex:1];
  [constants setConstantValue:&texSample type:MTLDataTypeBool atIndex:2];
  [constants setConstantValue:&fog type:MTLDataTypeBool atIndex:3];

  NSError *error = nil;
  id<MTLFunction> vertexFunc =
      [ctx->terrainLibrary newFunctionWithName:@"terrain_color_vertex"
                                constantValues:constants
                                         error:&error];
  if (!vertexFunc) {
    fprintf(stderr,
            "[MetalRender] Failed to create LOD %d vertex function: %s\n",
            lodLevel,
            error ? [[error localizedDescription] UTF8String] : "unknown");
    return;
  }

  id<MTLFunction> fragmentFunc =
      [ctx->terrainLibrary newFunctionWithName:@"terrain_color_fragment"
                                constantValues:constants
                                         error:&error];
  if (!fragmentFunc) {
    fprintf(stderr,
            "[MetalRender] Failed to create LOD %d fragment function: %s\n",
            lodLevel,
            error ? [[error localizedDescription] UTF8String] : "unknown");
    return;
  }

  MTLRenderPipelineDescriptor *desc =
      [[MTLRenderPipelineDescriptor alloc] init];
  desc.vertexFunction = vertexFunc;
  desc.fragmentFunction = fragmentFunc;
  desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  desc.colorAttachments[0].blendingEnabled = YES;
  desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
  desc.colorAttachments[0].destinationRGBBlendFactor =
      MTLBlendFactorOneMinusSourceAlpha;
  desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
  desc.colorAttachments[0].destinationAlphaBlendFactor =
      MTLBlendFactorOneMinusSourceAlpha;
  desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
  desc.label = [NSString stringWithFormat:@"TerrainLOD%dPipeline", lodLevel];

  ctx->lodTerrainPipelines[lodLevel] =
      [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
  if (!ctx->lodTerrainPipelines[lodLevel]) {
    fprintf(stderr, "[MetalRender] Failed to create LOD %d pipeline: %s\n",
            lodLevel,
            error ? [[error localizedDescription] UTF8String] : "unknown");
  } else {
    printf("[MetalRender] Created LOD %d pipeline (blockLight=%d, tex=%d, "
           "fog=%d)\n",
           lodLevel, blockLight, texSample, fog);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetTripleBuffering(
    JNIEnv *, jclass, jlong handle, jboolean enabled) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  bool enable = (enabled == JNI_TRUE);
  if (enable == ctx->tripleBufferingEnabled)
    return;

  if (enable && !ctx->frameSemaphore) {
    ctx->frameSemaphore = dispatch_semaphore_create(3);
    ctx->currentFrameIndex = 0;
    printf("[MetalRender] Triple buffering enabled (semaphore count=3)\n");
  } else if (!enable && ctx->frameSemaphore) {

    for (int i = 0; i < 3; i++) {
      dispatch_semaphore_signal(ctx->frameSemaphore);
    }
    ctx->frameSemaphore = nullptr;
    ctx->currentFrameIndex = 0;
    printf("[MetalRender] Triple buffering disabled\n");
  }
  ctx->tripleBufferingEnabled = enable;
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetCurrentFrameIndex(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return 0;

  if (ctx->tripleBufferingEnabled && ctx->frameSemaphore) {

    dispatch_semaphore_wait(ctx->frameSemaphore, DISPATCH_TIME_FOREVER);
    uint32_t idx = ctx->currentFrameIndex;
    ctx->currentFrameIndex = (ctx->currentFrameIndex + 1) % 3;
    return static_cast<jint>(idx);
  }
  return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nInitMetalFXSpatial(
    JNIEnv *, jclass, jlong handle, jint renderWidth, jint renderHeight,
    jint outputWidth, jint outputHeight) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device)
    return JNI_FALSE;

#if METALRENDER_HAS_METALFX
  if (!ctx->metalFxSupported)
    return JNI_FALSE;

  ctx->metalFxEnabled = true;
  float scale =
      static_cast<float>(renderWidth) / static_cast<float>(outputWidth);
  bool ok = ensureMetalFXResources(ctx, static_cast<uint32_t>(outputWidth),
                                   static_cast<uint32_t>(outputHeight), scale);
  if (ok) {
    printf("[MetalRender] MetalFX Spatial initialized: render=%dx%d "
           "output=%dx%d\n",
           renderWidth, renderHeight, outputWidth, outputHeight);
  }
  return ok ? JNI_TRUE : JNI_FALSE;
#else
  (void)renderWidth;
  (void)renderHeight;
  (void)outputWidth;
  (void)outputHeight;
  return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nApplyMetalFXSpatial(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue)
    return;

#if METALRENDER_HAS_METALFX
  if (!ctx->metalFxScaler || !ctx->metalFxColor || !ctx->metalFxOutput)
    return;

  id<MTLCommandBuffer> cb = [ctx->graphicsQueue commandBuffer];
  if (!cb)
    return;

  id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
  if (blit && ctx->ioSurfaceTexture && ctx->metalFxColor) {

    [blit copyFromTexture:ctx->ioSurfaceTexture toTexture:ctx->metalFxColor];
    [blit endEncoding];
  }

  ctx->metalFxScaler.colorTexture = ctx->metalFxColor;
  ctx->metalFxScaler.outputTexture = ctx->metalFxOutput;
  [ctx->metalFxScaler encodeToCommandBuffer:cb];

  id<MTLBlitCommandEncoder> blit2 = [cb blitCommandEncoder];
  if (blit2 && ctx->metalFxOutput) {
    [blit2 copyFromTexture:ctx->metalFxOutput toTexture:ctx->ioSurfaceTexture];
    [blit2 synchronizeResource:ctx->ioSurfaceTexture];
    [blit2 endEncoding];
  }

  [cb commit];
  [cb waitUntilCompleted];
#endif
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyMetalFXSpatial(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

#if METALRENDER_HAS_METALFX
  destroyMetalFXResources(ctx);
  ctx->metalFxEnabled = false;
  printf("[MetalRender] MetalFX Spatial destroyed\n");
#endif
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nCreateArgumentBuffer(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device)
    return 0;

  static const uint32_t MAX_SLOTS = 32;
  size_t bufferSize = MAX_SLOTS * 16;

  id<MTLBuffer> argBuffer =
      [ctx->device newBufferWithLength:bufferSize
                               options:MTLResourceStorageModeShared];
  if (!argBuffer)
    return 0;

  memset(argBuffer.contents, 0, bufferSize);

  uint64_t bufHandle =
      reinterpret_cast<uint64_t>((__bridge_retained void *)argBuffer);
  ctx->argumentBuffers[bufHandle] = argBuffer;

  printf("[MetalRender] Created argument buffer: %llu (%zu bytes)\n", bufHandle,
         bufferSize);
  return static_cast<jlong>(bufHandle);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nUpdateArgumentBuffer(
    JNIEnv *, jclass, jlong handle, jlong argBufferHandle, jint index,
    jlong textureHandle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  auto it = ctx->argumentBuffers.find(static_cast<uint64_t>(argBufferHandle));
  if (it == ctx->argumentBuffers.end())
    return;

  if (index >= 0 && index < 32) {
    uint64_t *slots = static_cast<uint64_t *>(it->second.contents);
    slots[index * 2] = static_cast<uint64_t>(textureHandle);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyArgumentBuffer(
    JNIEnv *, jclass, jlong handle, jlong argBufferHandle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  auto it = ctx->argumentBuffers.find(static_cast<uint64_t>(argBufferHandle));
  if (it != ctx->argumentBuffers.end()) {
    ctx->argumentBuffers.erase(it);

    id<MTLBuffer> buf =
        (__bridge_transfer id<MTLBuffer>)(void *)static_cast<uintptr_t>(
            argBufferHandle);
    buf = nil;
  }
}
}

extern "C" {

__attribute__((visibility("default"))) void
mr_beginFrame(jlong handle, const float *viewProj, const float *cameraPos,
              float fogStart, float fogEnd) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->persistentBuffer ||
      !ctx->indirectArgs)
    return;
  if (viewProj) {
    memcpy(ctx->viewProj, viewProj, 16 * sizeof(float));
    ctx->hasViewProj = true;
  }
  if (cameraPos) {
    ctx->cameraX = cameraPos[0];
    ctx->cameraY = cameraPos[1];
    ctx->cameraZ = cameraPos[2];
  }
  ctx->fogStart = fogStart;
  ctx->fogEnd = fogEnd;
}

__attribute__((visibility("default"))) void mr_drawTerrain(jlong handle,
                                                           jint pass) {
  JNIEnv *env = nullptr;
  JavaVM *vm = nullptr;
  if (vm == nullptr) {
  }
  Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain(nullptr, nullptr,
                                                              handle, pass);
}

__attribute__((visibility("default"))) void
mr_clearIndirectCommands(jlong handle) {
  Java_com_metalrender_nativebridge_NativeBridge_nClearIndirectCommands(
      nullptr, nullptr, handle);
}

__attribute__((visibility("default"))) void
mr_batchDrawCommands(jlong handle, const void *data, int commandCount) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || commandCount <= 0 || !data)
    return;

  uint32_t count = static_cast<uint32_t>(commandCount);
  if (count > ctx->maxIndirectCommands)
    count = ctx->maxIndirectCommands;

  ctx->drawCommands.resize(count);
  ctx->currentIndirectCount = count;

  static const size_t ENTRY_SIZE = 32;
  const uint8_t *src = static_cast<const uint8_t *>(data);
  for (uint32_t i = 0; i < count; i++) {
    const uint8_t *entry = src + i * ENTRY_SIZE;
    DrawCommandData &cmd = ctx->drawCommands[i];
    uint32_t offset, vertexCount, renderLayer, lodLevel;
    float ox, oy, oz;
    memcpy(&offset, entry + 0, 4);
    memcpy(&vertexCount, entry + 4, 4);
    memcpy(&ox, entry + 8, 4);
    memcpy(&oy, entry + 12, 4);
    memcpy(&oz, entry + 16, 4);
    memcpy(&renderLayer, entry + 20, 4);
    memcpy(&lodLevel, entry + 28, 4);
    cmd.bufferOffset = offset;
    cmd.vertexCount = vertexCount;
    cmd.originX = ox;
    cmd.originY = oy;
    cmd.originZ = oz;
    cmd.renderLayer = renderLayer;
    cmd.lodLevel = lodLevel;
  }
}

__attribute__((visibility("default"))) int mr_executeIndirect(jlong handle,
                                                              jint pass) {
  return (int)Java_com_metalrender_nativebridge_NativeBridge_nExecuteIndirect(
      nullptr, nullptr, handle, pass);
}

__attribute__((visibility("default"))) void
mr_setTemporalJitter(jlong handle, float jitterX, float jitterY,
                     float blendFactor) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->temporalJitterX = jitterX;
  ctx->temporalJitterY = jitterY;
  ctx->temporalBlend = blendFactor;
}

__attribute__((visibility("default"))) void
mr_setLightParams(jlong handle, float dayBrightness, float ambientLight,
                  float skyAngle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->dayBrightness = dayBrightness;
  ctx->ambientLight = ambientLight;
  ctx->skyAngle = skyAngle;
}

__attribute__((visibility("default"))) void
mr_beginEntityPass(jlong handle, const float *viewProj,
                   const float *cameraPos) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->ioSurfaceTexture ||
      !ctx->depthTexture)
    return;
  if (!ensureEntityPipeline(ctx))
    return;
  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    ctx->lastEntityCommandBuffer = ctx->currentEntityCommandBuffer;
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
  }
  if (ctx->lastTerrainCommandBuffer) {
    [ctx->lastTerrainCommandBuffer waitUntilCompleted];
    ctx->lastTerrainCommandBuffer = nil;
  }
  if (ctx->lastEntityCommandBuffer) {
    [ctx->lastEntityCommandBuffer waitUntilCompleted];
    ctx->lastEntityCommandBuffer = nil;
  }
  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction = MTLLoadActionLoad;
  passDesc.depthAttachment.storeAction = MTLStoreActionStore;
  ctx->currentEntityCommandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!ctx->currentEntityCommandBuffer)
    return;
  ctx->currentEntityEncoder = [ctx->currentEntityCommandBuffer
      renderCommandEncoderWithDescriptor:passDesc];
  if (!ctx->currentEntityEncoder) {
    [ctx->currentEntityCommandBuffer commit];
    ctx->currentEntityCommandBuffer = nil;
    return;
  }
  [ctx->currentEntityEncoder setRenderPipelineState:ctx->entityPipeline];
  [ctx->currentEntityEncoder setDepthStencilState:ctx->entityDepthState];
  [ctx->currentEntityEncoder setCullMode:MTLCullModeNone];
  [ctx->currentEntityEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [ctx->currentEntityEncoder setViewport:viewport];
  EntityFrameUniforms frameUniforms;
  memset(&frameUniforms, 0, sizeof(frameUniforms));
  if (viewProj)
    memcpy(frameUniforms.viewProj, viewProj, 16 * sizeof(float));
  if (cameraPos) {
    frameUniforms.cameraPos[0] = cameraPos[0];
    frameUniforms.cameraPos[1] = cameraPos[1];
    frameUniforms.cameraPos[2] = cameraPos[2];
    frameUniforms.cameraPos[3] = 0.0f;
  }
  frameUniforms.fogColor[0] = ctx->fogR;
  frameUniforms.fogColor[1] = ctx->fogG;
  frameUniforms.fogColor[2] = ctx->fogB;
  frameUniforms.fogColor[3] = ctx->fogA;
  frameUniforms.fogParams[0] = ctx->fogStart;
  frameUniforms.fogParams[1] = ctx->fogEnd;
  frameUniforms.fogParams[2] = 0.0f;
  frameUniforms.fogParams[3] = 0.0f;
  frameUniforms.lightParams[0] = ctx->dayBrightness;
  frameUniforms.lightParams[1] = ctx->ambientLight;
  frameUniforms.lightParams[2] = ctx->skyAngle;
  frameUniforms.lightParams[3] = 0.0f;
  [ctx->currentEntityEncoder setVertexBytes:&frameUniforms
                                     length:sizeof(frameUniforms)
                                    atIndex:1];
  [ctx->currentEntityEncoder setFragmentBytes:&frameUniforms
                                       length:sizeof(frameUniforms)
                                      atIndex:1];
  ctx->entityPassActive = true;
}

__attribute__((visibility("default"))) void
mr_drawEntity(jlong handle, const void *vertexData, int vertexCount,
              jlong textureHandle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->entityPassActive || !ctx->currentEntityEncoder)
    return;
  if (vertexCount <= 0 || !vertexData)
    return;
  size_t vertexDataSize = static_cast<size_t>(vertexCount) * 32;
  id<MTLBuffer> vertexBuffer =
      [ctx->device newBufferWithBytes:vertexData
                               length:vertexDataSize
                              options:MTLResourceStorageModeShared];
  if (!vertexBuffer)
    return;
  [ctx->currentEntityEncoder setVertexBuffer:vertexBuffer offset:0 atIndex:0];
  id<MTLTexture> textureToUse = nil;
  if (textureHandle != 0) {
    auto it = ctx->entityTextures.find(static_cast<uint64_t>(textureHandle));
    if (it != ctx->entityTextures.end() && it->second)
      textureToUse = it->second;
  }
  if (!textureToUse && ctx->whiteTexture)
    textureToUse = ctx->whiteTexture;
  if (textureToUse)
    [ctx->currentEntityEncoder setFragmentTexture:textureToUse atIndex:0];
  [ctx->currentEntityEncoder
      drawPrimitives:MTLPrimitiveTypeTriangle
         vertexStart:0
         vertexCount:static_cast<NSUInteger>(vertexCount)];
}

__attribute__((visibility("default"))) void mr_endEntityPass(jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    ctx->lastEntityCommandBuffer = ctx->currentEntityCommandBuffer;
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;
  }
}

__attribute__((visibility("default"))) void
mr_batchDrawEntities(jlong handle, const void *vertexData, int totalVertexBytes,
                     const void *cmdData, int cmdCount) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->entityPassActive || !ctx->currentEntityEncoder)
    return;
  if (cmdCount <= 0 || !vertexData || !cmdData || totalVertexBytes <= 0)
    return;

  id<MTLBuffer> vertexBuffer =
      [ctx->device newBufferWithBytes:vertexData
                               length:static_cast<NSUInteger>(totalVertexBytes)
                              options:MTLResourceStorageModeShared];
  if (!vertexBuffer)
    return;

  struct EntityCmd {
    int32_t vertexOffset;
    int32_t vertexCount;
    int64_t textureHandle;
    int32_t pad0;
    int32_t pad1;
  };
  const EntityCmd *cmds = reinterpret_cast<const EntityCmd *>(cmdData);

  id<MTLTexture> lastTexture = nil;
  int64_t lastTextureHandle = -1;

  for (int i = 0; i < cmdCount; i++) {
    const EntityCmd &cmd = cmds[i];
    if (cmd.vertexCount <= 0)
      continue;

    NSUInteger byteOffset = static_cast<NSUInteger>(cmd.vertexOffset);
    [ctx->currentEntityEncoder setVertexBuffer:vertexBuffer
                                        offset:byteOffset
                                       atIndex:0];

    if (cmd.textureHandle != lastTextureHandle) {
      lastTextureHandle = cmd.textureHandle;
      lastTexture = nil;
      if (cmd.textureHandle != 0) {
        auto it =
            ctx->entityTextures.find(static_cast<uint64_t>(cmd.textureHandle));
        if (it != ctx->entityTextures.end() && it->second)
          lastTexture = it->second;
      }
      if (!lastTexture && ctx->whiteTexture)
        lastTexture = ctx->whiteTexture;
      if (lastTexture)
        [ctx->currentEntityEncoder setFragmentTexture:lastTexture atIndex:0];
    }

    [ctx->currentEntityEncoder
        drawPrimitives:MTLPrimitiveTypeTriangle
           vertexStart:0
           vertexCount:static_cast<NSUInteger>(cmd.vertexCount)];
  }
}
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nInitComputeMesher(
    JNIEnv *, jclass, jlong handle, jint maxVertsPerSection) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device || !ctx->terrainLibrary)
    return JNI_FALSE;
  if (ctx->computeMesherInitialized)
    return JNI_TRUE;

  NSError *error = nil;
  id<MTLFunction> countFunc =
      [ctx->terrainLibrary newFunctionWithName:@"lod_compute_count_faces"];
  id<MTLFunction> emitFunc =
      [ctx->terrainLibrary newFunctionWithName:@"lod_compute_emit_faces"];
  id<MTLFunction> clearFunc =
      [ctx->terrainLibrary newFunctionWithName:@"lod_compute_clear_counters"];

  if (!countFunc || !emitFunc || !clearFunc)
    return JNI_FALSE;

  ctx->computeMesherCountPipeline =
      [ctx->device newComputePipelineStateWithFunction:countFunc error:&error];
  if (!ctx->computeMesherCountPipeline)
    return JNI_FALSE;

  ctx->computeMesherEmitPipeline =
      [ctx->device newComputePipelineStateWithFunction:emitFunc error:&error];
  if (!ctx->computeMesherEmitPipeline)
    return JNI_FALSE;

  ctx->computeMesherClearPipeline =
      [ctx->device newComputePipelineStateWithFunction:clearFunc error:&error];
  if (!ctx->computeMesherClearPipeline)
    return JNI_FALSE;

  uint32_t maxVerts =
      static_cast<uint32_t>(std::max(1, static_cast<int>(maxVertsPerSection)));
  ctx->computeMesherMaxVerts = maxVerts;

  ctx->computeMesherCountersBuffer =
      [ctx->device newBufferWithLength:sizeof(uint32_t) * 2
                               options:MTLResourceStorageModeShared];
  ctx->computeMesherFaceMaskBuffer =
      [ctx->device newBufferWithLength:4096 * sizeof(uint32_t)
                               options:MTLResourceStorageModeShared];

  ctx->computeMesherInitialized = true;
  return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDispatchComputeMesh(
    JNIEnv *env, jclass, jlong handle, jobject stagingBuffer, jint outputOffset,
    jint sectionX, jint sectionY, jint sectionZ, jint maxVerts) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->computeMesherInitialized || !ctx->computeQueue)
    return -1;

  void *stagingData = env->GetDirectBufferAddress(stagingBuffer);
  if (!stagingData)
    return -1;

  uint32_t blockCount = 4096;
  size_t blockDataSize = blockCount * sizeof(uint32_t);
  size_t lightDataSize = blockCount;

  id<MTLBuffer> blockBuffer =
      [ctx->device newBufferWithBytes:stagingData
                               length:blockDataSize
                              options:MTLResourceStorageModeShared];
  id<MTLBuffer> lightBuffer = [ctx->device
      newBufferWithBytes:static_cast<uint8_t *>(stagingData) + blockDataSize
                  length:lightDataSize
                 options:MTLResourceStorageModeShared];
  if (!blockBuffer || !lightBuffer)
    return -1;

  memset(ctx->computeMesherCountersBuffer.contents, 0, sizeof(uint32_t) * 2);
  memset(ctx->computeMesherFaceMaskBuffer.contents, 0, 4096 * sizeof(uint32_t));

  id<MTLCommandBuffer> cb = [ctx->computeQueue commandBuffer];
  if (!cb)
    return -1;

  id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
  if (!enc) {
    [cb commit];
    return -1;
  }

  [enc setComputePipelineState:ctx->computeMesherClearPipeline];
  [enc setBuffer:ctx->computeMesherCountersBuffer offset:0 atIndex:0];
  [enc dispatchThreads:MTLSizeMake(1, 1, 1)
      threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];

  [enc setComputePipelineState:ctx->computeMesherCountPipeline];
  [enc setBuffer:blockBuffer offset:0 atIndex:0];
  [enc setBuffer:ctx->computeMesherCountersBuffer offset:0 atIndex:1];
  [enc setBuffer:ctx->computeMesherFaceMaskBuffer offset:0 atIndex:2];
  [enc setBytes:&blockCount length:sizeof(uint32_t) atIndex:3];
  NSUInteger threadWidth = ctx->computeMesherCountPipeline.threadExecutionWidth;
  [enc dispatchThreads:MTLSizeMake(blockCount, 1, 1)
      threadsPerThreadgroup:MTLSizeMake(threadWidth, 1, 1)];

  memset(ctx->computeMesherCountersBuffer.contents, 0, sizeof(uint32_t) * 2);

  struct MeshUniforms {
    float originX;
    float originY;
    float originZ;
    uint32_t sectionIndex;
    uint32_t outputBaseVertex;
    uint32_t maxOutputVerts;
  };

  MeshUniforms uniforms;
  uniforms.originX = static_cast<float>(sectionX) * 16.0f;
  uniforms.originY = static_cast<float>(sectionY) * 16.0f;
  uniforms.originZ = static_cast<float>(sectionZ) * 16.0f;
  uniforms.sectionIndex = 0;
  uniforms.outputBaseVertex = 0;
  uniforms.maxOutputVerts = static_cast<uint32_t>(maxVerts);

  [enc setComputePipelineState:ctx->computeMesherEmitPipeline];
  [enc setBuffer:blockBuffer offset:0 atIndex:0];
  [enc setBuffer:lightBuffer offset:0 atIndex:1];
  [enc setBuffer:ctx->computeMesherFaceMaskBuffer offset:0 atIndex:2];

  size_t outputSize = static_cast<size_t>(maxVerts) * 8;
  id<MTLBuffer> outputBuffer =
      [ctx->device newBufferWithLength:outputSize
                               options:MTLResourceStorageModeShared];
  [enc setBuffer:outputBuffer offset:0 atIndex:3];
  [enc setBuffer:ctx->computeMesherCountersBuffer offset:0 atIndex:4];
  [enc setBytes:&uniforms length:sizeof(MeshUniforms) atIndex:5];
  [enc setBytes:&blockCount length:sizeof(uint32_t) atIndex:6];

  threadWidth = ctx->computeMesherEmitPipeline.threadExecutionWidth;
  [enc dispatchThreads:MTLSizeMake(blockCount, 1, 1)
      threadsPerThreadgroup:MTLSizeMake(threadWidth, 1, 1)];

  [enc endEncoding];
  [cb commit];
  [cb waitUntilCompleted];

  uint32_t *counters =
      static_cast<uint32_t *>(ctx->computeMesherCountersBuffer.contents);
  uint32_t vertexCount = counters[0];

  if (vertexCount > 0 && ctx->persistentBuffer &&
      static_cast<size_t>(outputOffset) + vertexCount * 8 <=
          ctx->persistentCapacity) {
    uint8_t *dst =
        static_cast<uint8_t *>(ctx->persistentBuffer.contents) + outputOffset;
    memcpy(dst, outputBuffer.contents, vertexCount * 8);
  }

  return static_cast<jint>(vertexCount);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyComputeMesher(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->computeMesherCountPipeline = nil;
  ctx->computeMesherEmitPipeline = nil;
  ctx->computeMesherClearPipeline = nil;
  ctx->computeMesherCountersBuffer = nil;
  ctx->computeMesherFaceMaskBuffer = nil;
  ctx->computeMesherInitialized = false;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetThreadQoS(JNIEnv *, jclass,
                                                             jint qosClass) {
  qos_class_t qos;
  switch (qosClass) {
  case 0x21:
    qos = QOS_CLASS_USER_INTERACTIVE;
    break;
  case 0x19:
    qos = QOS_CLASS_USER_INITIATED;
    break;
  case 0x11:
    qos = QOS_CLASS_UTILITY;
    break;
  case 0x09:
    qos = QOS_CLASS_BACKGROUND;
    break;
  default:
    qos = QOS_CLASS_DEFAULT;
    break;
  }
  pthread_set_qos_class_self_np(qos, 0);
}
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginFrame_1jni(
    JNIEnv *env, jclass cls, jlong handle, jfloatArray viewProj,
    jfloatArray cameraPos, jfloat fogStart, jfloat fogEnd) {
  Java_com_metalrender_nativebridge_NativeBridge_nBeginFrame(
      env, cls, handle, viewProj, cameraPos, fogStart, fogEnd);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain_1jni(JNIEnv *env,
                                                                 jclass cls,
                                                                 jlong handle,
                                                                 jint pass) {
  Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain(env, cls, handle,
                                                              pass);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nClearIndirectCommands_1jni(
    JNIEnv *env, jclass cls, jlong handle) {
  Java_com_metalrender_nativebridge_NativeBridge_nClearIndirectCommands(
      env, cls, handle);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBatchDrawCommands_1jni(
    JNIEnv *env, jclass cls, jlong handle, jobject buffer, jint commandCount) {
  Java_com_metalrender_nativebridge_NativeBridge_nBatchDrawCommands(
      env, cls, handle, buffer, commandCount);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nExecuteIndirect_1jni(
    JNIEnv *env, jclass cls, jlong handle, jint pass) {
  return Java_com_metalrender_nativebridge_NativeBridge_nExecuteIndirect(
      env, cls, handle, pass);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetTemporalJitter_1jni(
    JNIEnv *env, jclass cls, jlong handle, jfloat jitterX, jfloat jitterY,
    jfloat blendFactor) {
  Java_com_metalrender_nativebridge_NativeBridge_nSetTemporalJitter(
      env, cls, handle, jitterX, jitterY, blendFactor);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetLightParams_1jni(
    JNIEnv *env, jclass cls, jlong handle, jfloat dayBrightness,
    jfloat ambientLight, jfloat skyAngle) {
  Java_com_metalrender_nativebridge_NativeBridge_nSetLightParams(
      env, cls, handle, dayBrightness, ambientLight, skyAngle);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginEntityPass_1jni(
    JNIEnv *env, jclass cls, jlong handle, jfloatArray viewProj,
    jfloatArray cameraPos) {
  Java_com_metalrender_nativebridge_NativeBridge_nBeginEntityPass(
      env, cls, handle, viewProj, cameraPos);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawEntity_1jni(
    JNIEnv *env, jclass cls, jlong handle, jobject vertices, jint vertexCount,
    jlong textureHandle) {
  Java_com_metalrender_nativebridge_NativeBridge_nDrawEntity(
      env, cls, handle, vertices, vertexCount, textureHandle);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEndEntityPass_1jni(
    JNIEnv *env, jclass cls, jlong handle) {
  Java_com_metalrender_nativebridge_NativeBridge_nEndEntityPass(env, cls,
                                                                handle);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBatchDrawEntities(
    JNIEnv *env, jclass, jlong handle, jobject vertexBuf, jint totalVertexBytes,
    jobject cmdBuf, jint cmdCount) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || cmdCount <= 0)
    return;
  void *vertexData = env->GetDirectBufferAddress(vertexBuf);
  void *cmdData = env->GetDirectBufferAddress(cmdBuf);
  if (!vertexData || !cmdData)
    return;
  mr_batchDrawEntities(handle, vertexData, totalVertexBytes, cmdData, cmdCount);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBatchDrawEntities_1jni(
    JNIEnv *env, jclass cls, jlong handle, jobject vertexBuf,
    jint totalVertexBytes, jobject cmdBuf, jint cmdCount) {
  Java_com_metalrender_nativebridge_NativeBridge_nBatchDrawEntities(
      env, cls, handle, vertexBuf, totalVertexBytes, cmdBuf, cmdCount);
}
}
