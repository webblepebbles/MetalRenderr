#include "metalrender.h"
#import <AppKit/AppKit.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
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
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct DrawCommandData {
  uint32_t bufferOffset;
  uint32_t vertexCount;
  float originX, originY, originZ;
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
  // Increase buffer to 1GB to handle large worlds and frequent reloads
  size_t persistentCapacity = 1024 * 1024 * 1024;
  size_t persistentAlignment = 256;
  size_t persistentCursor = 0;
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

  // CAMetalLayer for direct presentation (full Metal mode)
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

  // Entity rendering resources
  id<MTLRenderPipelineState> entityPipeline = nil;
  id<MTLRenderPipelineState> entityColorOnlyPipeline =
      nil; // For no-texture rendering
  id<MTLRenderPipelineState> guiPipeline =
      nil; // GUI pipeline without depth attachment format
  id<MTLDepthStencilState> entityDepthState =
      nil; // Depth test but may differ from terrain
  id<MTLSamplerState> entitySampler = nil;
  id<MTLRenderCommandEncoder> currentEntityEncoder = nil;
  id<MTLCommandBuffer> currentEntityCommandBuffer = nil;
  bool entityPassActive = false;
  id<MTLTexture> whiteTexture = nil; // Fallback 1x1 white texture

  // Last command buffers - stored for waitForRender to sync on
  id<MTLCommandBuffer> lastGuiCommandBuffer = nil;
  id<MTLCommandBuffer> lastEntityCommandBuffer = nil;

  // Entity texture cache (texture handle -> MTLTexture)
  std::unordered_map<uint64_t, id<MTLTexture>> entityTextures;

  id<MTLTexture> atlasTexture = nil;
  uint32_t atlasWidth = 0;
  uint32_t atlasHeight = 0;

  float cameraX = 0, cameraY = 0, cameraZ = 0;
  float fogStart = 0, fogEnd = 1000;
  float fogR = 0.5f, fogG = 0.6f, fogB = 0.8f, fogA = 1.0f;
  float texShrinkU = 1.0f / 1024.0f;
  float texShrinkV = 1.0f / 1024.0f;

  // GUI rendering resources - separate texture for correct layering
  id<MTLTexture> guiTexture = nil; // GUI renders to this
  id<MTLRenderPipelineState> compositePipeline =
      nil;                             // Composites GUI over terrain
  id<MTLBuffer> compositeQuadVB = nil; // Fullscreen quad vertices
  uint32_t guiTextureWidth = 0;
  uint32_t guiTextureHeight = 0;
  bool guiNeedsComposite = false; // Flag to track if GUI was drawn

  // Shaders metallib path
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

  // Load the compiled metallib if we haven't yet
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

  // Create terrain render pipeline
  if (!ctx->terrainPipeline) {
    id<MTLFunction> vertexFunc =
        [ctx->terrainLibrary newFunctionWithName:@"terrain_color_vertex"];
    id<MTLFunction> fragmentFunc =
        [ctx->terrainLibrary newFunctionWithName:@"terrain_color_fragment"];
    if (!vertexFunc || !fragmentFunc) {
      printf("[MetalRender] Failed to find terrain shader functions\n");
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
    printf("[MetalRender] Created terrain render pipeline\n");
  }

  // Create depth stencil state
  if (!ctx->depthState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction =
        MTLCompareFunctionLessEqual; // Changed from Less to LessEqual
    depthDesc.depthWriteEnabled = YES;
    ctx->depthState =
        [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
    if (!ctx->depthState) {
      printf("[MetalRender] Failed to create depth stencil state\n");
      return false;
    }
  }

  // Create sampler for terrain atlas
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

// ============================================================================
// Entity Pipeline Setup
// ============================================================================

// Entity frame uniforms structure (must match entity.metal)
struct EntityFrameUniforms {
  float viewProj[16]; // View-projection matrix
  float cameraPos[4]; // Camera world position (xyz + padding)
  float fogColor[4];  // Fog color RGBA
  float fogParams[4]; // start, end, density, unused
};

static bool ensureEntityPipeline(MetalContext *ctx) {
  if (!ctx || !ctx->device)
    return false;
  if (ctx->entityPipeline && ctx->entityDepthState && ctx->entitySampler)
    return true;

  // We need the terrain library loaded (it should contain entity shaders too)
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

  // Create entity render pipeline
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

  // Create color-only entity pipeline (no texture required)
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

  // Create fallback 1x1 white texture
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

  // Create entity depth stencil state
  // Use depth testing so entities render correctly behind terrain
  if (!ctx->entityDepthState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction =
        MTLCompareFunctionLess;        // Normal depth test
    depthDesc.depthWriteEnabled = YES; // Write depth for proper occlusion
    ctx->entityDepthState =
        [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
    if (!ctx->entityDepthState) {
      printf("[MetalRender] Failed to create entity depth stencil state\n");
      return false;
    }
    printf("[MetalRender] Created entity depth state (depth test enabled)\n");
  }

  // Create GUI pipeline (same shaders but NO depth attachment format)
  // This is needed because GUI pass uses no depth texture
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
      // NO depth attachment format - GUI renders without depth
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

  // Create sampler for entity textures (nearest filtering for pixel-art
  // Minecraft style)
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

  // Check if existing IOSurface is the right size
  if (ctx->ioSurface && ctx->ioSurfaceWidth == width &&
      ctx->ioSurfaceHeight == height) {
    return true;
  }

  // Release old resources
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

  // Create IOSurface properties
  // BGRA pixel format = 'BGRA' - required for CGLTexImageIOSurface2D on macOS
  uint32_t pixelFormat = 'BGRA';

  size_t bytesPerRow = width * 4;
  size_t align = 256; // Safe alignment for Metal/IOSurface
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

  // Create Metal texture backed by IOSurface
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

  // Create depth texture for rendering
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
  ctx->colorTexture = ctx->ioSurfaceTexture; // Alias for clarity

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
    bool deviceSupported =
        [MTLFXTemporalScalerDescriptor supportsDevice:device];
    if (deviceSupported) {
      fprintf(
          stderr,
          "[MetalRender] MetalFX support detected and enabled for device: %s\n",
          [[device name] UTF8String]);
    } else {
      printf("[MetalRender] MetalFX not supported by device: %s\n",
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
        ctx->metalFxDepth && ctx->metalFxOutput) {
      ctx->metalFxDestroyed = false;
      ctx->metalFxScaler.inputContentWidth = inputWidth;
      ctx->metalFxScaler.inputContentHeight = inputHeight;
      ctx->metalFxScaler.colorTexture = ctx->metalFxColor;
      ctx->metalFxScaler.depthTexture = ctx->metalFxDepth;
      ctx->metalFxScaler.outputTexture = ctx->metalFxOutput;
      static uint64_t metalFxFrameCounter = 0;
      metalFxFrameCounter++;
      if (metalFxFrameCounter % 1000 == 0) {
        printf("[MetalRender] MetalFX upscaling active: input=%ux%u "
               "output=%ux%u scale=%.3f (frame %llu)\n",
               inputWidth, inputHeight, outputWidth, outputHeight, clampedScale,
               metalFxFrameCounter);
      }
      return true;
    }

    destroyMetalFXResources(ctx);

    printf("[MetalRender] Allocating MetalFX resources: output=%ux%u "
           "scale=%.3f input=%ux%u\n",
           outputWidth, outputHeight, clampedScale, inputWidth, inputHeight);

    MTLFXTemporalScalerDescriptor *descriptor =
        [[MTLFXTemporalScalerDescriptor alloc] init];
    descriptor.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
    descriptor.depthTextureFormat = MTLPixelFormatDepth32Float;
    descriptor.motionTextureFormat = MTLPixelFormatInvalid;
    descriptor.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
    descriptor.inputWidth = inputWidth;
    descriptor.inputHeight = inputHeight;
    descriptor.outputWidth = outputWidth;
    descriptor.outputHeight = outputHeight;
    descriptor.autoExposureEnabled = NO;
    descriptor.inputContentPropertiesEnabled = YES;
    descriptor.inputContentMinScale = 0.25F;
    descriptor.inputContentMaxScale = 1.0F;
    if (@available(macOS 14.4, *)) {
      if ([descriptor
              respondsToSelector:@selector(setReactiveMaskTextureEnabled:)]) {
        descriptor.reactiveMaskTextureEnabled = NO;
      }
    }

    id<MTLFXTemporalScaler> scaler =
        [descriptor newTemporalScalerWithDevice:ctx->device];
    if (!scaler) {
      printf("[MetalRender] Failed to create MetalFX temporal scaler\n");
      return false;
    }

    ctx->metalFxScaler = scaler;
    ctx->metalFxInputWidth = inputWidth;
    ctx->metalFxInputHeight = inputHeight;
    ctx->metalFxOutputWidth = outputWidth;
    ctx->metalFxOutputHeight = outputHeight;
    ctx->metalFxResetHistory = true;
    printf("[MetalRender] MetalFX resources successfully allocated and ready "
           "(input=%ux%u, output=%ux%u, "
           "scale=%.3f)\n",
           inputWidth, inputHeight, outputWidth, outputHeight, clampedScale);

    MTLPixelFormat colorFormat = scaler.colorTextureFormat;
    MTLPixelFormat depthFormat = scaler.depthTextureFormat;
    MTLPixelFormat outputFormat = scaler.outputTextureFormat;

    MTLTextureUsage colorUsage = scaler.colorTextureUsage |
                                 MTLTextureUsageRenderTarget |
                                 MTLTextureUsageShaderRead;
    MTLTextureUsage depthUsage = scaler.depthTextureUsage |
                                 MTLTextureUsageRenderTarget |
                                 MTLTextureUsageShaderRead;
    MTLTextureUsage outputUsage =
        scaler.outputTextureUsage | MTLTextureUsageRenderTarget |
        MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;

    MTLTextureDescriptor *colorDesc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:colorFormat
                                                           width:inputWidth
                                                          height:inputHeight
                                                       mipmapped:NO];
    colorDesc.storageMode = MTLStorageModePrivate;
    colorDesc.usage = colorUsage;
    ctx->metalFxColor = [ctx->device newTextureWithDescriptor:colorDesc];
    if (!ctx->metalFxColor) {
      fprintf(
          stderr,
          "[MetalRender] Failed to allocate MetalFX color texture (%u x %u)\n",
          inputWidth, inputHeight);
      destroyMetalFXResources(ctx);
      return false;
    }

    MTLTextureDescriptor *depthDesc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:depthFormat
                                                           width:inputWidth
                                                          height:inputHeight
                                                       mipmapped:NO];
    depthDesc.storageMode = MTLStorageModePrivate;
    depthDesc.usage = depthUsage;
    ctx->metalFxDepth = [ctx->device newTextureWithDescriptor:depthDesc];
    if (!ctx->metalFxDepth) {
      fprintf(
          stderr,
          "[MetalRender] Failed to allocate MetalFX depth texture (%u x %u)\n",
          inputWidth, inputHeight);
      destroyMetalFXResources(ctx);
      return false;
    }

    MTLTextureDescriptor *outputDesc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:outputFormat
                                                           width:outputWidth
                                                          height:outputHeight
                                                       mipmapped:NO];
    outputDesc.storageMode = MTLStorageModePrivate;
    outputDesc.usage = outputUsage;
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
    scaler.depthTexture = ctx->metalFxDepth;
    scaler.motionTexture = nil;
    scaler.outputTexture = ctx->metalFxOutput;
    scaler.preExposure = 1.0F;
    scaler.motionVectorScaleX = 1.0F;
    scaler.motionVectorScaleY = 1.0F;
    scaler.depthReversed = NO;
    scaler.inputContentWidth = inputWidth;
    scaler.inputContentHeight = inputHeight;
    scaler.jitterOffsetX = ctx->temporalJitterX;
    scaler.jitterOffsetY = ctx->temporalJitterY;
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
  return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nResize(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || width <= 0 || height <= 0)
    return;

  // IMPORTANT: IOSurface must match framebuffer exactly for correct blitting
  // Resolution scale is only used for internal render targets, not IOSurface
  uint32_t ioSurfaceWidth = static_cast<uint32_t>(width);
  uint32_t ioSurfaceHeight = static_cast<uint32_t>(height);
  ioSurfaceWidth = std::max(1u, ioSurfaceWidth);
  ioSurfaceHeight = std::max(1u, ioSurfaceHeight);

  // Ensure IOSurface is correct size (always full resolution for blitting)
  if (!ensureIOSurface(ctx, ioSurfaceWidth, ioSurfaceHeight)) {
    printf("[MetalRender] nResize: Failed to ensure IOSurface %ux%u\n",
           ioSurfaceWidth, ioSurfaceHeight);
  }

  // Store scale for potential internal rendering use (not used for IOSurface)
  (void)scale;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginFrame(
    JNIEnv *env, jclass, jlong handle, jfloatArray jViewProj,
    jfloatArray jCameraPos, jfloat fogStart, jfloat fogEnd) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->persistentBuffer ||
      !ctx->indirectArgs)
    return;

  // Store view projection matrix
  if (jViewProj) {
    jfloat *viewProj = env->GetFloatArrayElements(jViewProj, nullptr);
    if (viewProj) {
      memcpy(ctx->viewProj, viewProj, 16 * sizeof(float));
      ctx->hasViewProj = true;
      env->ReleaseFloatArrayElements(jViewProj, viewProj, JNI_ABORT);
    }
  }

  // Store camera position
  if (jCameraPos && env->GetArrayLength(jCameraPos) >= 3) {
    jfloat *cameraPos = env->GetFloatArrayElements(jCameraPos, nullptr);
    if (cameraPos) {
      ctx->cameraX = cameraPos[0];
      ctx->cameraY = cameraPos[1];
      ctx->cameraZ = cameraPos[2];
      env->ReleaseFloatArrayElements(jCameraPos, cameraPos, JNI_ABORT);
    }
  }

  // Store fog parameters
  ctx->fogStart = fogStart;
  ctx->fogEnd = fogEnd;
}

// Shader uniform structures matching metalrender.metal
struct DrawUniforms {
  float originX;
  float originY;
  float originZ;
  float padding;
};

struct FrameUniforms {
  float viewProj[16];
  float cameraX, cameraY, cameraZ, cameraPad;
  float fogR, fogG, fogB, fogA;
  float fogStart, fogEnd, fogPad1, fogPad2;
  float texShrinkU, texShrinkV, texShrinkPad1, texShrinkPad2;
};

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain(JNIEnv *, jclass,
                                                            jlong handle,
                                                            jint pass) {
  (void)pass;

  // DEBUG: Trace entry
  static int traceCounter = 0;
  if (traceCounter++ < 100)
    printf("[MetalRender] nDrawTerrain ENTRY frame=%d\n", traceCounter);

  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->ioSurfaceTexture ||
      !ctx->depthTexture) {
    printf("[MetalRender] nDrawTerrain: missing resources (ctx=%p, q=%p, "
           "tex=%p, depth=%p)\n",
           ctx, ctx ? ctx->graphicsQueue : 0, ctx ? ctx->ioSurfaceTexture : 0,
           ctx ? ctx->depthTexture : 0);
    return;
  }

  // Ensure terrain pipeline is created
  if (!ensureTerrainPipeline(ctx)) {
    if (traceCounter < 100)
      printf("[MetalRender] nDrawTerrain: ensureTerrainPipeline failed\n");
    // Pipeline not ready - just clear to sky blue
    MTLRenderPassDescriptor *passDesc =
        [MTLRenderPassDescriptor renderPassDescriptor];
    passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
    passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
    passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
    // Clear to transparent so OpenGL content shows through empty areas
    passDesc.colorAttachments[0].clearColor =
        MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    passDesc.depthAttachment.texture = ctx->depthTexture;
    passDesc.depthAttachment.loadAction = MTLLoadActionClear;
    passDesc.depthAttachment.storeAction =
        MTLStoreActionStore; // Store depth for entity pass
    passDesc.depthAttachment.clearDepth = 1.0;

    id<MTLCommandBuffer> cb = [ctx->graphicsQueue commandBuffer];
    id<MTLRenderCommandEncoder> enc =
        [cb renderCommandEncoderWithDescriptor:passDesc];
    [enc endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    return;
  }

  // Create render pass descriptor
  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
  // Clear to transparent so OpenGL content shows through empty areas
  passDesc.colorAttachments[0].clearColor =
      MTLClearColorMake(0.0, 0.0, 0.0, 0.0);

  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction = MTLLoadActionClear;
  passDesc.depthAttachment.storeAction =
      MTLStoreActionStore; // Store depth for entity pass
  passDesc.depthAttachment.clearDepth = 1.0;

  // Create command buffer and render encoder
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

  // Set up pipeline state
  [encoder setRenderPipelineState:ctx->terrainPipeline];
  [encoder setDepthStencilState:ctx->depthState]; // Re-enabled with LessEqual
  [encoder setCullMode:MTLCullModeNone]; // TEMP: Disable culling to debug
  [encoder setFrontFacingWinding:MTLWindingCounterClockwise];

  // Set viewport to match IOSurface dimensions
  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [encoder setViewport:viewport];

  // Create and bind frame uniforms
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

  [encoder setVertexBytes:&frameUniforms
                   length:sizeof(frameUniforms)
                  atIndex:2];
  [encoder setFragmentBytes:&frameUniforms
                     length:sizeof(frameUniforms)
                    atIndex:2];

  // Bind atlas texture if available
  if (ctx->atlasTexture) {
    [encoder setFragmentTexture:ctx->atlasTexture atIndex:0];
    [encoder setFragmentSamplerState:ctx->terrainSampler atIndex:0];
  } else {
    // Debug: Log when atlas is missing during draw
    static int noAtlasCounter = 0;
    if (noAtlasCounter++ % 60 == 0) {
      fprintf(
          stderr,
          "[MetalRender] nDrawTerrain: WARNING - no atlas texture bound!\n");
    }
  }

  // Debug: Log draw stats periodically (only first 3 frames to reduce overhead)
  static int drawCounter = 0;
  drawCounter++;
  bool shouldLog = (drawCounter <= 3);
  if (shouldLog) {
    printf("[MetalRender] nDrawTerrain: viewport=%ux%u, drawCommands=%u, "
           "atlasTexture=%s, hasViewProj=%d\n",
           ctx->ioSurfaceWidth, ctx->ioSurfaceHeight, ctx->currentIndirectCount,
           ctx->atlasTexture ? "YES" : "NO", ctx->hasViewProj ? 1 : 0);
    // Debug: Print viewProj matrix
    printf("[MetalRender] viewProj matrix:\n");
    printf("  [%8.3f %8.3f %8.3f %8.3f]\n", frameUniforms.viewProj[0],
           frameUniforms.viewProj[1], frameUniforms.viewProj[2],
           frameUniforms.viewProj[3]);
    printf("  [%8.3f %8.3f %8.3f %8.3f]\n", frameUniforms.viewProj[4],
           frameUniforms.viewProj[5], frameUniforms.viewProj[6],
           frameUniforms.viewProj[7]);
    printf("  [%8.3f %8.3f %8.3f %8.3f]\n", frameUniforms.viewProj[8],
           frameUniforms.viewProj[9], frameUniforms.viewProj[10],
           frameUniforms.viewProj[11]);
    printf("  [%8.3f %8.3f %8.3f %8.3f]\n", frameUniforms.viewProj[12],
           frameUniforms.viewProj[13], frameUniforms.viewProj[14],
           frameUniforms.viewProj[15]);
    printf("[MetalRender] camera=(%f, %f, %f)\n", frameUniforms.cameraX,
           frameUniforms.cameraY, frameUniforms.cameraZ);
  }

  // Draw each chunk
  uint32_t actualDraws = 0;
  for (uint32_t i = 0;
       i < ctx->currentIndirectCount && i < ctx->drawCommands.size(); i++) {
    const DrawCommandData &cmd = ctx->drawCommands[i];
    if (cmd.vertexCount == 0)
      continue;

    // Debug: Print first draw command details (only for first few frames)
    if (shouldLog && actualDraws == 0) {
      printf("[MetalRender] First draw: origin=(%f, %f, %f), vertices=%u, "
             "offset=%lu\n",
             cmd.originX, cmd.originY, cmd.originZ, cmd.vertexCount,
             cmd.bufferOffset);
    }

    // Set per-draw uniforms (chunk origin)
    DrawUniforms drawUniforms;
    drawUniforms.originX = cmd.originX;
    drawUniforms.originY = cmd.originY;
    drawUniforms.originZ = cmd.originZ;
    drawUniforms.padding = 0;

    [encoder setVertexBytes:&drawUniforms
                     length:sizeof(drawUniforms)
                    atIndex:1];

    // Bind vertex data at the correct offset
    // The vertex data is in the persistent buffer at cmd.bufferOffset
    [encoder setVertexBuffer:ctx->persistentBuffer
                      offset:cmd.bufferOffset
                     atIndex:0];

    // Draw primitives (triangles)
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle
                vertexStart:0
                vertexCount:cmd.vertexCount];
    actualDraws++;
  }

  // Debug: Log actual draws (only for first few frames)
  if (shouldLog) {
    printf("[MetalRender] nDrawTerrain: executed %u actual draw calls\n",
           actualDraws);
  }

  [encoder endEncoding];

  // DEBUG: Force cyan clear - DISABLED (terrain is now visible!)
  // static int forceColorFrame = 0;
  // forceColorFrame++;
  // ... (disabled)

  // Add synchronize resource call to ensure IOSurface is updated
  // This is required for shared storage mode textures
  id<MTLBlitCommandEncoder> blitEncoder = [commandBuffer blitCommandEncoder];
  [blitEncoder synchronizeResource:ctx->ioSurfaceTexture];
  [blitEncoder endEncoding];

  [commandBuffer commit];
  [commandBuffer waitUntilCompleted];
}
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPrewarmPipelines(JNIEnv *,
                                                                 jclass,
                                                                 jlong handle) {
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
  // Clean up IOSurface resources
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
    printf("[MetalRender Native] getContext returned null.\n");
    return env->NewStringUTF("Unknown");
  }
  printf("[MetalRender Native] Device name: %s\n", ctx->deviceName.c_str());
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
  return JNI_FALSE;
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
    JNIEnv *, jclass, jlong handle, jboolean enabled) {}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nConfigureMetalFX(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {}

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

// Upload atlas texture data from Java ByteBuffer
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

  // Release old atlas texture if it exists
  if (ctx->atlasTexture) {
    ctx->atlasTexture = nil;
  }

  // Create new atlas texture descriptor
  // Use BGRA8 format to match the Java code's BGRA byte order
  MTLTextureDescriptor *texDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                   width:(NSUInteger)width
                                  height:(NSUInteger)height
                               mipmapped:NO];
  texDesc.usage = MTLTextureUsageShaderRead;
  texDesc.storageMode = MTLStorageModeShared;

  ctx->atlasTexture = [ctx->device newTextureWithDescriptor:texDesc];
  if (!ctx->atlasTexture) {
    printf("[MetalRender] nUploadAtlas: failed to create texture %dx%d\n",
           width, height);
    return JNI_FALSE;
  }

  // Upload pixel data to texture
  MTLRegion region =
      MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);
  NSUInteger bytesPerRow = (NSUInteger)(width * 4);
  [ctx->atlasTexture replaceRegion:region
                       mipmapLevel:0
                         withBytes:data
                       bytesPerRow:bytesPerRow];

  fprintf(
      stderr,
      "[MetalRender] nUploadAtlas: uploaded %dx%d atlas texture successfully\n",
      width, height);
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
    jlong, jint vertexCount, jint chunkX, jint chunkY, jint chunkZ, jint) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  if (commandIndex < 0 ||
      commandIndex >= static_cast<jint>(ctx->maxIndirectCommands))
    return;

  // Store draw command data with chunk origin
  DrawCommandData cmd;
  cmd.bufferOffset = static_cast<uint32_t>(bufferOffset);
  cmd.vertexCount = static_cast<uint32_t>(vertexCount);
  cmd.originX = static_cast<float>(chunkX);
  cmd.originY = static_cast<float>(chunkY);
  cmd.originZ = static_cast<float>(chunkZ);

  // Ensure vector is large enough
  if (static_cast<size_t>(commandIndex) >= ctx->drawCommands.size()) {
    ctx->drawCommands.resize(commandIndex + 1);
  }
  ctx->drawCommands[commandIndex] = cmd;

  uint32_t nextCount = static_cast<uint32_t>(commandIndex + 1);
  ctx->currentIndirectCount = std::max(ctx->currentIndirectCount, nextCount);
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
      ctx->metalFxDepth && ctx->metalFxOutput) {
    MTLRenderPassDescriptor *passDesc =
        [MTLRenderPassDescriptor renderPassDescriptor];
    passDesc.colorAttachments[0].texture = ctx->metalFxColor;
    passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
    passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
    // Clear to transparent for compositing
    passDesc.colorAttachments[0].clearColor =
        MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    passDesc.depthAttachment.texture = ctx->metalFxDepth;
    passDesc.depthAttachment.loadAction = MTLLoadActionClear;
    passDesc.depthAttachment.storeAction = MTLStoreActionStore;
    passDesc.depthAttachment.clearDepth = 1.0;

    id<MTLRenderCommandEncoder> encoder =
        [commandBuffer renderCommandEncoderWithDescriptor:passDesc];
    if (encoder) {

      [encoder endEncoding];
    }

    ctx->metalFxScaler.colorTexture = ctx->metalFxColor;
    ctx->metalFxScaler.depthTexture = ctx->metalFxDepth;
    ctx->metalFxScaler.outputTexture = ctx->metalFxOutput;
    ctx->metalFxScaler.inputContentWidth = ctx->metalFxInputWidth;
    ctx->metalFxScaler.inputContentHeight = ctx->metalFxInputHeight;
    if (ctx->metalFxResetHistory) {
      ctx->metalFxScaler.reset = YES;
      ctx->metalFxResetHistory = false;
    } else {
      ctx->metalFxScaler.reset = NO;
    }
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
#if METALRENDER_HAS_METALFX
  if (ctx->metalFxScaler) {
    ctx->metalFxScaler.jitterOffsetX = jitterX;
    ctx->metalFxScaler.jitterOffsetY = jitterY;
  }
#endif
}

// IOSurface methods for blitting Metal content to OpenGL
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

  // Get current OpenGL context
  CGLContextObj cglContext = CGLGetCurrentContext();
  if (!cglContext) {
    printf("[MetalRender] No current CGL context for IOSurface binding\n");
    return JNI_FALSE;
  }

  // Bind IOSurface to OpenGL texture using GL_TEXTURE_RECTANGLE
  // Note: IOSurfaces use GL_TEXTURE_RECTANGLE, not GL_TEXTURE_2D
  GLenum target = GL_TEXTURE_RECTANGLE;
  glBindTexture(target, static_cast<GLuint>(glTexture));

  CGLError err = CGLTexImageIOSurface2D(
      cglContext, target, GL_RGBA, static_cast<GLsizei>(ctx->ioSurfaceWidth),
      static_cast<GLsizei>(ctx->ioSurfaceHeight), GL_BGRA,
      GL_UNSIGNED_INT_8_8_8_8_REV, ctx->ioSurface,
      0 // plane
  );

  if (err != kCGLNoError) {
    printf("[MetalRender] CGLTexImageIOSurface2D failed: %d\n", err);
    return JNI_FALSE;
  }

  return JNI_TRUE;
}

// Wait for any pending Metal commands to complete
// This ensures the IOSurface has valid data from all render passes
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nWaitForRender(JNIEnv *, jclass,
                                                              jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue) {
    return;
  }

  // Wait for entity command buffer if pending
  if (ctx->lastEntityCommandBuffer) {
    [ctx->lastEntityCommandBuffer waitUntilCompleted];
    ctx->lastEntityCommandBuffer = nil;
  }

  // Wait for GUI command buffer if pending
  if (ctx->lastGuiCommandBuffer) {
    [ctx->lastGuiCommandBuffer waitUntilCompleted];
    ctx->lastGuiCommandBuffer = nil;
  }

  // Create an empty command buffer and wait for it to complete
  // This effectively flushes any other pending work on the graphics queue
  id<MTLCommandBuffer> syncBuffer = [ctx->graphicsQueue commandBuffer];
  if (syncBuffer) {
    [syncBuffer commit];
    [syncBuffer waitUntilCompleted];
  }

  static int waitCount = 0;
  if (waitCount++ < 10 || waitCount % 60 == 0) {
    printf("[MetalRender] nWaitForRender: sync complete\n");
  }
}

// CPU readback - copy Metal render target to ByteBuffer for glTexImage2D upload
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
  size_t requiredSize = width * height * 4; // BGRA = 4 bytes per pixel

  if (bufferCapacity < (jlong)requiredSize) {
    printf("[MetalRender] nReadbackPixels: buffer too small (%lld < %zu)\n",
           bufferCapacity, requiredSize);
    return JNI_FALSE;
  }

  // Read pixels from IOSurface directly to handle stride correctly
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

  // Copy row by row to pack the data
  uint8_t *src = (uint8_t *)baseAddr;
  uint8_t *dst = (uint8_t *)bufferPtr;
  size_t rowBytes = width * 4;

  // Ensure we don't read past the buffer or surface
  if (sourceStride < rowBytes) {
    // This shouldn't happen if we allocated correctly, but safety first
    rowBytes = sourceStride;
  }

  // Real copy from IOSurface
  for (uint32_t y = 0; y < height; y++) {
    memcpy(dst + y * (width * 4), src + y * sourceStride, rowBytes);
  }

  // DEBUG: Log sample pixels from BOTH source AND destination
  static int logCounter = 0;
  if (height > 0 && width > 0 && logCounter++ <= 10) {
    // Sample center pixel from SOURCE (IOSurface)
    uint32_t cx = width / 2;
    uint32_t cy = height / 2;
    uint8_t *centerPixelSrc = src + cy * sourceStride + cx * 4;
    // Sample corner from SOURCE
    uint8_t *cornerPixelSrc = src;

    // Sample center pixel from DESTINATION (Java buffer)
    uint8_t *centerPixelDst = dst + cy * width * 4 + cx * 4;
    // Sample corner from DESTINATION
    uint8_t *cornerPixelDst = dst;

    printf("[MetalRender] IOSurface SRC: center(%u,%u) BGRA=%d,%d,%d,%d  "
           "corner(0,0) BGRA=%d,%d,%d,%d\n",
           cx, cy, centerPixelSrc[0], centerPixelSrc[1], centerPixelSrc[2],
           centerPixelSrc[3], cornerPixelSrc[0], cornerPixelSrc[1],
           cornerPixelSrc[2], cornerPixelSrc[3]);
    printf("[MetalRender] Java DST: center(%u,%u) BGRA=%d,%d,%d,%d  "
           "corner(0,0) BGRA=%d,%d,%d,%d\n",
           cx, cy, centerPixelDst[0], centerPixelDst[1], centerPixelDst[2],
           centerPixelDst[3], cornerPixelDst[0], cornerPixelDst[1],
           cornerPixelDst[2], cornerPixelDst[3]);
  }

  IOSurfaceUnlock(ctx->ioSurface, kIOSurfaceLockReadOnly, nullptr);

  return JNI_TRUE;
}

// Shader path for loading compiled metallib
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

// Surface attachment for window integration - FULL METAL MODE
// This REPLACES OpenGL entirely - Metal becomes the sole renderer
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

    // Get the content view
    NSView *contentView = [window contentView];
    if (!contentView) {
      printf("[MetalRender] nAttachSurface: no content view\n");
      return JNI_FALSE;
    }

    // For 100% Metal mode, we add CAMetalLayer ON TOP of everything
    // and cancel the OpenGL swap buffer in Java
    CAMetalLayer *metalLayer = [CAMetalLayer layer];
    metalLayer.device = ctx->device;
    metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    metalLayer.framebufferOnly = NO; // Allow reading for screenshots
    metalLayer.drawableSize =
        CGSizeMake(ctx->ioSurfaceWidth, ctx->ioSurfaceHeight);
    metalLayer.opaque = YES; // OPAQUE - Metal is the only renderer now!
    metalLayer.frame = contentView.bounds;
    metalLayer.contentsScale = contentView.window.backingScaleFactor;

    // CRITICAL: Disable display sync to prevent blocking on nextDrawable
    // This avoids freezes when OpenGL/Metal vsync conflict
    metalLayer.displaySyncEnabled = NO;

    // Use triple buffering for smooth presentation
    metalLayer.maximumDrawableCount = 3;

    // Allow next drawable to return nil instead of blocking
    metalLayer.allowsNextDrawableTimeout = YES;

    // Add as sublayer ON TOP (zPosition > 0)
    // Use dispatch_sync to ensure layer is added before we return
    __block bool layerAdded = false;
    if ([NSThread isMainThread]) {
      // Already on main thread, just add it
      [contentView setWantsLayer:YES];
      metalLayer.zPosition = 1000;
      [[contentView layer] addSublayer:metalLayer];
      layerAdded = true;
      printf("[MetalRender] CAMetalLayer added on main thread (direct)\n");
    } else {
      // Dispatch synchronously to main thread
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

// Present the current frame directly to screen (Full Metal mode)
JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPresentFrame(JNIEnv *, jclass,
                                                             jlong handle) {
  MetalContext *ctx = getContext(handle);

  // Debug logging - always log for now to diagnose
  static int presentFrameCount = 0;
  presentFrameCount++;

  // Very verbose logging for first 10 frames
  if (presentFrameCount <= 10) {
    printf("[MetalRender] nPresentFrame #%d: ctx=%p\\n", presentFrameCount,
           ctx);
    if (ctx) {
      printf("  -> directPresentEnabled=%d, metalLayer=%p, "
             "ioSurfaceTexture=%p, size=%ux%u\\n",
             ctx->directPresentEnabled, (void *)ctx->metalLayer,
             (void *)ctx->ioSurfaceTexture, ctx->ioSurfaceWidth,
             ctx->ioSurfaceHeight);
    }
  } else if (presentFrameCount % 300 == 0) {
    printf("[MetalRender] nPresentFrame #%d: enabled=%d, layer=%p\\n",
           presentFrameCount, ctx ? ctx->directPresentEnabled : 0,
           ctx ? (void *)ctx->metalLayer : nullptr);
  }

  if (!ctx || !ctx->directPresentEnabled || !ctx->metalLayer ||
      !ctx->ioSurfaceTexture) {
    if (presentFrameCount <= 10) {
      printf("[MetalRender] nPresentFrame: FAILED - missing state (enabled=%d, "
             "layer=%p, tex=%p)\\n",
             ctx ? ctx->directPresentEnabled : 0,
             ctx ? (void *)ctx->metalLayer : nullptr,
             ctx ? (void *)ctx->ioSurfaceTexture : nullptr);
    }
    return JNI_FALSE;
  }

  @autoreleasepool {
    // Update layer drawable size to match IOSurface
    CGSize currentSize = ctx->metalLayer.drawableSize;
    if (currentSize.width != ctx->ioSurfaceWidth ||
        currentSize.height != ctx->ioSurfaceHeight) {
      ctx->metalLayer.drawableSize =
          CGSizeMake(ctx->ioSurfaceWidth, ctx->ioSurfaceHeight);
      printf("[MetalRender] nPresentFrame: updated layer size to %ux%u\n",
             ctx->ioSurfaceWidth, ctx->ioSurfaceHeight);
    }

    // Get next drawable from the layer - use non-blocking approach
    // With allowsNextDrawableTimeout=YES and displaySyncEnabled=NO,
    // this should return nil quickly if no drawable available
    id<CAMetalDrawable> drawable = [ctx->metalLayer nextDrawable];
    if (!drawable) {
      if (presentFrameCount <= 10 || presentFrameCount % 300 == 0) {
        printf("[MetalRender] nPresentFrame #%d: no drawable available "
               "(skipping frame)\n",
               presentFrameCount);
      }
      return JNI_FALSE;
    }

    // Create command buffer
    id<MTLCommandBuffer> commandBuffer = [ctx->graphicsQueue commandBuffer];
    if (!commandBuffer) {
      return JNI_FALSE;
    }

    // Blit IOSurface texture to drawable texture
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

    // Present and commit
    [commandBuffer presentDrawable:drawable];
    [commandBuffer commit];

    if (presentFrameCount <= 10) {
      printf("[MetalRender] nPresentFrame #%d: successfully presented\n",
             presentFrameCount);
    }

    return JNI_TRUE;
  }
}

// Memory monitoring
JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetDeviceMemory(JNIEnv *,
                                                                jclass,
                                                                jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device)
    return 0;
  // Return recommended max working set size (unified memory)
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
  // Return current allocated size
  if (@available(macOS 10.13, *)) {
    return static_cast<jlong>([ctx->device currentAllocatedSize]);
  }
  return 0;
}

// Fence synchronization
JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nCreateFence(JNIEnv *, jclass,
                                                            jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device)
    return 0;
  id<MTLFence> fence = [ctx->device newFence];
  if (!fence)
    return 0;
  // Store the fence and return a handle
  return reinterpret_cast<jlong>((__bridge_retained void *)fence);
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPollFence(JNIEnv *, jclass,
                                                          jlong handle,
                                                          jlong fenceHandle) {
  (void)handle;
  if (fenceHandle == 0)
    return JNI_TRUE;
  // Metal fences don't have a poll mechanism - they're for GPU-GPU sync
  // Return true as "completed" since we can't really poll
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
    return 0; // Success - nothing to wait for
  // Metal fences are GPU-GPU only, can't wait on CPU side
  // For CPU sync, use command buffer completion handlers
  return 0; // Return success
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyFence(
    JNIEnv *, jclass, jlong handle, jlong fenceHandle) {
  (void)handle;
  if (fenceHandle == 0)
    return;
  id<MTLFence> fence = (__bridge_transfer id<MTLFence>)(void *)fenceHandle;
  fence = nil; // Release the fence
}

// ============================================================================
// Entity Rendering - Stub implementations
// These will be filled in when entity rendering is fully implemented
// ============================================================================

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

  // Create texture descriptor
  // Note: Java uploads BGRA format from OpenGL readback
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

  // Upload pixel data
  MTLRegion region = MTLRegionMake2D(0, 0, width, height);
  [texture replaceRegion:region
             mipmapLevel:0
               withBytes:pixelData
             bytesPerRow:width * 4];

  // Store in texture cache and return handle
  uint64_t texHandle =
      reinterpret_cast<uint64_t>((__bridge_retained void *)texture);
  ctx->entityTextures[texHandle] = texture;

  static int uploadCount = 0;
  if (uploadCount++ < 20) {
    printf("[metalrender] nUploadEntityTexture: created %dx%d texture "
           "handle=%llu\n",
           width, height, texHandle);
  }

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
    // ARC will release the texture
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

  // Ensure entity pipeline exists
  if (!ensureEntityPipeline(ctx)) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr,
              "[metalrender] nBeginEntityPass: failed to create pipeline\n");
    }
    return;
  }

  // End any existing entity pass
  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    [ctx->currentEntityCommandBuffer waitUntilCompleted];
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
  }

  // Create render pass - LOAD depth from terrain pass, don't clear
  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction =
      MTLLoadActionLoad; // Keep terrain colors
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

  passDesc.depthAttachment.texture = ctx->depthTexture;
  passDesc.depthAttachment.loadAction =
      MTLLoadActionLoad; // KEEP terrain depth!
  passDesc.depthAttachment.storeAction = MTLStoreActionStore;

  // Create command buffer and encoder
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

  // Set up pipeline state
  [ctx->currentEntityEncoder setRenderPipelineState:ctx->entityPipeline];
  [ctx->currentEntityEncoder setDepthStencilState:ctx->entityDepthState];
  [ctx->currentEntityEncoder
      setCullMode:MTLCullModeNone]; // Disable culling - entities have varying
                                    // winding
  [ctx->currentEntityEncoder setFrontFacingWinding:MTLWindingCounterClockwise];

  // Set viewport
  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [ctx->currentEntityEncoder setViewport:viewport];

  // Set up frame uniforms from passed data
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

  // Set fog params from context
  frameUniforms.fogColor[0] = ctx->fogR;
  frameUniforms.fogColor[1] = ctx->fogG;
  frameUniforms.fogColor[2] = ctx->fogB;
  frameUniforms.fogColor[3] = ctx->fogA;
  frameUniforms.fogParams[0] = ctx->fogStart;
  frameUniforms.fogParams[1] = ctx->fogEnd;
  frameUniforms.fogParams[2] = 0.0f;
  frameUniforms.fogParams[3] = 0.0f;

  [ctx->currentEntityEncoder setVertexBytes:&frameUniforms
                                     length:sizeof(frameUniforms)
                                    atIndex:1];
  [ctx->currentEntityEncoder setFragmentBytes:&frameUniforms
                                       length:sizeof(frameUniforms)
                                      atIndex:1];

  ctx->entityPassActive = true;

  static int beginCount = 0;
  if (beginCount++ < 20) {
    printf("[metalrender] nBeginEntityPass: started entity render pass\n");
  }
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

  // Vertex data: 32 bytes per vertex (position, UV, color, normal, padding)
  size_t vertexDataSize = static_cast<size_t>(vertexCount) * 32;

  // Create temporary buffer for vertex data
  id<MTLBuffer> vertexBuffer =
      [ctx->device newBufferWithBytes:vertexData
                               length:vertexDataSize
                              options:MTLResourceStorageModeShared];
  if (!vertexBuffer) {
    fprintf(stderr,
            "[metalrender] nDrawEntity: failed to create vertex buffer\n");
    return;
  }

  // Bind vertex buffer
  [ctx->currentEntityEncoder setVertexBuffer:vertexBuffer offset:0 atIndex:0];

  // Determine which texture to use
  id<MTLTexture> textureToUse = nil;

  if (textureHandle != 0) {
    auto it = ctx->entityTextures.find(static_cast<uint64_t>(textureHandle));
    if (it != ctx->entityTextures.end() && it->second) {
      textureToUse = it->second;
    }
  }

  // If no texture, use fallback white texture
  if (!textureToUse && ctx->whiteTexture) {
    textureToUse = ctx->whiteTexture;
  }

  // Bind texture and sampler
  if (textureToUse) {
    [ctx->currentEntityEncoder setFragmentTexture:textureToUse atIndex:0];
    [ctx->currentEntityEncoder setFragmentSamplerState:ctx->entitySampler
                                               atIndex:0];
  }

  // Draw triangles
  [ctx->currentEntityEncoder
      drawPrimitives:MTLPrimitiveTypeTriangle
         vertexStart:0
         vertexCount:static_cast<NSUInteger>(vertexCount)];

  static int drawCount = 0;
  if (drawCount++ < 100) {
    printf("[metalrender] nDrawEntity: drew %d vertices, tex=%llu (using=%p)\n",
           vertexCount, static_cast<unsigned long long>(textureHandle),
           textureToUse);
  }
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

    // Store for waitForRender to sync on
    ctx->lastEntityCommandBuffer = ctx->currentEntityCommandBuffer;

    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;

    static int endCount = 0;
    if (endCount++ < 20) {
      printf("[metalrender] nEndEntityPass: ended entity render pass\n");
    }
  }
}

// =========================================================================
// Item Rendering Pass - For GUI items with optional depth testing
// 3D block items need depth testing, flat items don't
// =========================================================================

// Item pass state - tracks whether we're in an item pass and current depth mode
static bool itemPassActive = false;
static bool currentItemDepthTest = false;

// Depth states for items
static id<MTLDepthStencilState> itemDepthTestState = nil;   // For 3D blocks
static id<MTLDepthStencilState> itemNoDepthTestState = nil; // For flat items

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

  // Ensure entity pipeline exists (reuse it for items)
  if (!ensureEntityPipeline(ctx)) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr,
              "[metalrender] nBeginItemPass: failed to create pipeline\n");
    }
    return;
  }

  // Ensure item depth states exist
  ensureItemDepthStates(ctx);

  // End any existing entity pass
  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;
  }

  // Get Java arrays
  jfloat *viewProjData = env->GetFloatArrayElements(viewProj, nullptr);
  jfloat *cameraPosData = env->GetFloatArrayElements(cameraPos, nullptr);

  // Create render pass descriptor - load existing content, store result
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

  // Store in entity context (reuse same encoder storage)
  ctx->currentEntityEncoder = encoder;
  ctx->currentEntityCommandBuffer = commandBuffer;

  // Set up render state
  [encoder setRenderPipelineState:ctx->entityPipeline];
  [encoder setCullMode:MTLCullModeNone];
  [encoder setFrontFacingWinding:MTLWindingCounterClockwise];

  // Start with no depth test (will be set per-draw)
  [encoder setDepthStencilState:itemNoDepthTestState];
  currentItemDepthTest = false;

  // Set uniforms
  EntityFrameUniforms uniforms;
  memcpy(uniforms.viewProj, viewProjData, 16 * sizeof(float));
  uniforms.cameraPos[0] = cameraPosData[0];
  uniforms.cameraPos[1] = cameraPosData[1];
  uniforms.cameraPos[2] = cameraPosData[2];
  uniforms.cameraPos[3] = 1.0f;
  // Set default fog values
  uniforms.fogColor[0] = 1.0f;
  uniforms.fogColor[1] = 1.0f;
  uniforms.fogColor[2] = 1.0f;
  uniforms.fogColor[3] = 0.0f;
  uniforms.fogParams[0] = 1000.0f;  // Far fog start
  uniforms.fogParams[1] = 10000.0f; // Far fog end
  uniforms.fogParams[2] = 0.0f;
  uniforms.fogParams[3] = 0.0f;
  [encoder setVertexBytes:&uniforms
                   length:sizeof(EntityFrameUniforms)
                  atIndex:1];

  env->ReleaseFloatArrayElements(viewProj, viewProjData, JNI_ABORT);
  env->ReleaseFloatArrayElements(cameraPos, cameraPosData, JNI_ABORT);

  itemPassActive = true;
  ctx->entityPassActive = true;

  static int beginCount = 0;
  if (beginCount++ < 20) {
    printf("[metalrender] nBeginItemPass: started item render pass\n");
  }
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

  // Switch depth state if needed
  bool needsDepth = (useDepthTest == JNI_TRUE);
  if (needsDepth != currentItemDepthTest) {
    if (needsDepth) {
      [ctx->currentEntityEncoder setDepthStencilState:itemDepthTestState];
    } else {
      [ctx->currentEntityEncoder setDepthStencilState:itemNoDepthTestState];
    }
    currentItemDepthTest = needsDepth;
  }

  // Vertex data: 32 bytes per vertex (position, UV, color, normal, padding)
  size_t vertexDataSize = static_cast<size_t>(vertexCount) * 32;

  // Create temporary buffer for vertex data
  id<MTLBuffer> vertexBuffer =
      [ctx->device newBufferWithBytes:vertexData
                               length:vertexDataSize
                              options:MTLResourceStorageModeShared];
  if (!vertexBuffer) {
    fprintf(stderr,
            "[metalrender] nDrawItem: failed to create vertex buffer\n");
    return;
  }

  // Bind vertex buffer
  [ctx->currentEntityEncoder setVertexBuffer:vertexBuffer offset:0 atIndex:0];

  // Determine which texture to use
  id<MTLTexture> textureToUse = nil;

  if (textureHandle != 0) {
    auto it = ctx->entityTextures.find(static_cast<uint64_t>(textureHandle));
    if (it != ctx->entityTextures.end() && it->second) {
      textureToUse = it->second;
    }
  }

  // If no texture, use fallback white texture
  if (!textureToUse && ctx->whiteTexture) {
    textureToUse = ctx->whiteTexture;
  }

  // Bind texture and sampler
  if (textureToUse) {
    [ctx->currentEntityEncoder setFragmentTexture:textureToUse atIndex:0];
    [ctx->currentEntityEncoder setFragmentSamplerState:ctx->entitySampler
                                               atIndex:0];
  }

  // Draw triangles
  [ctx->currentEntityEncoder
      drawPrimitives:MTLPrimitiveTypeTriangle
         vertexStart:0
         vertexCount:static_cast<NSUInteger>(vertexCount)];

  static int drawCount = 0;
  if (drawCount++ < 100) {
    printf("[metalrender] nDrawItem: drew %d vertices, tex=%llu, depth=%s\n",
           vertexCount, static_cast<unsigned long long>(textureHandle),
           needsDepth ? "ON" : "OFF");
  }
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

    static int endCount = 0;
    if (endCount++ < 20) {
      printf("[metalrender] nEndItemPass: ended item render pass\n");
    }
  }
}

// =========================================================================
// Hand Rendering - Clear depth buffer so hand renders in front of everything
// =========================================================================

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

  // Create a render pass that ONLY clears the depth buffer
  // This ensures hand/item rendering will always be in front
  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];

  // Don't touch color - keep terrain/entities
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad;
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;

  // Clear depth to far plane (1.0)
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

  // Just end immediately - the clear happens on encoder creation
  [encoder endEncoding];
  [commandBuffer commit];
  // Don't wait - async is fine

  static int clearCount = 0;
  if (clearCount++ < 20) {
    printf(
        "[metalrender] nClearDepthForHand: cleared depth for hand rendering\n");
  }
}

// =========================================================================
// GUI Rendering Pass - No depth testing, always renders in front
// =========================================================================

// GUI-specific depth state (no depth testing)
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

  // Ensure entity pipeline exists (reuse it for GUI)
  if (!ensureEntityPipeline(ctx)) {
    static int errCount = 0;
    if (errCount++ < 10) {
      fprintf(stderr,
              "[metalrender] nBeginGuiPass: failed to create pipeline\n");
    }
    return;
  }

  // Create GUI depth state (disable depth testing) if needed
  if (!guiDepthState) {
    MTLDepthStencilDescriptor *depthDesc =
        [[MTLDepthStencilDescriptor alloc] init];
    depthDesc.depthCompareFunction = MTLCompareFunctionAlways; // Always pass
    depthDesc.depthWriteEnabled = NO; // Don't write depth
    guiDepthState = [ctx->device newDepthStencilStateWithDescriptor:depthDesc];
    if (!guiDepthState) {
      printf("[MetalRender] Failed to create GUI depth stencil state\n");
      return;
    }
    printf("[MetalRender] Created GUI depth state (depth test disabled)\n");
  }

  // End any existing entity pass
  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];
    [ctx->currentEntityCommandBuffer commit];
    [ctx->currentEntityCommandBuffer waitUntilCompleted];
    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
  }

  // SINGLE-PASS GUI: Render directly to ioSurfaceTexture over terrain
  // This fixes GUI appearing behind terrain - GUI blends directly over existing
  // content
  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture =
      ctx->ioSurfaceTexture; // GUI renders DIRECTLY to final texture
  passDesc.colorAttachments[0].loadAction =
      MTLLoadActionLoad; // KEEP terrain content underneath
  passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
  // No clear - we're drawing OVER existing terrain

  // No depth attachment for GUI - GUI is always in front, no depth testing
  // needed
  passDesc.depthAttachment.texture = nil;

  // Create command buffer and encoder
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

  // Set up pipeline state - use GUI pipeline (no depth format) with GUI depth
  // state
  if (ctx->guiPipeline) {
    [ctx->currentEntityEncoder setRenderPipelineState:ctx->guiPipeline];
  } else {
    // Fallback - should not happen but log it
    printf("[metalrender] WARNING: guiPipeline not created, using "
           "entityPipeline\n");
    [ctx->currentEntityEncoder setRenderPipelineState:ctx->entityPipeline];
  }
  [ctx->currentEntityEncoder setDepthStencilState:guiDepthState];
  [ctx->currentEntityEncoder setCullMode:MTLCullModeNone];
  [ctx->currentEntityEncoder setFrontFacingWinding:MTLWindingCounterClockwise];

  // Set viewport
  MTLViewport viewport;
  viewport.originX = 0.0;
  viewport.originY = 0.0;
  viewport.width = static_cast<double>(ctx->ioSurfaceWidth);
  viewport.height = static_cast<double>(ctx->ioSurfaceHeight);
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [ctx->currentEntityEncoder setViewport:viewport];

  // Set up frame uniforms from passed data (orthographic for GUI)
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

  // No fog for GUI
  frameUniforms.fogColor[0] = 0.0f;
  frameUniforms.fogColor[1] = 0.0f;
  frameUniforms.fogColor[2] = 0.0f;
  frameUniforms.fogColor[3] = 0.0f;
  frameUniforms.fogParams[0] = 10000.0f; // Far fog start
  frameUniforms.fogParams[1] = 10001.0f; // Far fog end
  frameUniforms.fogParams[2] = 0.0f;
  frameUniforms.fogParams[3] = 0.0f;

  [ctx->currentEntityEncoder setVertexBytes:&frameUniforms
                                     length:sizeof(frameUniforms)
                                    atIndex:1];
  [ctx->currentEntityEncoder setFragmentBytes:&frameUniforms
                                       length:sizeof(frameUniforms)
                                      atIndex:1];

  ctx->entityPassActive = true;

  static int beginCount = 0;
  if (beginCount++ < 20) {
    printf("[metalrender] nBeginGuiPass: started GUI render pass (no depth)\n");
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEndGuiPass(JNIEnv *, jclass,
                                                           jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;

  if (ctx->entityPassActive && ctx->currentEntityEncoder) {
    [ctx->currentEntityEncoder endEncoding];

    // CRITICAL: Synchronize IOSurface texture to ensure GUI content is visible
    // to CPU Without this, the blit may read stale terrain-only data
    id<MTLBlitCommandEncoder> blitEncoder =
        [ctx->currentEntityCommandBuffer blitCommandEncoder];
    [blitEncoder synchronizeResource:ctx->ioSurfaceTexture];
    [blitEncoder endEncoding];

    [ctx->currentEntityCommandBuffer commit];

    // Store the GUI command buffer so nWaitForRender can sync on it
    ctx->lastGuiCommandBuffer = ctx->currentEntityCommandBuffer;

    ctx->currentEntityEncoder = nil;
    ctx->currentEntityCommandBuffer = nil;
    ctx->entityPassActive = false;

    // No longer need composite pass - GUI rendered directly to ioSurfaceTexture
    // ctx->guiNeedsComposite = true; // REMOVED - single-pass GUI

    static int endCount = 0;
    if (endCount++ < 20) {
      printf("[metalrender] nEndGuiPass: ended GUI render pass (direct to "
             "IOSurface, synchronized)\n");
    }
  }
}

// =========================================================================
// GUI Composite - Composite GUI texture over terrain (ioSurfaceTexture)
// =========================================================================

// Composite shader source - blends GUI over terrain with alpha
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
    
    // Standard alpha compositing: GUI over terrain
    // result = gui * gui.a + terrain * (1 - gui.a)
    float3 resultColor = gui.rgb * gui.a + terrain.rgb * (1.0 - gui.a);
    float resultAlpha = gui.a + terrain.a * (1.0 - gui.a);
    
    return float4(resultColor, resultAlpha);
}
)METAL";

static bool ensureCompositePipeline(MetalContext *ctx) {
  if (ctx->compositePipeline)
    return true;

  // Compile composite shader
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

  // Create pipeline
  MTLRenderPipelineDescriptor *pipelineDesc =
      [[MTLRenderPipelineDescriptor alloc] init];
  pipelineDesc.vertexFunction = vertexFunc;
  pipelineDesc.fragmentFunction = fragFunc;
  pipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  // No blending - fragment shader handles compositing
  pipelineDesc.colorAttachments[0].blendingEnabled = NO;

  ctx->compositePipeline =
      [ctx->device newRenderPipelineStateWithDescriptor:pipelineDesc
                                                  error:&error];
  if (!ctx->compositePipeline || error) {
    printf("[MetalRender] Failed to create composite pipeline: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  // Create fullscreen quad vertex buffer
  // Positions (x, y) and texture coords (u, v)
  float quadVerts[] = {
      -1.0f, -1.0f, 0.0f, 1.0f, // bottom-left
      1.0f,  -1.0f, 1.0f, 1.0f, // bottom-right
      -1.0f, 1.0f,  0.0f, 0.0f, // top-left
      1.0f,  -1.0f, 1.0f, 1.0f, // bottom-right
      1.0f,  1.0f,  1.0f, 0.0f, // top-right
      -1.0f, 1.0f,  0.0f, 0.0f, // top-left
  };
  ctx->compositeQuadVB =
      [ctx->device newBufferWithBytes:quadVerts
                               length:sizeof(quadVerts)
                              options:MTLResourceStorageModeShared];

  printf("[MetalRender] Created composite pipeline and quad buffer\n");
  return true;
}

// Called before readback to composite GUI over terrain
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nCompositeGui(JNIEnv *, jclass,
                                                             jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue)
    return;

  // Skip if no GUI was drawn this frame
  if (!ctx->guiNeedsComposite || !ctx->guiTexture) {
    return;
  }

  // Ensure composite pipeline
  if (!ensureCompositePipeline(ctx)) {
    printf("[MetalRender] nCompositeGui: failed to create pipeline\n");
    return;
  }

  // Create render pass to composite GUI over terrain into ioSurfaceTexture
  MTLRenderPassDescriptor *passDesc =
      [MTLRenderPassDescriptor renderPassDescriptor];
  passDesc.colorAttachments[0].texture = ctx->ioSurfaceTexture;
  passDesc.colorAttachments[0].loadAction = MTLLoadActionLoad; // Keep terrain
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
  [enc setFragmentTexture:ctx->ioSurfaceTexture atIndex:0]; // terrain
  [enc setFragmentTexture:ctx->guiTexture atIndex:1];       // GUI

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

  static int compCount = 0;
  if (compCount++ < 20) {
    printf("[metalrender] nCompositeGui: composited GUI over terrain\n");
  }
}
}
