#include "metalrender.h"
#import <Metal/Metal.h>
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

namespace {
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
  uint32_t maxIndirectCommands = 65536;
  uint32_t currentIndirectCount = 0;
  size_t persistentCapacity = 32 * 1024 * 1024;
  size_t persistentAlignment = 256;
  size_t persistentCursor = 0;
  std::string deviceName;
  bool hasViewProj = false;
  float viewProj[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  bool meshShadersSupported = false;
  float temporalJitterX = 0.0F;
  float temporalJitterY = 0.0F;
  float temporalBlend = 0.0F;
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
    fprintf(stderr, "[MetalRender] Failed to compile occlusion shader: %s\n",
            error ? [[error localizedDescription] UTF8String]
                  : "unknown error");
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
    fprintf(stderr, "[MetalRender] Failed to create occlusion pipeline: %s\n",
            error ? [[error localizedDescription] UTF8String]
                  : "unknown error");
    ctx->occlusionPipeline = nil;
    return false;
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
    fprintf(stderr, "[MetalRender] METALRENDER_ENABLE_METALFX=%s\n", rawValue);
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
      fprintf(stderr, "[MetalRender] MetalFX not supported by device: %s\n",
              [[device name] UTF8String]);
    }
    return deviceSupported;
  }
  fprintf(stderr, "[MetalRender] MetalFX requires macOS 13.0 or later\n");
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
    fprintf(stderr, "[MetalRender] destroyMetalFXResources called twice "
                    "without recreation; skipping\n");
    return;
  }
  ctx->metalFxDestroyed = true;
  fprintf(stderr, "[MetalRender] Destroying MetalFX resources (hadScaler=%s)\n",
          ctx->metalFxScaler ? "yes" : "no");
  ctx->metalFxInputWidth = 0;
  ctx->metalFxInputHeight = 0;
  ctx->metalFxOutputWidth = 0;
  ctx->metalFxOutputHeight = 0;
  ctx->metalFxResetHistory = false;
  if (ctx->metalFxScaler) {
    fprintf(stderr, "[MetalRender] Releasing metalFxScaler\n");
    ctx->metalFxScaler = nil;
  }
  if (ctx->metalFxColor) {
    fprintf(stderr, "[MetalRender] Releasing metalFxColor\n");
    ctx->metalFxColor = nil;
  }
  if (ctx->metalFxDepth) {
    fprintf(stderr, "[MetalRender] Releasing metalFxDepth\n");
    ctx->metalFxDepth = nil;
  }
  if (ctx->metalFxOutput) {
    fprintf(stderr, "[MetalRender] Releasing metalFxOutput\n");
    ctx->metalFxOutput = nil;
  }
  fprintf(stderr, "[MetalRender] MetalFX resources destroyed.\n");
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
        fprintf(stderr,
                "[MetalRender] MetalFX upscaling active: input=%ux%u "
                "output=%ux%u scale=%.3f (frame %llu)\n",
                inputWidth, inputHeight, outputWidth, outputHeight,
                clampedScale, metalFxFrameCounter);
      }
      return true;
    }

    destroyMetalFXResources(ctx);

    fprintf(stderr,
            "[MetalRender] Allocating MetalFX resources: output=%ux%u "
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
      fprintf(stderr,
              "[MetalRender] Failed to create MetalFX temporal scaler\n");
      return false;
    }

    ctx->metalFxScaler = scaler;
    ctx->metalFxInputWidth = inputWidth;
    ctx->metalFxInputHeight = inputHeight;
    ctx->metalFxOutputWidth = outputWidth;
    ctx->metalFxOutputHeight = outputHeight;
    ctx->metalFxResetHistory = true;
    fprintf(stderr,
            "[MetalRender] MetalFX resources successfully allocated and ready "
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
    JNIEnv *, jclass, jlong, jint, jint, jfloat) {}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginFrame(
    JNIEnv *, jclass, jlong handle, jfloatArray, jfloatArray, jfloat, jfloat) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->graphicsQueue || !ctx->persistentBuffer ||
      !ctx->indirectArgs)
    return;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain(JNIEnv *, jclass,
                                                            jlong, jint) {}
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
  if (ctx->indirectArgs) {
    memset([ctx->indirectArgs contents], 0, [ctx->indirectArgs length]);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nQueueIndirectDraw(
    JNIEnv *, jclass, jlong handle, jint commandIndex, jlong, jlong,
    jint indexCount, jint, jint, jint instanceCount, jint) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->indirectArgs)
    return;
  if (commandIndex < 0 ||
      commandIndex >= static_cast<jint>(ctx->maxIndirectCommands))
    return;
  struct IndirectArgs {
    uint32_t indexCount;
    uint32_t instanceCount;
    uint32_t indexStart;
    uint32_t baseVertex;
    uint32_t baseInstance;
  };
  IndirectArgs *args =
      reinterpret_cast<IndirectArgs *>([ctx->indirectArgs contents]);
  args[commandIndex].indexCount = static_cast<uint32_t>(indexCount);
  args[commandIndex].instanceCount =
      static_cast<uint32_t>(instanceCount > 0 ? instanceCount : 1);
  args[commandIndex].indexStart = 0;
  args[commandIndex].baseVertex = 0;
  args[commandIndex].baseInstance = 0;
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
    passDesc.colorAttachments[0].clearColor =
        MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
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
}
