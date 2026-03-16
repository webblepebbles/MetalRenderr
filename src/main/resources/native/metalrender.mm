#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl.h>

#include <atomic>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <jni.h>
#include <mutex>
#include <shared_mutex>
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
static id<MTLRenderPipelineState> g_pipelineEntity = nil;
static id<MTLRenderPipelineState> g_pipelineEntityInstanced = nil;
static id<MTLRenderPipelineState> g_pipelineEntityTranslucent = nil;
static id<MTLRenderPipelineState> g_pipelineEntityEmissive = nil;
static id<MTLRenderPipelineState> g_pipelineEntityOutline = nil;
static id<MTLRenderPipelineState> g_pipelineEntityShadow = nil;
static id<MTLRenderPipelineState> g_pipelineParticle = nil;
static id<MTLRenderPipelineState> g_pipelineDebugLines = nil;
static id<MTLDepthStencilState> g_depthState = nil;
static id<MTLDepthStencilState> g_depthStateNoWrite = nil;
static id<MTLDepthStencilState> g_depthStateLessEqual = nil;
static id<MTLTexture> g_color = nil;
static id<MTLTexture> g_depth = nil;
static id<MTLBuffer> g_depthReadBuffer = nil;
static id<MTLCommandBuffer> g_depthCmdBuffer = nil;
static id<MTLTexture> g_blockAtlas = nil;
static IOSurfaceRef g_ioSurface = NULL;
static id<MTLLibrary> g_shaderLibrary = nil;
static int g_rtWidth = 16;
static int g_rtHeight = 16;
static float g_scale = 1.0f;
static int g_frameCount = 0;
static int g_drawCallCount = 0;
static int g_drawSkipCount = 0;
static int g_totalDraws = 0;
static id<MTLTexture> g_entityTexture = nil;
static float g_entityOverlayParams[4] = {0, 0, 0, 1};
static float g_entityTintColor[4] = {1, 1, 1, 1};

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

  NSError *error = nil;
  NSArray<NSString *> *searchPaths = @[
    @"src/main/resources/shaders.metallib",
    [NSString
        stringWithFormat:@"%@/shaders.metallib",
                         [[NSFileManager defaultManager] currentDirectoryPath]],
    @"shaders.metallib",
  ];

  Dl_info dlInfo;
  if (dladdr((void *)load_shaders, &dlInfo) && dlInfo.dli_fname) {
    NSString *dylibPath = [[NSString stringWithUTF8String:dlInfo.dli_fname]
        stringByDeletingLastPathComponent];
    NSString *metallibPath =
        [dylibPath stringByAppendingPathComponent:@"shaders.metallib"];
    searchPaths = [searchPaths arrayByAddingObject:metallibPath];
  }

  for (NSString *path in searchPaths) {
    if ([[NSFileManager defaultManager] fileExistsAtPath:path]) {
      NSURL *url = [NSURL fileURLWithPath:path];
      g_shaderLibrary = [g_device newLibraryWithURL:url error:&error];
      if (g_shaderLibrary) {
        dbg("Loaded metallib from: %s\n", [path UTF8String]);
        break;
      } else {
        dbg("Failed to load metallib from %s: %s\n", [path UTF8String],
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
  }

  if (!g_shaderLibrary) {
    dbg("No metallib found, falling back to inline shader compilation\n");
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


struct EntityVertex {
	packed_float3 position;
	packed_short2 texCoord;
	packed_uchar4 color;
	packed_uchar4 normal;
	packed_short2 overlay;
	packed_short2 lightUV;
};

struct EntityVertexOut {
	float4 position  [[position]];
	float2 texCoord;
	float4 color;
	float3 normal;
	float2 lightUV;
	float2 overlay;
	float3 worldPos;
};

vertex EntityVertexOut vertex_entity(
	device const EntityVertex*     vertices    [[buffer(0)]],
	constant float4x4&             projection  [[buffer(1)]],
	constant float4x4&             modelView   [[buffer(2)]],
	uint vid [[vertex_id]]
) {
	EntityVertex v = vertices[vid];
	EntityVertexOut out;
	float3 pos      = float3(v.position);
	float4 viewPos  = modelView * float4(pos, 1.0);
	out.position = projection * viewPos;
	out.texCoord = float2(v.texCoord) / 32768.0;
	out.color    = float4(v.color) / 255.0;
	out.normal   = normalize(float3(v.normal.xyz) / 127.0 - 1.0);
	out.lightUV  = float2(v.lightUV) / 256.0;
	out.overlay  = float2(v.overlay.x, v.overlay.y);
	out.worldPos = pos;
	return out;
}

fragment float4 fragment_entity(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	if (texColor.a < 0.1) discard_fragment();
	float4 finalColor = texColor * in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	float ambient   = 0.4;
	finalColor.rgb *= (ambient + (1.0 - ambient) * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y, 0.0, 1.0);
	float lightLevel = max(max(blockLight, skyLight), 0.5);
	finalColor.rgb *= lightLevel;
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		finalColor.rgb = mix(finalColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}
	float whiteFlash = overlayParams.y;
	if (whiteFlash > 0.0) {
		finalColor.rgb = mix(finalColor.rgb, float3(1.0),
			clamp(whiteFlash, 0.0, 1.0));
	}
	return finalColor;
}

fragment float4 fragment_entity_translucent(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler texSampler(filter::linear, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	if (texColor.a < 0.004) discard_fragment();
	float4 finalColor = texColor * in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	finalColor.rgb *= (0.4 + 0.6 * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y, 0.0, 1.0);
	float lightLevel = max(max(blockLight, skyLight), 0.5);
	finalColor.rgb *= lightLevel;
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		finalColor.rgb = mix(finalColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}
	return finalColor;
}

fragment float4 fragment_entity_emissive(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]]
) {
	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	if (texColor.a < 0.1) discard_fragment();
	return texColor * in.color;
}
	)";

    MTLCompileOptions *opts = [[MTLCompileOptions alloc] init];
    g_shaderLibrary = [g_device newLibraryWithSource:shaderSource
                                             options:opts
                                               error:&error];
    if (!g_shaderLibrary) {
      dbg("FATAL: Shader compilation failed: %s\n",
          error ? [[error localizedDescription] UTF8String] : "unknown");
      return;
    }
    dbg("Inline shader compilation OK\n");
  }

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
      dbg("FATAL: Terrain pipeline creation failed: %s\n",
          [[error localizedDescription] UTF8String]);
    }
    g_pipelineOpaque = g_pipelineInhouse;
  }

  auto createEntityPipeline = [&](NSString *vertName, NSString *fragName,
                                  NSString *label,
                                  bool blending) -> id<MTLRenderPipelineState> {
    id<MTLFunction> vf = [g_shaderLibrary newFunctionWithName:vertName];
    id<MTLFunction> ff = [g_shaderLibrary newFunctionWithName:fragName];
    if (!vf || !ff) {
      dbg("Entity pipeline '%s': missing function (vert=%p frag=%p)\n",
          [label UTF8String], vf, ff);
      return nil;
    }
    MTLRenderPipelineDescriptor *pd =
        [[MTLRenderPipelineDescriptor alloc] init];
    pd.vertexFunction = vf;
    pd.fragmentFunction = ff;
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    pd.label = label;

    if (blending) {
      pd.colorAttachments[0].blendingEnabled = YES;
      pd.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      pd.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
      pd.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      pd.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
    } else {
      pd.colorAttachments[0].blendingEnabled = NO;
    }

    id<MTLRenderPipelineState> ps =
        [g_device newRenderPipelineStateWithDescriptor:pd error:&error];
    if (!ps) {
      dbg("Entity pipeline '%s' creation failed: %s\n", [label UTF8String],
          error ? [[error localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("Entity pipeline '%s' created OK\n", [label UTF8String]);
    }
    return ps;
  };

  g_pipelineEntity = createEntityPipeline(@"vertex_entity", @"fragment_entity",
                                          @"EntityOpaque", false);
  g_pipelineEntityTranslucent =
      createEntityPipeline(@"vertex_entity", @"fragment_entity_translucent",
                           @"EntityTranslucent", true);
  g_pipelineEntityEmissive = createEntityPipeline(
      @"vertex_entity", @"fragment_entity_emissive", @"EntityEmissive", true);

  g_pipelineEntityInstanced =
      createEntityPipeline(@"vertex_entity_instanced", @"fragment_entity",
                           @"EntityInstanced", false);

  id<MTLFunction> outlineFragFn =
      [g_shaderLibrary newFunctionWithName:@"fragment_entity_outline"];
  if (outlineFragFn) {
    g_pipelineEntityOutline = createEntityPipeline(
        @"vertex_entity", @"fragment_entity_outline", @"EntityOutline", true);
  }

  g_pipelineEntityShadow = createEntityPipeline(
      @"vertex_entity", @"fragment_entity_shadow", @"EntityShadow", true);



  g_pipelineParticle = createEntityPipeline(
      @"vertex_entity", @"fragment_particle", @"Particle", true);

  {
    id<MTLFunction> dbgVert =
        [g_shaderLibrary newFunctionWithName:@"vertex_debug"];
    id<MTLFunction> dbgFrag =
        [g_shaderLibrary newFunctionWithName:@"fragment_debug"];
    if (dbgVert && dbgFrag) {
      MTLRenderPipelineDescriptor *dbgDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      dbgDesc.vertexFunction = dbgVert;
      dbgDesc.fragmentFunction = dbgFrag;
      dbgDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      dbgDesc.colorAttachments[0].blendingEnabled = YES;
      dbgDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      dbgDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      dbgDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      dbgDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      dbgDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      dbgDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      dbgDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      dbgDesc.label = @"DebugLines";
      NSError *dbgErr = nil;
      g_pipelineDebugLines =
          [g_device newRenderPipelineStateWithDescriptor:dbgDesc error:&dbgErr];
      if (!g_pipelineDebugLines) {
        dbg("Debug line pipeline creation failed: %s\n",
            dbgErr ? [[dbgErr localizedDescription] UTF8String] : "unknown");
      } else {
        dbg("Debug line pipeline created OK\n");
      }
    }
  }

  MTLDepthStencilDescriptor *dsDesc = [[MTLDepthStencilDescriptor alloc] init];
  dsDesc.depthCompareFunction = MTLCompareFunctionLess;
  dsDesc.depthWriteEnabled = YES;
  g_depthState = [g_device newDepthStencilStateWithDescriptor:dsDesc];

  MTLDepthStencilDescriptor *dsNoWrite =
      [[MTLDepthStencilDescriptor alloc] init];
  dsNoWrite.depthCompareFunction = MTLCompareFunctionLess;
  dsNoWrite.depthWriteEnabled = NO;
  g_depthStateNoWrite = [g_device newDepthStencilStateWithDescriptor:dsNoWrite];

  MTLDepthStencilDescriptor *dsLessEq =
      [[MTLDepthStencilDescriptor alloc] init];
  dsLessEq.depthCompareFunction = MTLCompareFunctionLessEqual;
  dsLessEq.depthWriteEnabled = NO;
  g_depthStateLessEqual =
      [g_device newDepthStencilStateWithDescriptor:dsLessEq];

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

  dbg("Shaders loaded: terrain inhouse=%p opaque=%p entity=%p "
      "entityTranslucent=%p entityEmissive=%p depth=%p\n",
      g_pipelineInhouse, g_pipelineOpaque, g_pipelineEntity,
      g_pipelineEntityTranslucent, g_pipelineEntityEmissive, g_depthState);
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


  NSUInteger depthBufSize = (NSUInteger)(w * h * 4);
  if (!g_depthReadBuffer || g_depthReadBuffer.length < depthBufSize) {
    g_depthReadBuffer =
        [g_device newBufferWithLength:depthBufSize
                              options:MTLResourceStorageModeShared];
  }
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

static std::shared_mutex g_bufferMutex;

static uint64_t store_buffer(id<MTLBuffer> buf) {
  if (!buf)
    return 0;
  std::unique_lock<std::shared_mutex> lock(g_bufferMutex);
  uint64_t h = g_nextHandle++;
  g_buffers[h] = buf;
  return h;
}

static id<MTLBuffer> get_buffer(uint64_t h) {
  std::shared_lock<std::shared_mutex> lock(g_bufferMutex);
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
    rp.depthAttachment.storeAction =
        MTLStoreActionStore;
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
  std::unique_lock<std::shared_mutex> lock(g_bufferMutex);
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
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadBufferDataDirect(
    JNIEnv *env, jclass, jlong bufferHandle, jobject directBuffer, jint offset,
    jint length) {
  id<MTLBuffer> buf = get_buffer((uint64_t)bufferHandle);
  if (!buf || !directBuffer)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  if (ptr && length > 0 && (size_t)(offset + length) <= [buf length]) {
    memcpy((uint8_t *)[buf contents], (uint8_t *)ptr + offset, (size_t)length);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyBuffer(
    JNIEnv *, jclass, jlong bufferHandle) {
  std::unique_lock<std::shared_mutex> lock(g_bufferMutex);
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






    bool isTranslucentPipeline = (pipeline == g_pipelineEntityTranslucent ||
                                  pipeline == g_pipelineParticle);
    id<MTLDepthStencilState> ds =
        isTranslucentPipeline ? g_depthStateLessEqual : g_depthState;
    if (ds)
      [g_currentEncoder setDepthStencilState:ds];
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
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawIndexedBatch(
    JNIEnv *env, jclass, jlong frameContext, jlong indexBuffer,
    jfloatArray drawData, jint drawCount) {
  (void)frameContext;
  if (!g_currentEncoder || drawCount <= 0 || !drawData)
    return;

  id<MTLBuffer> ib = get_buffer((uint64_t)indexBuffer);
  if (!ib)
    return;

  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;

  int len = env->GetArrayLength(drawData);
  int stride = 6;
  if (len < drawCount * stride)
    return;

  jfloat *data = env->GetFloatArrayElements(drawData, nullptr);
  if (!data)
    return;

  for (int i = 0; i < drawCount; i++) {
    int off = i * stride;

    uint32_t hi = *(uint32_t *)&data[off + 0];
    uint32_t lo = *(uint32_t *)&data[off + 1];
    uint64_t bufHandle = ((uint64_t)hi << 32) | (uint64_t)lo;
    float ox = data[off + 2];
    float oy = data[off + 3];
    float oz = data[off + 4];
    int idxCount = *(int *)&data[off + 5];

    id<MTLBuffer> vb = get_buffer(bufHandle);
    if (!vb || idxCount <= 0)
      continue;

    float offset[4] = {ox, oy, oz, 0.0f};
    [g_currentEncoder setVertexBytes:offset length:sizeof(offset) atIndex:4];
    [g_currentEncoder setVertexBuffer:vb offset:0 atIndex:0];
    [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                 indexCount:(NSUInteger)idxCount
                                  indexType:MTLIndexTypeUInt32
                                indexBuffer:ib
                          indexBufferOffset:0];
    g_drawCallCount++;
  }

  env->ReleaseFloatArrayElements(drawData, data, JNI_ABORT);
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





extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetDebugColor(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  if (!g_currentEncoder)
    return;
  float color[4] = {r, g, b, a};
  [g_currentEncoder setVertexBytes:color length:sizeof(color) atIndex:5];
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawLineBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jint vertexCount) {
  (void)frameContext;

  dbg("nDrawLineBuffer: encoder=%p, vtxCount=%d, pipeline=%p, buffer=%lld\n",
      g_currentEncoder, vertexCount, g_pipelineDebugLines,
      (long long)vertexBuffer);

  if (!g_currentEncoder || vertexCount <= 0 || !g_pipelineDebugLines)
    return;
  id<MTLBuffer> vb = get_buffer((uint64_t)vertexBuffer);
  if (!vb) {
    dbg("nDrawLineBuffer: buffer lookup failed\n");
    return;
  }


  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];
  if (g_depthStateLessEqual)
    [g_currentEncoder setDepthStencilState:g_depthStateLessEqual];


  [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];

  [g_currentEncoder setVertexBuffer:vb offset:0 atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeLine
                       vertexStart:0
                       vertexCount:(NSUInteger)vertexCount];

  dbg("nDrawLineBuffer: drew %d vertices\n", vertexCount);


  if (g_currentPipeline) {
    [g_currentEncoder setRenderPipelineState:g_currentPipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
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
    g_currentCmdBuffer = [[g_queue commandBuffer] retain];
    MTLRenderPassDescriptor *rp =
        [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = g_color;
    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;

    rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    rp.depthAttachment.texture = g_depth;
    rp.depthAttachment.loadAction = MTLLoadActionClear;
    rp.depthAttachment.storeAction =
        MTLStoreActionStore;
    rp.depthAttachment.clearDepth = 1.0;

    g_currentEncoder =
        [[g_currentCmdBuffer renderCommandEncoderWithDescriptor:rp] retain];
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
    [g_currentEncoder release];
    g_currentEncoder = nil;
  }


  if (g_currentCmdBuffer && g_depth && g_depthReadBuffer) {
    int w = (int)g_depth.width;
    int h = (int)g_depth.height;
    if (g_depthReadBuffer.length >= (NSUInteger)(w * h * 4)) {
      id<MTLBlitCommandEncoder> blitEnc =
          [g_currentCmdBuffer blitCommandEncoder];
      [blitEnc copyFromTexture:g_depth
                       sourceSlice:0
                       sourceLevel:0
                      sourceOrigin:MTLOriginMake(0, 0, 0)
                        sourceSize:MTLSizeMake(w, h, 1)
                          toBuffer:g_depthReadBuffer
                 destinationOffset:0
            destinationBytesPerRow:(NSUInteger)(w * 4)
          destinationBytesPerImage:(NSUInteger)(w * h * 4)];
      [blitEnc endEncoding];
    }
  }
  if (g_currentCmdBuffer) {


    if (g_depthCmdBuffer) {
      [g_depthCmdBuffer release];
      g_depthCmdBuffer = nil;
    }
    g_depthCmdBuffer = [g_currentCmdBuffer retain];
    [g_currentCmdBuffer commit];


    [g_currentCmdBuffer release];
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReadbackDepth(
    JNIEnv *env, jclass, jlong handle, jobject dest) {
  (void)handle;


  if (!g_depthReadBuffer || !dest || !env)
    return JNI_FALSE;

  void *destPtr = env->GetDirectBufferAddress(dest);
  if (!destPtr)
    return JNI_FALSE;

  jlong capacity = env->GetDirectBufferCapacity(dest);
  NSUInteger bufLen = g_depthReadBuffer.length;
  if (capacity < (jlong)bufLen)
    return JNI_FALSE;


  if (g_depthCmdBuffer) {
    [g_depthCmdBuffer waitUntilCompleted];
    [g_depthCmdBuffer release];
    g_depthCmdBuffer = nil;
  }

  memcpy(destPtr, g_depthReadBuffer.contents, bufLen);
  return JNI_TRUE;
}





extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntity;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityTranslucentPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntityTranslucent;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityEmissivePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntityEmissive;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetParticlePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineParticle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetEntityOverlay(
    JNIEnv *, jclass, jlong frameContext, jfloat hurtTime, jfloat whiteFlash,
    jfloat alpha) {
  (void)frameContext;
  g_entityOverlayParams[0] = hurtTime;
  g_entityOverlayParams[1] = whiteFlash;
  g_entityOverlayParams[2] = 0.0f;
  g_entityOverlayParams[3] = alpha;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetEntityTintColor(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  g_entityTintColor[0] = r;
  g_entityTintColor[1] = g;
  g_entityTintColor[2] = b;
  g_entityTintColor[3] = a;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindEntityTexture(
    JNIEnv *, jclass, jlong frameContext, jlong textureHandle) {
  (void)frameContext;
  if (textureHandle == 0) {
    g_entityTexture = nil;
    if (g_frameCount < 5) {
      dbg("nBindEntityTexture: clearing entity texture\n");
    }
    return;
  }
  g_entityTexture = (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;

  if (g_frameCount < 5) {
    dbg("nBindEntityTexture: set g_entityTexture=%p (handle=%lld)\n",
        g_entityTexture, textureHandle);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawEntityBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jint vertexCount,
    jint baseVertex, jint renderFlags) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0)
    return;

  id<MTLBuffer> vb = get_buffer((uint64_t)vertexBuffer);
  if (!vb)
    return;




  if (!(renderFlags & 0x8)) {
    id<MTLRenderPipelineState> pipeline = g_pipelineEntity;
    id<MTLDepthStencilState> depthSt = g_depthState;

    if (renderFlags & 0x2) {
      pipeline = g_pipelineEntityEmissive ? g_pipelineEntityEmissive
                                          : g_pipelineEntity;
    } else if (renderFlags & 0x1) {
      pipeline = g_pipelineEntityTranslucent ? g_pipelineEntityTranslucent
                                             : g_pipelineEntity;
      depthSt = g_depthStateNoWrite ? g_depthStateNoWrite : g_depthState;
    }


    if (!pipeline) {
      pipeline = g_pipelineInhouse;
      if (!pipeline)
        return;
    }

    [g_currentEncoder setRenderPipelineState:pipeline];
    [g_currentEncoder setDepthStencilState:depthSt];
  }


  [g_currentEncoder setVertexBuffer:vb offset:0 atIndex:0];



  [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                              length:sizeof(g_entityOverlayParams)
                             atIndex:5];


  if (g_entityTexture) {
    [g_currentEncoder setFragmentTexture:g_entityTexture atIndex:0];
    if (g_frameCount < 5) {
      dbg("Entity draw: bound texture %p at slot 0, verts=%d\n",
          g_entityTexture, vertexCount);
    }
  } else {

    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }
    if (g_frameCount < 5) {
      dbg("Entity draw: NO entity texture, using blockAtlas fallback, "
          "verts=%d\n",
          vertexCount);
    }
  }



  [g_currentEncoder setCullMode:MTLCullModeNone];

  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:(NSUInteger)baseVertex
                       vertexCount:(NSUInteger)vertexCount];


  [g_currentEncoder setCullMode:MTLCullModeBack];

  g_drawCallCount++;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawEntityBufferIndexed(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jlong indexBuffer,
    jint indexCount, jint baseIndex, jint renderFlags) {
  (void)frameContext;
  if (!g_currentEncoder || indexCount <= 0)
    return;

  id<MTLBuffer> vb = get_buffer((uint64_t)vertexBuffer);
  id<MTLBuffer> ib = get_buffer((uint64_t)indexBuffer);
  if (!vb || !ib)
    return;

  id<MTLRenderPipelineState> pipeline = g_pipelineEntity;
  id<MTLDepthStencilState> depthSt = g_depthState;

  if (renderFlags & 0x2) {
    pipeline =
        g_pipelineEntityEmissive ? g_pipelineEntityEmissive : g_pipelineEntity;
  } else if (renderFlags & 0x1) {
    pipeline = g_pipelineEntityTranslucent ? g_pipelineEntityTranslucent
                                           : g_pipelineEntity;
    depthSt = g_depthStateNoWrite ? g_depthStateNoWrite : g_depthState;
  }

  if (!pipeline) {
    pipeline = g_pipelineInhouse;
    if (!pipeline)
      return;
  }

  [g_currentEncoder setRenderPipelineState:pipeline];
  [g_currentEncoder setDepthStencilState:depthSt];
  [g_currentEncoder setVertexBuffer:vb offset:0 atIndex:0];
  [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                              length:sizeof(g_entityOverlayParams)
                             atIndex:5];
  [g_currentEncoder setCullMode:MTLCullModeNone];

  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)baseIndex * sizeof(uint32_t)];

  [g_currentEncoder setCullMode:MTLCullModeBack];
  g_drawCallCount++;
}
