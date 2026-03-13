#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl.h>

#include <atomic>
#include <dispatch/dispatch.h>
#include <jni.h>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

static FILE *g_debugFile = nullptr;
static void dbg(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void dbg(const char *fmt, ...) {
  if (!g_debugFile) {
    g_debugFile = fopen("/tmp/metalrender_debug.log", "w");
    if (!g_debugFile)
      return;
  }
  va_list args;
  va_start(args, fmt);
  vfprintf(g_debugFile, fmt, args);
  va_end(args);
  fflush(g_debugFile);
}

static id<MTLBuffer> get_buffer(uint64_t h);

#ifndef dispatch_get_active_cpu_count
static inline int dispatch_get_active_cpu_count() {
  unsigned hc = std::thread::hardware_concurrency();
  return (int)(hc == 0 ? 1 : hc);
}
#endif

static bool g_available = true;
static id<MTLDevice> g_device = nil;
static id<MTLCommandQueue> g_queue = nil;
static std::unordered_map<uint64_t, id<MTLBuffer>> g_buffers;
static uint64_t g_nextHandle = 1;
static id<MTLRenderPipelineState> g_pipelineOpaque = nil;
static id<MTLRenderPipelineState> g_pipelineInhouse = nil;
static id<MTLRenderPipelineState> g_pipelineCutout = nil;
static id<MTLRenderPipelineState> g_pipelineTranslucent = nil;
static id<MTLTexture> g_color = nil;
static id<MTLTexture> g_depth = nil;
static id<MTLTexture> g_blockAtlas = nil;
static IOSurfaceRef g_ioSurface = NULL;
static id<MTLLibrary> g_shaderLibrary = nil;
static id<MTLDepthStencilState> g_depthState = nil;
static int g_rtWidth = 16;
static int g_rtHeight = 16;
static float g_scale = 1.0f;
static int g_frameCount = 0;
static int g_drawCallCount = 0;
static int g_drawSkipCount = 0;
static int g_totalDraws = 0;

static void ensure_device() {
  if (!g_device) {
    g_device = MTLCreateSystemDefaultDevice();
    if (g_device) {
      g_queue = [g_device newCommandQueue];
    }
  }
}

static void load_shaders() {
  dbg("load_shaders() called: device=%p shaderLibrary=%p\n", g_device,
      g_shaderLibrary);
  if (!g_device || g_shaderLibrary)
    return;

  NSString *shaderSource = @R"(
#include <metal_stdlib>
using namespace metal;

struct InhouseTerrainVertex {
	packed_short3 position;
	packed_ushort2 texCoord;
	packed_uchar4 color;
	uchar packedLight;
	uchar normalIndex;
};

struct SimpleVertexOut {
	float4 position  [[position]];
	float2 texCoord;
	float4 color;
	float light;
};

vertex SimpleVertexOut vertex_terrain_inhouse(
	device const InhouseTerrainVertex* vertices   [[buffer(0)]],
	constant float4x4& projectionMatrix           [[buffer(1)]],
	constant float4x4& modelViewMatrix            [[buffer(2)]],
	constant float4& cameraPosition               [[buffer(3)]],
	constant float4& chunkOffset                  [[buffer(4)]],
	uint vid [[vertex_id]]
) {
	InhouseTerrainVertex v = vertices[vid];
	SimpleVertexOut out;

	float3 localPos = float3(short3(v.position)) / 256.0;
	float3 worldPos = localPos + chunkOffset.xyz;
	float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
	out.position = projectionMatrix * viewPos;

	out.texCoord = float2(v.texCoord) / 65535.0;
	out.color    = float4(v.color) / 255.0;
	out.light    = float(v.packedLight) / 255.0;
	return out;
}

fragment float4 fragment_terrain(
	SimpleVertexOut in [[stage_in]],
	texture2d<float> blockAtlas [[texture(0)]]
) {
	constexpr sampler s(filter::nearest, address::clamp_to_edge);
	float4 texColor = blockAtlas.sample(s, in.texCoord);

	float4 baseColor = texColor * in.color;
	float lightFactor = clamp(in.light * 0.8 + 0.2, 0.2, 1.0);
	baseColor.rgb *= lightFactor;

	if (texColor.a < 0.1) discard_fragment();
	return float4(baseColor.rgb, 1.0);
}
	)";

  NSError *error = nil;
  MTLCompileOptions *opts = [[MTLCompileOptions alloc] init];
  g_shaderLibrary = [g_device newLibraryWithSource:shaderSource
                                           options:opts
                                             error:&error];
  dbg("newLibraryWithSource returned: lib=%p error=%p\n", g_shaderLibrary,
      error);
  if (!g_shaderLibrary) {
    dbg("FATAL: Shader compilation failed: %s\n",
        error ? [[error localizedDescription] UTF8String] : "unknown");
    return;
  }
  dbg("Shader library compiled OK\n");

  id<MTLFunction> vertexFn =
      [g_shaderLibrary newFunctionWithName:@"vertex_terrain_inhouse"];
  id<MTLFunction> fragmentFn =
      [g_shaderLibrary newFunctionWithName:@"fragment_terrain"];
  if (vertexFn && fragmentFn) {
    MTLRenderPipelineDescriptor *desc =
        [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vertexFn;
    desc.fragmentFunction = fragmentFn;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    desc.colorAttachments[0].blendingEnabled = NO;
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    desc.label = @"InhouseTerrain";

    g_pipelineInhouse = [g_device newRenderPipelineStateWithDescriptor:desc
                                                                 error:&error];
    if (!g_pipelineInhouse) {
      dbg("FATAL: Pipeline creation failed: %s\n",
          [[error localizedDescription] UTF8String]);
    }

    g_pipelineOpaque = g_pipelineInhouse;
  }

  MTLDepthStencilDescriptor *dsDesc = [[MTLDepthStencilDescriptor alloc] init];
  dsDesc.depthCompareFunction = MTLCompareFunctionLess;
  dsDesc.depthWriteEnabled = YES;
  g_depthState = [g_device newDepthStencilStateWithDescriptor:dsDesc];

  if (!g_blockAtlas) {
    MTLTextureDescriptor *fallbackDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                     width:1
                                    height:1
                                 mipmapped:NO];
    fallbackDesc.usage = MTLTextureUsageShaderRead;
    fallbackDesc.storageMode = MTLStorageModeShared;
    g_blockAtlas = [g_device newTextureWithDescriptor:fallbackDesc];
    uint8_t white[4] = {255, 255, 255, 255};
    [g_blockAtlas replaceRegion:MTLRegionMake2D(0, 0, 1, 1)
                    mipmapLevel:0
                      withBytes:white
                    bytesPerRow:4];
    dbg("Created 1x1 white fallback atlas texture\n");
  }

  dbg("Shaders compiled OK, pipeline inhouse=%p opaque=%p depth=%p\n",
      g_pipelineInhouse, g_pipelineOpaque, g_depthState);
}

static void ensure_offscreen() {
  if (!g_device)
    return;
  int w = std::max(1, (int)(g_rtWidth * g_scale));
  int h = std::max(1, (int)(g_rtHeight * g_scale));
  bool recreate =
      (!g_color) || ((int)g_color.width != w) || ((int)g_color.height != h);
  if (!recreate)
    return;

  if (g_ioSurface) {
    CFRelease(g_ioSurface);
    g_ioSurface = NULL;
  }

  NSUInteger bytesPerRow = ((w * 4) + 15) & ~15;
  NSDictionary *surfaceProperties = @{
    (id)kIOSurfaceWidth : @(w),
    (id)kIOSurfaceHeight : @(h),
    (id)kIOSurfaceBytesPerElement : @4,
    (id)kIOSurfaceBytesPerRow : @(bytesPerRow),
    (id)kIOSurfaceAllocSize : @(bytesPerRow * h),
    (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
  };
  g_ioSurface = IOSurfaceCreate((__bridge CFDictionaryRef)surfaceProperties);

  MTLTextureDescriptor *cd = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                   width:w
                                  height:h
                               mipmapped:NO];
  cd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  cd.storageMode = MTLStorageModeShared;
  if (g_ioSurface) {
    g_color = [g_device newTextureWithDescriptor:cd
                                       iosurface:g_ioSurface
                                           plane:0];
  }
  if (!g_color) {

    cd.storageMode = MTLStorageModeShared;
    g_color = [g_device newTextureWithDescriptor:cd];
  }

  MTLTextureDescriptor *dd = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                   width:w
                                  height:h
                               mipmapped:NO];
  dd.usage = MTLTextureUsageRenderTarget;
  dd.storageMode = MTLStorageModePrivate;
  g_depth = [g_device newTextureWithDescriptor:dd];
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsAvailable(
    JNIEnv *, jclass) {
  ensure_device();
  return (g_available && g_device) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nInit(
    JNIEnv *, jclass, jint width, jint height, jfloat scale) {

  ensure_device();
  g_rtWidth = (int)width;
  g_rtHeight = (int)height;
  g_scale = scale;
  ensure_offscreen();
  load_shaders();
  return (g_device != nil) ? (jlong)0x1 : (jlong)0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResize(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {
  (void)handle;
  g_rtWidth = (int)width;
  g_rtHeight = (int)height;
  g_scale = scale;
  ensure_offscreen();
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBeginFrame(
    JNIEnv *, jclass, jlong handle, jfloatArray proj, jfloatArray view,
    jfloat fogStart, jfloat fogEnd) {
  (void)handle;
  (void)proj;
  (void)view;
  (void)fogStart;
  (void)fogEnd;
  ensure_device();
  ensure_offscreen();
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawTerrain(
    JNIEnv *, jclass, jlong handle, jint layerId) {}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawOverlay(
    JNIEnv *, jclass, jlong handle, jint layerId) {}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nOnWorldLoaded(
    JNIEnv *, jclass, jlong handle) {}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nOnWorldUnloaded(
    JNIEnv *, jclass, jlong handle) {}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroy(
    JNIEnv *, jclass, jlong handle) {}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDeviceName(
    JNIEnv *env, jclass) {
  ensure_device();
  if (!g_device)
    return env->NewStringUTF("unknown");
  NSString *name = [g_device name];
  return env->NewStringUTF([name UTF8String]);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSupportsIndirect(
    JNIEnv *, jclass) {
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSupportsMeshShaders(
    JNIEnv *, jclass) {
  return JNI_TRUE;
}

static std::mutex g_bufferMutex;

static uint64_t store_buffer(id<MTLBuffer> buf) {
  if (!buf)
    return 0;
  std::lock_guard<std::mutex> lock(g_bufferMutex);
  uint64_t h = g_nextHandle++;
  g_buffers[h] = buf;
  return h;
}

static id<MTLBuffer> get_buffer(uint64_t h) {
  std::lock_guard<std::mutex> lock(g_bufferMutex);
  auto it = g_buffers.find(h);
  if (it == g_buffers.end())
    return nil;
  return it->second;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_initNative(
    JNIEnv *, jclass, jlong windowHandle, jboolean someFlag) {
  (void)windowHandle;
  (void)someFlag;
  ensure_device();
  return (g_device != nil) ? (jlong)0xBEEF : (jlong)0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_uploadStaticMesh(
    JNIEnv *env, jclass, jlong handle, jobject vertexData, jint vertexCount,
    jint stride) {
  (void)handle;
  (void)vertexCount;
  (void)stride;
  if (!vertexData)
    return;
  void *ptr = env->GetDirectBufferAddress(vertexData);
  jlong cap = env->GetDirectBufferCapacity(vertexData);
  (void)ptr;
  (void)cap;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_resize(
    JNIEnv *, jclass, jlong handle, jint width, jint height) {
  (void)handle;
  (void)width;
  (void)height;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_setCamera(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj4x4) {
  (void)handle;
  if (!viewProj4x4)
    return;
  jfloat tmp[16];
  if (env->GetArrayLength(viewProj4x4) >= 16) {
    env->GetFloatArrayRegion(viewProj4x4, 0, 16, tmp);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_render(
    JNIEnv *, jclass, jlong handle, jfloat timeSeconds) {
  (void)handle;
  (void)timeSeconds;
  ensure_device();
  ensure_offscreen();
  if (!g_device || !g_queue || !g_color || !g_depth)
    return;
  @autoreleasepool {
    id<MTLCommandBuffer> cb = [g_queue commandBuffer];
    MTLRenderPassDescriptor *rp =
        [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = g_color;
    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
    rp.depthAttachment.texture = g_depth;
    rp.depthAttachment.loadAction = MTLLoadActionClear;
    rp.depthAttachment.storeAction = MTLStoreActionDontCare;
    rp.depthAttachment.clearDepth = 1.0;

    id<MTLRenderCommandEncoder> enc =
        [cb renderCommandEncoderWithDescriptor:rp];
    [enc endEncoding];
    [cb commit];
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_createVertexBuffer(
    JNIEnv *env, jclass, jlong handle, jobject data, jint size) {
  (void)handle;
  ensure_device();
  if (!g_device || !data || size <= 0)
    return 0;
  void *ptr = env->GetDirectBufferAddress(data);
  jlong cap = env->GetDirectBufferCapacity(data);
  if (!ptr || cap < size)
    return 0;
  id<MTLBuffer> buf =
      [g_device newBufferWithLength:(size_t)size
                            options:MTLResourceStorageModeShared];
  memcpy([buf contents], ptr, (size_t)size);
  uint64_t h = store_buffer(buf);
  return (jlong)h;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_createIndexBuffer(
    JNIEnv *env, jclass, jlong handle, jobject data, jint size) {
  (void)handle;
  ensure_device();
  if (!g_device || !data || size <= 0)
    return 0;
  void *ptr = env->GetDirectBufferAddress(data);
  jlong cap = env->GetDirectBufferCapacity(data);
  if (!ptr || cap < size)
    return 0;
  id<MTLBuffer> buf =
      [g_device newBufferWithLength:(size_t)size
                            options:MTLResourceStorageModeShared];
  memcpy([buf contents], ptr, (size_t)size);
  uint64_t h = store_buffer(buf);
  return (jlong)h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_destroyBuffer(
    JNIEnv *, jclass, jlong handle, jlong bufferHandle) {
  (void)handle;
  std::lock_guard<std::mutex> lock(g_bufferMutex);
  auto it = g_buffers.find((uint64_t)bufferHandle);
  if (it != g_buffers.end()) {
    g_buffers.erase(it);
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateBuffer(
    JNIEnv *, jclass, jlong deviceHandle, jint sizeBytes, jint storageMode) {
  (void)deviceHandle;
  (void)storageMode;
  ensure_device();
  if (!g_device || sizeBytes <= 0)
    return 0;
  id<MTLBuffer> buf =
      [g_device newBufferWithLength:(size_t)sizeBytes
                            options:MTLResourceStorageModeShared];
  return (jlong)store_buffer(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadBufferData(
    JNIEnv *env, jclass, jlong bufferHandle, jbyteArray data, jint offset,
    jint length) {
  id<MTLBuffer> buf = get_buffer((uint64_t)bufferHandle);
  if (!buf || !data)
    return;
  jbyte *bytes = env->GetByteArrayElements(data, nullptr);
  if (bytes && length > 0 && (size_t)(offset + length) <= [buf length]) {
    memcpy((uint8_t *)[buf contents] + offset, bytes, (size_t)length);
  }
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyBuffer(
    JNIEnv *, jclass, jlong bufferHandle) {
  std::lock_guard<std::mutex> lock(g_bufferMutex);
  auto it = g_buffers.find((uint64_t)bufferHandle);
  if (it != g_buffers.end()) {
    g_buffers.erase(it);
  }
}

static id<MTLRenderPipelineState> g_currentPipeline = nil;
static float g_chunkOffsetX = 0, g_chunkOffsetY = 0, g_chunkOffsetZ = 0;
static float g_projMatrix[16] = {};
static float g_mvMatrix[16] = {};
static double g_camX = 0, g_camY = 0, g_camZ = 0;
static id<MTLRenderCommandEncoder> g_currentEncoder = nil;
static id<MTLCommandBuffer> g_currentCmdBuffer = nil;

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetPipelineState(
    JNIEnv *, jclass, jlong frameContext, jlong pipelineHandle) {
  (void)frameContext;
  if (g_currentEncoder && pipelineHandle != 0) {
    id<MTLRenderPipelineState> pipeline =
        (__bridge id<MTLRenderPipelineState>)(void *)(uintptr_t)pipelineHandle;
    [g_currentEncoder setRenderPipelineState:pipeline];
    g_currentPipeline = pipeline;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  } else if (g_currentEncoder && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetChunkOffset(
    JNIEnv *, jclass, jlong frameContext, jfloat x, jfloat y, jfloat z) {
  (void)frameContext;
  g_chunkOffsetX = x;
  g_chunkOffsetY = y;
  g_chunkOffsetZ = z;
  if (g_currentEncoder) {
    float offset[4] = {x, y, z, 0.0f};
    [g_currentEncoder setVertexBytes:offset length:sizeof(offset) atIndex:4];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawIndexedBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jlong indexBuffer,
    jint indexCount, jint baseIndex) {
  (void)frameContext;
  if (!g_currentEncoder || indexCount <= 0) {
    g_drawSkipCount++;
    return;
  }
  id<MTLBuffer> vb = get_buffer((uint64_t)vertexBuffer);
  id<MTLBuffer> ib = get_buffer((uint64_t)indexBuffer);
  if (!vb || !ib)
    return;

  g_totalDraws++;
  if (g_totalDraws <= 3) {
    const short *vtx = (const short *)[vb contents];
    const uint32_t *idx = (const uint32_t *)[ib contents];
    dbg("Draw #%d: vb=%p(%luB) ib=%p(%luB) idxCount=%d baseIdx=%d\n",
        g_totalDraws, vb, (unsigned long)[vb length], ib,
        (unsigned long)[ib length], indexCount, baseIndex);
    dbg("  First vertex (shorts): %d %d %d %d %d | bytes: %d %d %d %d | %d "
        "%d\n",
        vtx[0], vtx[1], vtx[2], vtx[3], vtx[4], ((const uint8_t *)vtx)[10],
        ((const uint8_t *)vtx)[11], ((const uint8_t *)vtx)[12],
        ((const uint8_t *)vtx)[13], ((const uint8_t *)vtx)[14],
        ((const uint8_t *)vtx)[15]);
    dbg("  First indices: %u %u %u %u %u %u\n", idx[0], idx[1], idx[2], idx[3],
        idx[4], idx[5]);
    dbg("  ChunkOffset: %.2f %.2f %.2f\n", g_chunkOffsetX, g_chunkOffsetY,
        g_chunkOffsetZ);
  }

  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;

  [g_currentEncoder setVertexBuffer:vb offset:0 atIndex:0];
  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)baseIndex * sizeof(uint32_t)];
  g_drawCallCount++;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jint vertexCount,
    jint baseVertex) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0)
    return;
  id<MTLBuffer> vb = get_buffer((uint64_t)vertexBuffer);
  if (!vb)
    return;
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  [g_currentEncoder setVertexBuffer:vb offset:0 atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:(NSUInteger)baseVertex
                       vertexCount:(NSUInteger)vertexCount];
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetCurrentFrameContext(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_device || !g_queue || !g_color || !g_depth)
    return 0;

  if (g_currentEncoder)
    return (jlong)0x1;

  @autoreleasepool {
    g_currentCmdBuffer = [g_queue commandBuffer];
    MTLRenderPassDescriptor *rp =
        [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = g_color;
    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;

    rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    rp.depthAttachment.texture = g_depth;
    rp.depthAttachment.loadAction = MTLLoadActionClear;
    rp.depthAttachment.storeAction = MTLStoreActionDontCare;
    rp.depthAttachment.clearDepth = 1.0;

    g_currentEncoder =
        [g_currentCmdBuffer renderCommandEncoderWithDescriptor:rp];
    g_currentPipeline = nil;

    MTLViewport vp;
    vp.originX = 0;
    vp.originY = 0;
    vp.width = (double)g_color.width;
    vp.height = (double)g_color.height;
    vp.znear = 0.0;
    vp.zfar = 1.0;
    [g_currentEncoder setViewport:vp];

    [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    float camPos[4] = {(float)g_camX, (float)g_camY, (float)g_camZ, 0.0f};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
    float chunkOff[4] = {0, 0, 0, 0};
    [g_currentEncoder setVertexBytes:chunkOff length:16 atIndex:4];

    [g_currentEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
    [g_currentEncoder setCullMode:MTLCullModeBack];

    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }

    if (g_frameCount < 3) {
      dbg("Matrices at frame start:\n");
      dbg("  Proj: [%.4f %.4f %.4f %.4f] [%.4f %.4f %.4f %.4f] [%.4f %.4f %.4f "
          "%.4f] [%.4f %.4f %.4f %.4f]\n",
          g_projMatrix[0], g_projMatrix[1], g_projMatrix[2], g_projMatrix[3],
          g_projMatrix[4], g_projMatrix[5], g_projMatrix[6], g_projMatrix[7],
          g_projMatrix[8], g_projMatrix[9], g_projMatrix[10], g_projMatrix[11],
          g_projMatrix[12], g_projMatrix[13], g_projMatrix[14],
          g_projMatrix[15]);
      dbg("  MV:   [%.4f %.4f %.4f %.4f] [%.4f %.4f %.4f %.4f] [%.4f %.4f %.4f "
          "%.4f] [%.4f %.4f %.4f %.4f]\n",
          g_mvMatrix[0], g_mvMatrix[1], g_mvMatrix[2], g_mvMatrix[3],
          g_mvMatrix[4], g_mvMatrix[5], g_mvMatrix[6], g_mvMatrix[7],
          g_mvMatrix[8], g_mvMatrix[9], g_mvMatrix[10], g_mvMatrix[11],
          g_mvMatrix[12], g_mvMatrix[13], g_mvMatrix[14], g_mvMatrix[15]);
      dbg("  Cam: %.2f %.2f %.2f\n", g_camX, g_camY, g_camZ);
    }
  }
  return (jlong)0x1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nEndFrame(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  g_frameCount++;
  if (g_frameCount <= 5 || g_frameCount % 500 == 0) {
    dbg("EndFrame #%d: draws=%d skips=%d encoder=%p pipeline=%p "
        "pipelineInhouse=%p texture=%dx%d proj[0]=%.3f mv[0]=%.3f "
        "cam=%.1f,%.1f,%.1f\n",
        g_frameCount, g_drawCallCount, g_drawSkipCount, g_currentEncoder,
        g_currentPipeline, g_pipelineInhouse, g_color ? (int)g_color.width : 0,
        g_color ? (int)g_color.height : 0, g_projMatrix[0], g_mvMatrix[0],
        g_camX, g_camY, g_camZ);
  }
  g_drawCallCount = 0;
  g_drawSkipCount = 0;
  if (g_currentEncoder) {
    [g_currentEncoder endEncoding];
    g_currentEncoder = nil;
  }
  if (g_currentCmdBuffer) {
    [g_currentCmdBuffer commit];
    [g_currentCmdBuffer waitUntilCompleted];
    g_currentCmdBuffer = nil;
  }
  g_currentPipeline = nil;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetProjectionMatrix(
    JNIEnv *env, jclass, jlong handle, jfloatArray matrix) {
  (void)handle;
  if (matrix && env->GetArrayLength(matrix) >= 16) {
    env->GetFloatArrayRegion(matrix, 0, 16, g_projMatrix);
    if (g_currentEncoder) {
      [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetModelViewMatrix(
    JNIEnv *env, jclass, jlong handle, jfloatArray matrix) {
  (void)handle;
  if (matrix && env->GetArrayLength(matrix) >= 16) {
    env->GetFloatArrayRegion(matrix, 0, 16, g_mvMatrix);
    if (g_currentEncoder) {
      [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetCameraPosition(
    JNIEnv *, jclass, jlong handle, jdouble x, jdouble y, jdouble z) {
  (void)handle;
  g_camX = x;
  g_camY = y;
  g_camZ = z;
  if (g_currentEncoder) {
    float camPos[4] = {(float)x, (float)y, (float)z, 0.0f};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindTexture(
    JNIEnv *, jclass, jlong handle, jlong textureHandle, jint slot) {
  (void)handle;
  if (textureHandle == 0)
    return;
  id<MTLTexture> tex =
      (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (slot == 0) {
    g_blockAtlas = tex;
  }

  if (g_currentEncoder && tex) {
    [g_currentEncoder setFragmentTexture:tex atIndex:(NSUInteger)slot];
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateTexture2D(
    JNIEnv *env, jclass, jlong deviceHandle, jint width, jint height,
    jbyteArray pixelData) {
  (void)deviceHandle;
  if (!g_device || width <= 0 || height <= 0)
    return 0;

  MTLTextureDescriptor *desc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                   width:(NSUInteger)width
                                  height:(NSUInteger)height
                               mipmapped:NO];
  desc.usage = MTLTextureUsageShaderRead;
  desc.storageMode = MTLStorageModeShared;

  id<MTLTexture> tex = [g_device newTextureWithDescriptor:desc];
  if (!tex) {
    dbg("nCreateTexture2D: failed to create %dx%d texture\n", width, height);
    return 0;
  }

  jbyte *data = env->GetByteArrayElements(pixelData, NULL);
  if (data) {
    MTLRegion region =
        MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);
    [tex replaceRegion:region
           mipmapLevel:0
             withBytes:data
           bytesPerRow:(NSUInteger)(width * 4)];
    env->ReleaseByteArrayElements(pixelData, data, JNI_ABORT);
  }

  dbg("nCreateTexture2D: created %dx%d RGBA texture %p\n", width, height, tex);

  return (jlong)(uintptr_t)(__bridge_retained void *)tex;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDeviceHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_device;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetShaderLibraryHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetInhousePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineOpaque;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDefaultPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineOpaque;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGLTextureId(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIOSurfaceWidth(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return g_rtWidth;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIOSurfaceHeight(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return g_rtHeight;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nWaitForRender(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindIOSurfaceToTexture(
    JNIEnv *, jclass, jlong handle, jint glTexture) {
  (void)handle;
  if (!g_ioSurface || glTexture == 0 || g_rtWidth <= 0 || g_rtHeight <= 0)
    return JNI_FALSE;

  CGLContextObj cglCtx = CGLGetCurrentContext();
  if (!cglCtx)
    return JNI_FALSE;

  int w = std::max(1, (int)(g_rtWidth * g_scale));
  int h = std::max(1, (int)(g_rtHeight * g_scale));

  glBindTexture(GL_TEXTURE_RECTANGLE_ARB, (GLuint)glTexture);
  CGLError err = CGLTexImageIOSurface2D(
      cglCtx, GL_TEXTURE_RECTANGLE_ARB, GL_RGBA, (GLsizei)w, (GLsizei)h,
      GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, g_ioSurface, 0);
  glBindTexture(GL_TEXTURE_RECTANGLE_ARB, 0);

  return (err == kCGLNoError) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReadbackPixels(
    JNIEnv *env, jclass, jlong handle, jobject dest) {
  (void)handle;
  if (!g_color || !dest || !env)
    return JNI_FALSE;

  void *destPtr = env->GetDirectBufferAddress(dest);
  if (!destPtr)
    return JNI_FALSE;

  int w = (int)g_color.width;
  int h = (int)g_color.height;
  jlong capacity = env->GetDirectBufferCapacity(dest);
  if (capacity < (jlong)(w * h * 4))
    return JNI_FALSE;

  [g_color getBytes:destPtr
        bytesPerRow:(NSUInteger)(w * 4)
         fromRegion:MTLRegionMake2D(0, 0, w, h)
        mipmapLevel:0];

  return JNI_TRUE;
}
