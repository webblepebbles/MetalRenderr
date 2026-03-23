#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl.h>
#import <Foundation/NSProcessInfo.h>
#include <algorithm>
#include <atomic>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <jni.h>
#include <mach/mach.h>
#include <mach/mach_host.h>
#include <mach/mach_time.h>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <unordered_map>
#include <utility>
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
struct ResolvedBuf {
  id<MTLBuffer> buf;
  size_t offset;
};
static ResolvedBuf resolve_buffer(uint64_t h);
#ifndef dispatch_get_active_cpu_count
static inline int dispatch_get_active_cpu_count() {
  unsigned hc = std::thread::hardware_concurrency();
  return (int)(hc == 0 ? 1 : hc);
}
#endif
static bool g_available = true;
id<MTLDevice> g_device = nil;
static id<MTLCommandQueue> g_queue = nil;
static std::unordered_map<uint64_t, id<MTLBuffer>> g_buffers;
static uint64_t g_nextHandle = 1;
static id<MTLBuffer> g_megaVB = nil;
static const size_t MEGA_VB_CAPACITY = 2048ULL * 1024 * 1024;
static size_t g_megaVBHead = 0;
struct MegaSubAlloc {
  size_t offset;
  size_t size; 
};
static std::unordered_map<uint64_t, MegaSubAlloc> g_megaAllocs;
static std::vector<MegaSubAlloc> g_megaFreeList;
static uint64_t g_nextMegaHandle = 0x8000000000000000ULL;
static std::shared_mutex g_megaMutex;
struct DeferredDeletion {
  uint64_t handle;
  int frameQueued; 
  bool isMega;     
};
static std::vector<DeferredDeletion> g_deferredDeletions;
static std::mutex g_deferredMutex;
static const int DEFERRED_FRAME_DELAY =
    3; 
static inline bool isMegaHandle(uint64_t h) {
  return (h & 0x8000000000000000ULL) != 0;
}
static uint64_t megaAlloc(size_t size) {
  std::unique_lock<std::shared_mutex> lock(g_megaMutex);
  size_t aligned = (size + 255) & ~255; 
  for (auto it = g_megaFreeList.begin(); it != g_megaFreeList.end(); ++it) {
    if (it->size >= aligned) {
      size_t offset = it->offset;
      size_t blockSize = it->size;
      g_megaFreeList.erase(it);
      uint64_t handle = g_nextMegaHandle++;
      g_megaAllocs[handle] = {offset, aligned};
      if (blockSize > aligned) {
        g_megaFreeList.push_back({offset + aligned, blockSize - aligned});
      }
      return handle;
    }
  }
  if (g_megaVBHead + aligned > MEGA_VB_CAPACITY) {
    static int megaFailCount = 0;
    if (megaFailCount++ < 10 || megaFailCount % 500 == 0)
      dbg("megaAlloc FAIL: need %zu, head=%zu, cap=%zu (fail #%d)\n", aligned,
          g_megaVBHead, MEGA_VB_CAPACITY, megaFailCount);
    return 0; 
  }
  size_t offset = g_megaVBHead;
  g_megaVBHead += aligned;
  uint64_t handle = g_nextMegaHandle++;
  g_megaAllocs[handle] = {offset, aligned};
  return handle;
}
static void megaFree(uint64_t handle) {
  std::unique_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  if (it == g_megaAllocs.end())
    return;
  g_megaFreeList.push_back(it->second);
  g_megaAllocs.erase(it);
}
static size_t megaGetOffset(uint64_t handle) {
  std::shared_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  return (it != g_megaAllocs.end()) ? it->second.offset : 0;
}
static void *megaGetPointer(uint64_t handle) {
  std::shared_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  if (it == g_megaAllocs.end() || !g_megaVB)
    return nullptr;
  return (char *)[g_megaVB contents] + it->second.offset;
}
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
id<MTLDepthStencilState> g_depthState = nil;
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
static std::atomic<float> g_lastGpuMs{
    0.0f}; 
uint32_t g_drawCallCount = 0;
static int g_drawSkipCount = 0;
static int g_totalDraws = 0;
static uint64_t g_prof_drawAll_acc = 0;    
static uint64_t g_prof_endFrame_acc = 0;   
static uint64_t g_prof_waitRender_acc = 0; 
static uint64_t g_prof_cglBind_acc = 0;    
static int g_prof_count = 0;
static id<MTLTexture> g_entityTexture = nil;
static float g_entityOverlayParams[4] = {0, 0, 0, 1};
static float g_entityTintColor[4] = {1, 1, 1, 1};
static id<MTLIndirectCommandBuffer> g_icb = nil;
static NSUInteger g_icbMaxCommands = 0;
static const NSUInteger ICB_INITIAL_SIZE = 8192; 
static id<MTLRenderPipelineState> g_pipelineInhouseICB =
    nil;                                 
static id<MTLBuffer> g_fragArgBuf = nil; 
static id<MTLArgumentEncoder> g_fragArgEncoder = nil;
static id<MTLComputePipelineState> g_hizDownsamplePipeline = nil;
static id<MTLComputePipelineState> g_hizMultiPipeline = nil;
static id<MTLComputePipelineState> g_cullEncodePipeline = nil;
static id<MTLComputePipelineState> g_resetCullPipeline = nil;
static id<MTLComputePipelineState> g_lodSelectPipeline = nil;
static id<MTLTexture> g_hizPyramid = nil;
static uint32_t g_hizMipCount = 0;
static int g_hizWidth = 0;
static int g_hizHeight = 0;
static id<MTLBuffer> g_hizReadbackBuf = nil;
static int g_hizReadbackW = 0;
static int g_hizReadbackH = 0;
static const uint32_t HIZ_READBACK_MIP = 3; 
static float g_vpMatrix[16] = {0};          
static id<MTLTexture> g_hizSrcViews[16] = {}; 
static id<MTLTexture> g_hizDstViews[16] = {}; 
static int g_hizViewsValid = 0;               
id<MTLBuffer> g_cullDrawArgsBuffer = nil; 
id<MTLBuffer> g_cullDrawCountBuffer = nil;
static id<MTLBuffer> g_cullStatsBuffer = nil;
id<MTLBuffer> g_subChunkBuffer = nil;
static id<MTLBuffer> g_chunkUniformsBuffer = nil;
static id<MTLBuffer> g_cameraUniformsBuffer = nil;
static id<MTLBuffer> g_visibleIndicesBuffer =
    nil; 
static uint32_t g_maxGPUDrawCalls = 65536;
uint32_t g_gpuSubChunkCount = 0;
static bool g_gpuDrivenEnabled = false;
static id<MTLSharedEvent> g_frameEvent = nil;
static MTLSharedEventListener *g_eventListener = nil;
static uint64_t g_eventCounter = 0;
static constexpr int kTripleBufferCount = 3;
id<MTLBuffer> g_tripleBuffers[kTripleBufferCount] = {};
int g_currentBufferIndex = 0;
static dispatch_semaphore_t g_frameSemaphore = nil;
static volatile bool g_shuttingDown = false;
static volatile bool g_currentFrameReady =
    true; 
static id<MTLBuffer> g_argumentBuffer = nil;
static bool g_argumentBufferDirty = true;
static id<MTLRenderPipelineState> g_meshTerrainOpaquePSO = nil;
static id<MTLRenderPipelineState> g_meshTerrainCutoutPSO = nil;
static id<MTLRenderPipelineState> g_meshTerrainEmissivePSO = nil;
bool g_meshShadersActive = false;
static int g_thermalState = 0;       
static int g_lodRadiusReduction = 0; 
static double g_lastThermalCheckTime = 0;
static id<MTLDepthStencilState> g_depthStateReversedZ = nil;
static id<MTLDepthStencilState> g_depthStateReversedZNoWrite = nil;
static id<MTLDepthStencilState> g_depthStateReversedZReadOnly = nil;
struct NativeMesh {
  int32_t chunkX, chunkY, chunkZ;
  uint64_t bufferHandle;
  int32_t quadCount;
  int32_t opaqueQuadCount; 
  int32_t lodLevel;
  bool active;
};
static std::vector<NativeMesh> g_nativeMeshes;
static std::unordered_map<int64_t, size_t> g_meshKeyToIdx;
static std::vector<size_t> g_meshFreeSlots;
static std::shared_mutex g_meshRegMutex;
static int g_activeMeshCount = 0;
static int64_t packMeshKey(int cx, int cy, int cz) {
  return ((int64_t)(cx & 0x3FFFFF) << 42) | ((int64_t)(cy & 0xFFFFF) << 22) |
         (int64_t)(cz & 0x3FFFFF);
}
static inline bool frustumTestAABB(const float p[24], float x0, float y0,
                                   float z0, float x1, float y1, float z1) {
  for (int i = 0; i < 6; i++) {
    float a = p[i * 4], b = p[i * 4 + 1], c = p[i * 4 + 2], d = p[i * 4 + 3];
    float px = (a >= 0) ? x1 : x0;
    float py = (b >= 0) ? y1 : y0;
    float pz = (c >= 0) ? z1 : z0;
    if (a * px + b * py + c * pz + d < 0)
      return false;
  }
  return true;
}
static void extractFrustumPlanes(const float m[16], float out[24]) {
  out[0] = m[3] + m[0];
  out[1] = m[7] + m[4];
  out[2] = m[11] + m[8];
  out[3] = m[15] + m[12];
  out[4] = m[3] - m[0];
  out[5] = m[7] - m[4];
  out[6] = m[11] - m[8];
  out[7] = m[15] - m[12];
  out[8] = m[3] + m[1];
  out[9] = m[7] + m[5];
  out[10] = m[11] + m[9];
  out[11] = m[15] + m[13];
  out[12] = m[3] - m[1];
  out[13] = m[7] - m[5];
  out[14] = m[11] - m[9];
  out[15] = m[15] - m[13];
  out[16] = m[3] + m[2];
  out[17] = m[7] + m[6];
  out[18] = m[11] + m[10];
  out[19] = m[15] + m[14];
  out[20] = m[3] - m[2];
  out[21] = m[7] - m[6];
  out[22] = m[11] - m[10];
  out[23] = m[15] - m[14];
  for (int i = 0; i < 6; i++) {
    float a = out[i * 4], b = out[i * 4 + 1], c = out[i * 4 + 2];
    float len = sqrtf(a * a + b * b + c * c);
    if (len > 0) {
      float inv = 1.0f / len;
      out[i * 4] *= inv;
      out[i * 4 + 1] *= inv;
      out[i * 4 + 2] *= inv;
      out[i * 4 + 3] *= inv;
    }
  }
}
static void ensure_device() {
  if (!g_device) {
    g_device = MTLCreateSystemDefaultDevice();
    if (g_device) {
      g_queue = [g_device newCommandQueue];
      if (!g_megaVB) {
        g_megaVB = [g_device newBufferWithLength:MEGA_VB_CAPACITY
                                         options:MTLResourceStorageModeShared];
        g_megaVBHead = 0;
        if (g_megaVB) {
          dbg("Mega vertex buffer created: %zuMB\n",
              MEGA_VB_CAPACITY / (1024 * 1024));
        } else {
          dbg("WARN: Failed to create mega vertex buffer, falling back to "
              "individual buffers\n");
        }
      }
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
	uint faceMask = as_type<uint>(chunkOffset.w);
	if (faceMask != 0 && v.color[3] >= 254) {
		uint nIdx = v.normalIndex & 0x7;
		if (nIdx < 6 && ((faceMask >> nIdx) & 1) == 0) {
			out.position = float4(0.0, 0.0, -2.0, 1.0);
			out.texCoord = float2(0.0);
			out.color    = float4(0.0);
			out.light    = 0.0;
			return out;
		}
	}
	float3 localPos = float3(short3(v.position)) / 256.0;
	float3 worldPos = localPos + chunkOffset.xyz;
	float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
	out.position = projectionMatrix * viewPos;
	out.texCoord = float2(v.texCoord) / 65535.0;
	out.color    = float4(v.color) / 255.0;
	float blockLight = float(v.packedLight & 0xF) / 15.0;
	float skyLight   = float((v.packedLight >> 4) & 0xF) / 15.0;
	out.light    = max(blockLight, skyLight);
	return out;
}
fragment float4 fragment_terrain(
	SimpleVertexOut in [[stage_in]],
	texture2d<float> blockAtlas [[texture(0)]]
) {
	constexpr sampler s(filter::nearest, address::clamp_to_edge);
	float4 texColor = blockAtlas.sample(s, in.texCoord);
	if (texColor.a < 0.5 && in.color.a >= 0.998) discard_fragment();
	float4 baseColor = texColor * in.color;
	float lightFactor = clamp(in.light * 0.8 + 0.2, 0.2, 1.0);
	baseColor.rgb *= lightFactor;
	float outAlpha = in.color.a < 0.99 ? in.color.a : 1.0;
	return float4(baseColor.rgb, outAlpha);
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
	float4 baseColor = (texColor.a > 0.01) ? texColor * in.color : in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	baseColor.rgb *= (0.4 + 0.6 * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.5);
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}
	float whiteFlash = overlayParams.y;
	if (whiteFlash > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0),
			clamp(whiteFlash, 0.0, 1.0));
	}
	return float4(baseColor.rgb, 1.0);
}
fragment float4 fragment_entity_translucent(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler texSampler(filter::linear, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	float4 baseColor = (texColor.a > 0.004) ? texColor * in.color : in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	baseColor.rgb *= (0.4 + 0.6 * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.5);
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}
	return baseColor;
}
fragment float4 fragment_entity_emissive(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]]
) {
	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	float4 baseColor = (texColor.a > 0.01) ? texColor * in.color : in.color;
	return baseColor;
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
    desc.colorAttachments[0].blendingEnabled = YES;
    desc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
    desc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
    desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    desc.colorAttachments[0].destinationRGBBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    desc.colorAttachments[0].destinationAlphaBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    desc.label = @"InhouseTerrain";
    g_pipelineInhouse = [g_device newRenderPipelineStateWithDescriptor:desc
                                                                 error:&error];
    if (!g_pipelineInhouse) {
      dbg("FATAL: Terrain pipeline creation failed: %s\n",
          [[error localizedDescription] UTF8String]);
    }
    g_pipelineOpaque = g_pipelineInhouse;
    id<MTLFunction> fragIcbFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_terrain_icb"];
    if (vertexFn && fragIcbFn) {
      MTLRenderPipelineDescriptor *icbDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      icbDesc.vertexFunction = vertexFn;
      icbDesc.fragmentFunction = fragIcbFn;
      icbDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      icbDesc.colorAttachments[0].blendingEnabled = YES;
      icbDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      icbDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      icbDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      icbDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      icbDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      icbDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      icbDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      icbDesc.supportIndirectCommandBuffers = YES;
      icbDesc.label = @"InhouseTerrainICB";
      g_pipelineInhouseICB =
          [g_device newRenderPipelineStateWithDescriptor:icbDesc error:&error];
      if (!g_pipelineInhouseICB) {
        dbg("WARN: ICB terrain pipeline creation failed: %s\n",
            [[error localizedDescription] UTF8String]);
      } else {
        dbg("ICB terrain pipeline created OK\n");
        g_fragArgEncoder = [fragIcbFn newArgumentEncoderWithBufferIndex:0];
        if (g_fragArgEncoder) {
          NSUInteger argBufLen = [g_fragArgEncoder encodedLength];
          g_fragArgBuf =
              [g_device newBufferWithLength:argBufLen
                                    options:MTLResourceStorageModeShared];
          dbg("Fragment arg buffer: %zu bytes\n", (size_t)argBufLen);
        }
      }
    } else {
      dbg("WARN: ICB fragment shader not found\n");
    }
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
  dsDesc.depthCompareFunction = MTLCompareFunctionLessEqual;
  dsDesc.depthWriteEnabled = YES;
  g_depthState = [g_device newDepthStencilStateWithDescriptor:dsDesc];
  g_depthStateReversedZ = g_depthState;
  MTLDepthStencilDescriptor *dsNoWrite =
      [[MTLDepthStencilDescriptor alloc] init];
  dsNoWrite.depthCompareFunction = MTLCompareFunctionLessEqual;
  dsNoWrite.depthWriteEnabled = NO;
  g_depthStateNoWrite = [g_device newDepthStencilStateWithDescriptor:dsNoWrite];
  g_depthStateReversedZNoWrite = g_depthStateNoWrite;
  MTLDepthStencilDescriptor *dsLessEq =
      [[MTLDepthStencilDescriptor alloc] init];
  dsLessEq.depthCompareFunction = MTLCompareFunctionLessEqual;
  dsLessEq.depthWriteEnabled = NO;
  g_depthStateLessEqual =
      [g_device newDepthStencilStateWithDescriptor:dsLessEq];
  g_depthStateReversedZReadOnly = g_depthStateLessEqual;
  auto createComputePipeline =
      [&](NSString *funcName) -> id<MTLComputePipelineState> {
    id<MTLFunction> func = [g_shaderLibrary newFunctionWithName:funcName];
    if (!func) {
      dbg("Compute function '%s' not found in library\n",
          [funcName UTF8String]);
      return nil;
    }
    NSError *cErr = nil;
    id<MTLComputePipelineState> cps =
        [g_device newComputePipelineStateWithFunction:func error:&cErr];
    if (!cps) {
      dbg("Compute pipeline '%s' creation failed: %s\n", [funcName UTF8String],
          cErr ? [[cErr localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("Compute pipeline '%s' created OK\n", [funcName UTF8String]);
    }
    return cps;
  };
  g_hizDownsamplePipeline = createComputePipeline(@"hiz_downsample");
  g_hizMultiPipeline = createComputePipeline(@"hiz_downsample_multi");
  g_cullEncodePipeline = createComputePipeline(@"cull_and_encode");
  g_resetCullPipeline = createComputePipeline(@"reset_cull_stats");
  g_lodSelectPipeline = createComputePipeline(@"lod_select");
  if (!g_frameSemaphore) {
    g_frameSemaphore = dispatch_semaphore_create(kTripleBufferCount);
    dbg("Triple buffering: dispatch_semaphore created (count=2)\n");
  }
  if (!g_frameEvent) {
    g_frameEvent = [g_device newSharedEvent];
    g_eventListener = [[MTLSharedEventListener alloc]
        initWithDispatchQueue:dispatch_get_global_queue(
                                  QOS_CLASS_USER_INTERACTIVE, 0)];
    g_eventCounter = 0;
    dbg("Triple buffering: MTLSharedEvent created\n");
  }
  for (int i = 0; i < kTripleBufferCount; i++) {
    if (!g_tripleBuffers[i]) {
      g_tripleBuffers[i] =
          [g_device newBufferWithLength:512
                                options:MTLResourceStorageModeShared];
    }
  }
  if (!g_cullDrawCountBuffer) {
    g_cullDrawCountBuffer =
        [g_device newBufferWithLength:sizeof(uint32_t)
                              options:MTLResourceStorageModeShared];
  }
  if (!g_cullStatsBuffer) {
    g_cullStatsBuffer =
        [g_device newBufferWithLength:sizeof(uint32_t) * 8
                              options:MTLResourceStorageModeShared];
  }
  if (!g_cullDrawArgsBuffer) {
    size_t argsSize = g_maxGPUDrawCalls * sizeof(uint32_t);
    g_cullDrawArgsBuffer =
        [g_device newBufferWithLength:argsSize
                              options:MTLResourceStorageModeShared];
  }
  if (!g_visibleIndicesBuffer) {
    g_visibleIndicesBuffer =
        [g_device newBufferWithLength:g_maxGPUDrawCalls * sizeof(uint32_t)
                              options:MTLResourceStorageModeShared];
  }
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
  dd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  dd.storageMode = MTLStorageModePrivate;
  g_depth = [g_device newTextureWithDescriptor:dd];
  g_hizWidth = w;
  g_hizHeight = h;
  int hizW = std::max(1, w / 2);
  int hizH = std::max(1, h / 2);
  g_hizMipCount = (uint32_t)floor(log2(std::max(hizW, hizH))) + 1;
  g_hizMipCount = std::min(g_hizMipCount, (uint32_t)12); 
  MTLTextureDescriptor *hizDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                   width:hizW
                                  height:hizH
                               mipmapped:YES];
  hizDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
  hizDesc.storageMode = MTLStorageModePrivate;
  hizDesc.mipmapLevelCount = g_hizMipCount;
  g_hizPyramid = [g_device newTextureWithDescriptor:hizDesc];
  dbg("Created Hi-Z pyramid: %dx%d, %d mips\n", hizW, hizH, g_hizMipCount);
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
  g_shuttingDown = false; 
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
static bool g_reuseTerrainFrame = false;
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetReuseTerrainFrame(
    JNIEnv *, jclass, jboolean reuse) {
  g_reuseTerrainFrame = (bool)reuse;
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
    JNIEnv *, jclass, jlong handle) {
  g_shuttingDown = true;
  if (g_frameSemaphore) {
    dispatch_semaphore_signal(g_frameSemaphore);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroy(
    JNIEnv *, jclass, jlong handle) {
  g_shuttingDown = true;
  if (g_frameSemaphore) {
    dispatch_semaphore_signal(g_frameSemaphore);
  }
}
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
static ResolvedBuf resolve_buffer(uint64_t h) {
  if (isMegaHandle(h)) {
    std::shared_lock<std::shared_mutex> lock(g_megaMutex);
    auto it = g_megaAllocs.find(h);
    if (it != g_megaAllocs.end() && g_megaVB)
      return {g_megaVB, it->second.offset};
    return {nil, 0};
  }
  std::shared_lock<std::shared_mutex> lock(g_bufferMutex);
  auto it = g_buffers.find(h);
  if (it != g_buffers.end())
    return {it->second, 0};
  return {nil, 0};
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
    rp.depthAttachment.storeAction = MTLStoreActionStore;
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
  uint64_t h = (uint64_t)bufferHandle;
  std::lock_guard<std::mutex> lock(g_deferredMutex);
  g_deferredDeletions.push_back({h, g_frameCount, isMegaHandle(h)});
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateBuffer(
    JNIEnv *, jclass, jlong deviceHandle, jint sizeBytes, jint storageMode) {
  (void)deviceHandle;
  (void)storageMode;
  ensure_device();
  if (!g_device || sizeBytes <= 0)
    return 0;
  if (g_megaVB && sizeBytes <= 16 * 1024 * 1024) { 
    uint64_t megaH = megaAlloc((size_t)sizeBytes);
    if (megaH != 0) {
      return (jlong)megaH;
    }
  }
  id<MTLBuffer> buf =
      [g_device newBufferWithLength:(size_t)sizeBytes
                            options:MTLResourceStorageModeShared];
  return (jlong)store_buffer(buf);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadBufferData(
    JNIEnv *env, jclass, jlong bufferHandle, jbyteArray data, jint offset,
    jint length) {
  uint64_t h = (uint64_t)bufferHandle;
  if (isMegaHandle(h)) {
    void *dst = megaGetPointer(h);
    if (!dst || !data || length <= 0)
      return;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes) {
      memcpy((uint8_t *)dst + offset, bytes, (size_t)length);
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    }
    return;
  }
  id<MTLBuffer> buf = get_buffer(h);
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
  uint64_t h = (uint64_t)bufferHandle;
  if (isMegaHandle(h)) {
    void *dst = megaGetPointer(h);
    if (!dst || !directBuffer || length <= 0)
      return;
    void *ptr = env->GetDirectBufferAddress(directBuffer);
    if (ptr) {
      memcpy((uint8_t *)dst, (uint8_t *)ptr + offset, (size_t)length);
    }
    return;
  }
  id<MTLBuffer> buf = get_buffer(h);
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
  uint64_t h = (uint64_t)bufferHandle;
  std::lock_guard<std::mutex> lock(g_deferredMutex);
  g_deferredDeletions.push_back({h, g_frameCount, isMegaHandle(h)});
}
static id<MTLRenderPipelineState> g_currentPipeline = nil;
static float g_chunkOffsetX = 0, g_chunkOffsetY = 0, g_chunkOffsetZ = 0;
static float g_projMatrix[16] = {};
static float g_mvMatrix[16] = {};
static double g_camX = 0, g_camY = 0, g_camZ = 0;
id<MTLRenderCommandEncoder> g_currentEncoder = nil;
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
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!vbRes.buf || !ibRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  id<MTLBuffer> ib = ibRes.buf;
  g_totalDraws++;
  if (g_totalDraws <= 3) {
    const short *vtx = (const short *)((uint8_t *)[vb contents] + vbRes.offset);
    const uint32_t *idx =
        (const uint32_t *)((uint8_t *)[ib contents] + ibRes.offset);
    dbg("Draw #%d: vb=%p(%luB+%zu) ib=%p(%luB+%zu) idxCount=%d baseIdx=%d\n",
        g_totalDraws, vb, (unsigned long)[vb length], vbRes.offset, ib,
        (unsigned long)[ib length], ibRes.offset, indexCount, baseIndex);
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
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)(ibRes.offset +
                                         (size_t)baseIndex * sizeof(uint32_t))];
  g_drawCallCount++;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawIndexedBatch(
    JNIEnv *env, jclass, jlong frameContext, jlong indexBuffer,
    jfloatArray drawData, jint drawCount) {
  (void)frameContext;
  if (!g_currentEncoder || drawCount <= 0 || !drawData)
    return;
  uint64_t t0 = mach_absolute_time(); 
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!ibRes.buf)
    return;
  id<MTLBuffer> ib = ibRes.buf;
  NSUInteger ibOffset = (NSUInteger)ibRes.offset;
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
  uint64_t t1 = mach_absolute_time(); 
  static id<MTLBuffer> g_batchOffsetBuf = nil;
  static size_t g_batchOffsetCap = 0;
  size_t offsetBufSize = (size_t)drawCount * 16; 
  if (!g_batchOffsetBuf || g_batchOffsetCap < offsetBufSize) {
    g_batchOffsetCap = offsetBufSize * 2;
    g_batchOffsetBuf =
        [g_device newBufferWithLength:g_batchOffsetCap
                              options:MTLResourceStorageModeShared];
  }
  float *offBuf = (float *)[g_batchOffsetBuf contents];
  struct DrawCmd {
    uint64_t bufHandle; 
    size_t megaOffset;  
    int idxCount;
    int opaqueIdxCount; 
    float distSq;       
    float ox, oy, oz;   
    bool isMega;
  };
  DrawCmd stackCmds[256];
  DrawCmd *cmds = (drawCount <= 256) ? stackCmds : new DrawCmd[drawCount];
  int validCount = 0;
  int megaCount = 0;
  static const int VERTEX_STRIDE = 16; 
  { 
    std::shared_lock<std::shared_mutex> megaLock(g_megaMutex);
    for (int i = 0; i < drawCount; i++) {
      int off = i * stride;
      uint32_t hi = *(uint32_t *)&data[off + 0];
      uint32_t lo = *(uint32_t *)&data[off + 1];
      uint64_t bufHandle = ((uint64_t)hi << 32) | (uint64_t)lo;
      int idxCount = *(int *)&data[off + 5];
      if (idxCount <= 0)
        continue;
      float ox = data[off + 2];
      float oy = data[off + 3];
      float oz = data[off + 4];
      float cx = ox + 8.0f;
      float cy = oy + 8.0f;
      float cz = oz + 8.0f;
      float distSq = cx * cx + cy * cy + cz * cz;
      bool mega = isMegaHandle(bufHandle);
      if (mega) {
        auto it = g_megaAllocs.find(bufHandle);
        if (it == g_megaAllocs.end())
          continue;
        cmds[validCount] = {bufHandle, it->second.offset,
                            idxCount,  idxCount,
                            distSq,    ox,
                            oy,        oz,
                            true};
        megaCount++;
      } else {
        cmds[validCount] = {bufHandle, 0,  idxCount, idxCount, distSq,
                            ox,        oy, oz,       false};
      }
      validCount++;
    }
  } 
  env->ReleaseFloatArrayElements(drawData, data, JNI_ABORT);
  static int hiz_culled_acc = 0, hiz_total_acc = 0;
  if (g_hizReadbackBuf && g_hizReadbackW > 0 && g_hizReadbackH > 0 &&
      g_frameCount > 10) { 
    const float *hizData = (const float *)[g_hizReadbackBuf contents];
    int hW = g_hizReadbackW;
    int hH = g_hizReadbackH;
    float screenW = (float)(g_rtWidth * g_scale);
    float screenH = (float)(g_rtHeight * g_scale);
    float texelCoverage = (float)(1 << (HIZ_READBACK_MIP + 1));
    NSUInteger bytesPerRow = (NSUInteger)(g_hizReadbackW * 4 + 255) & ~255;
    int floatsPerRow = (int)(bytesPerRow / 4);
    const float DEPTH_BIAS = 0.001f;
    int newValidCount = 0;
    int occluded = 0;
    for (int i = 0; i < validCount; i++) {
      float minX = cmds[i].ox, minY = cmds[i].oy, minZ = cmds[i].oz;
      float maxX = minX + 16.0f, maxY = minY + 16.0f, maxZ = minZ + 16.0f;
      float ssMinX = 1e10f, ssMinY = 1e10f;
      float ssMaxX = -1e10f, ssMaxY = -1e10f;
      float nearestDepth = 1.0f;
      bool anyBehind = false;
      for (int c = 0; c < 8; c++) {
        float cx = (c & 1) ? maxX : minX;
        float cy = (c & 2) ? maxY : minY;
        float cz = (c & 4) ? maxZ : minZ;
        float cw = g_vpMatrix[3] * cx + g_vpMatrix[7] * cy +
                   g_vpMatrix[11] * cz + g_vpMatrix[15];
        if (cw <= 0.01f) {
          anyBehind = true;
          break;
        }
        float clipX = g_vpMatrix[0] * cx + g_vpMatrix[4] * cy +
                      g_vpMatrix[8] * cz + g_vpMatrix[12];
        float clipY = g_vpMatrix[1] * cx + g_vpMatrix[5] * cy +
                      g_vpMatrix[9] * cz + g_vpMatrix[13];
        float clipZ = g_vpMatrix[2] * cx + g_vpMatrix[6] * cy +
                      g_vpMatrix[10] * cz + g_vpMatrix[14];
        float ndcX = clipX / cw;
        float ndcY = clipY / cw;
        float ndcZ = clipZ / cw; 
        float sx = (ndcX * 0.5f + 0.5f) * screenW;
        float sy = (1.0f - (ndcY * 0.5f + 0.5f)) * screenH;
        ssMinX = std::min(ssMinX, sx);
        ssMinY = std::min(ssMinY, sy);
        ssMaxX = std::max(ssMaxX, sx);
        ssMaxY = std::max(ssMaxY, sy);
        nearestDepth = std::min(nearestDepth, ndcZ);
      }
      if (anyBehind) {
        cmds[newValidCount++] = cmds[i];
        continue;
      }
      ssMinX = std::max(0.0f, std::min(ssMinX, screenW));
      ssMinY = std::max(0.0f, std::min(ssMinY, screenH));
      ssMaxX = std::max(0.0f, std::min(ssMaxX, screenW));
      ssMaxY = std::max(0.0f, std::min(ssMaxY, screenH));
      if (ssMaxX <= ssMinX || ssMaxY <= ssMinY) {
        occluded++;
        if (cmds[i].isMega)
          megaCount--;
        continue;
      }
      float maxHizDepth = 0.0f;
      float coords[4][2] = {{ssMinX, ssMinY},
                            {ssMaxX, ssMinY},
                            {ssMinX, ssMaxY},
                            {ssMaxX, ssMaxY}};
      bool validSample = false;
      for (int s = 0; s < 4; s++) {
        int hx =
            std::max(0, std::min((int)(coords[s][0] / texelCoverage), hW - 1));
        int hy =
            std::max(0, std::min((int)(coords[s][1] / texelCoverage), hH - 1));
        float d = hizData[hy * floatsPerRow + hx];
        if (d > 0.001f) {
          maxHizDepth = std::max(maxHizDepth, d);
          validSample = true;
        }
      }
      if (validSample && nearestDepth > maxHizDepth + DEPTH_BIAS) {
        occluded++;
        if (cmds[i].isMega)
          megaCount--;
        continue;
      }
      cmds[newValidCount++] = cmds[i];
    }
    hiz_culled_acc += occluded;
    hiz_total_acc += validCount;
    validCount = newValidCount;
    if (g_frameCount % 120 == 0 && hiz_total_acc > 0) {
      dbg("HiZ_CULL: culled %d/%d (%.0f%%) over 120 frames\n", hiz_culled_acc,
          hiz_total_acc, 100.0 * hiz_culled_acc / hiz_total_acc);
      hiz_culled_acc = 0;
      hiz_total_acc = 0;
    }
  }
  if (validCount > 1) {
    std::sort(cmds, cmds + validCount, [](const DrawCmd &a, const DrawCmd &b) {
      return a.distSq < b.distSq;
    });
  }
  for (int i = 0; i < validCount; i++) {
    int oidx = i * 4;
    offBuf[oidx + 0] = cmds[i].ox;
    offBuf[oidx + 1] = cmds[i].oy;
    offBuf[oidx + 2] = cmds[i].oz;
    float cx2 = cmds[i].ox + 8.0f;
    float cy2 = cmds[i].oy + 8.0f;
    float cz2 = cmds[i].oz + 8.0f;
    uint32_t faceMask = 0x3F;
    const float margin = 8.0f;
    if (cy2 > margin)
      faceMask &= ~(1u << 1);
    if (cy2 < -margin)
      faceMask &= ~(1u << 0);
    if (cz2 < -margin)
      faceMask &= ~(1u << 2);
    if (cz2 > margin)
      faceMask &= ~(1u << 3);
    if (cx2 < -margin)
      faceMask &= ~(1u << 4);
    if (cx2 > margin)
      faceMask &= ~(1u << 5);
    float maskAsFloat;
    memcpy(&maskAsFloat, &faceMask, sizeof(float));
    offBuf[oidx + 3] = maskAsFloat;
  }
  uint64_t t2 = mach_absolute_time(); 
  static uint64_t t_acc_resolve = 0, t_acc_jni = 0, t_acc_classify = 0;
  static uint64_t t_acc_icb_encode = 0, t_acc_icb_exec = 0, t_acc_total = 0;
  static int t_acc_frames = 0;
  static int t_acc_draws = 0;
  static uint64_t t_last_log_frame = 0;
  if (g_frameCount > 0 && g_frameCount != t_last_log_frame &&
      (g_frameCount % 120 == 0)) {
    mach_timebase_info_data_t tbi;
    mach_timebase_info(&tbi);
    auto toUs = [&](uint64_t t) -> double {
      return (double)(t * tbi.numer / tbi.denom) / 1000.0;
    };
    dbg("TIMING [%d frames, %d draws]: total=%.0fus resolve=%.0fus jni=%.0fus "
        "classify=%.0fus icb_encode=%.0fus icb_exec=%.0fus\n",
        t_acc_frames, t_acc_draws, toUs(t_acc_total), toUs(t_acc_resolve),
        toUs(t_acc_jni), toUs(t_acc_classify), toUs(t_acc_icb_encode),
        toUs(t_acc_icb_exec));
    dbg("TIMING per-frame avg: total=%.1fus resolve=%.1fus jni=%.1fus "
        "classify=%.1fus icb_encode=%.1fus icb_exec=%.1fus\n",
        toUs(t_acc_total) / MAX(t_acc_frames, 1),
        toUs(t_acc_resolve) / MAX(t_acc_frames, 1),
        toUs(t_acc_jni) / MAX(t_acc_frames, 1),
        toUs(t_acc_classify) / MAX(t_acc_frames, 1),
        toUs(t_acc_icb_encode) / MAX(t_acc_frames, 1),
        toUs(t_acc_icb_exec) / MAX(t_acc_frames, 1));
    t_acc_resolve = t_acc_jni = t_acc_classify = 0;
    t_acc_icb_encode = t_acc_icb_exec = t_acc_total = 0;
    t_acc_frames = 0;
    t_acc_draws = 0;
    t_last_log_frame = g_frameCount;
  }
  if (validCount == 0) {
    if (cmds != stackCmds)
      delete[] cmds;
    return;
  }
  static int g_drawBudget = 8192; 
  static const int MIN_BUDGET = 800;
  static const int MAX_BUDGET = 8192;
  float gpuMs = g_lastGpuMs.load(std::memory_order_relaxed);
  if (gpuMs > 14.0f && g_drawBudget > MIN_BUDGET) {
    g_drawBudget = MAX(MIN_BUDGET, (int)(g_drawBudget * 0.95f));
  } else if (gpuMs < 10.0f && g_drawBudget < MAX_BUDGET) {
    g_drawBudget = MIN(MAX_BUDGET, (int)(g_drawBudget * 1.08f) + 1);
  }
  int preCapCount = validCount;
  if (validCount > g_drawBudget) {
    megaCount = 0;
    for (int i = 0; i < g_drawBudget; i++) {
      if (cmds[i].isMega)
        megaCount++;
    }
    validCount = g_drawBudget;
  }
  if (g_frameCount % 600 == 0) {
    dbg("DRAW_BUDGET: budget=%d, preCap=%d, drawn=%d, gpuMs=%.1f\n",
        g_drawBudget, preCapCount, validCount, gpuMs);
  }
  if (g_megaVB) {
    [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
  }
  [g_currentEncoder setVertexBuffer:g_batchOffsetBuf offset:0 atIndex:4];
  bool canICB = (megaCount > 0 && g_megaVB && g_pipelineInhouseICB &&
                 g_fragArgBuf && g_fragArgEncoder && g_blockAtlas);
  if (canICB) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICB];
    g_currentPipeline = g_pipelineInhouseICB;
    [g_fragArgEncoder setArgumentBuffer:g_fragArgBuf offset:0];
    [g_fragArgEncoder setTexture:g_blockAtlas atIndex:0];
    [g_currentEncoder setFragmentBuffer:g_fragArgBuf offset:0 atIndex:0];
    [g_currentEncoder useResource:g_blockAtlas
                            usage:MTLResourceUsageRead
                           stages:MTLRenderStageFragment];
    if (!g_icb || g_icbMaxCommands < (NSUInteger)megaCount) {
      NSUInteger newSize = MAX(ICB_INITIAL_SIZE, (NSUInteger)megaCount * 2);
      MTLIndirectCommandBufferDescriptor *desc =
          [MTLIndirectCommandBufferDescriptor new];
      desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
      desc.inheritPipelineState = YES;
      desc.inheritBuffers = YES;
      g_icb = [g_device
          newIndirectCommandBufferWithDescriptor:desc
                                 maxCommandCount:newSize
                                         options:MTLResourceStorageModeShared];
      g_icbMaxCommands = newSize;
      dbg("ICB created: maxCommands=%lu\n", (unsigned long)newSize);
    }
    [g_icb resetWithRange:NSMakeRange(0, (NSUInteger)megaCount)];
    int icbIdx = 0;
    for (int i = 0; i < validCount; i++) {
      const DrawCmd &cmd = cmds[i];
      if (!cmd.isMega)
        continue;
      id<MTLIndirectRenderCommand> icmd =
          [g_icb indirectRenderCommandAtIndex:(NSUInteger)icbIdx];
      [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                       indexCount:(NSUInteger)cmd.idxCount
                        indexType:MTLIndexTypeUInt32
                      indexBuffer:ib
                indexBufferOffset:ibOffset
                    instanceCount:1
                       baseVertex:(NSInteger)(cmd.megaOffset / VERTEX_STRIDE)
                     baseInstance:(NSUInteger)i]; 
      icbIdx++;
    }
    uint64_t t3 = mach_absolute_time(); 
    [g_currentEncoder
        executeCommandsInBuffer:g_icb
                      withRange:NSMakeRange(0, (NSUInteger)icbIdx)];
    g_drawCallCount += icbIdx;
    uint64_t t4 = mach_absolute_time(); 
    t_acc_resolve += (t1 - t0);
    t_acc_jni += (t1 - t0);
    t_acc_classify += (t2 - t1);
    t_acc_icb_encode += (t3 - t2);
    t_acc_icb_exec += (t4 - t3);
    t_acc_total += (t4 - t0);
    t_acc_frames++;
    t_acc_draws += validCount;
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }
    int nonMegaCount = validCount - megaCount;
    if (nonMegaCount > 0) {
      for (int i = 0; i < validCount; i++) {
        const DrawCmd &cmd = cmds[i];
        if (cmd.isMega)
          continue;
        ResolvedBuf vbRes = resolve_buffer(cmd.bufHandle);
        if (vbRes.buf) {
          [g_currentEncoder setVertexBuffer:vbRes.buf
                                     offset:(NSUInteger)vbRes.offset
                                    atIndex:0];
          [g_currentEncoder
              drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:(NSUInteger)cmd.idxCount
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:0
                       baseInstance:(NSUInteger)i]; 
        }
        g_drawCallCount++;
      }
      if (g_megaVB) {
        [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
    }
  } else {
    for (int i = 0; i < validCount; i++) {
      const DrawCmd &cmd = cmds[i];
      if (cmd.isMega) {
        [g_currentEncoder
            drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                       indexCount:(NSUInteger)cmd.idxCount
                        indexType:MTLIndexTypeUInt32
                      indexBuffer:ib
                indexBufferOffset:ibOffset
                    instanceCount:1
                       baseVertex:(NSInteger)(cmd.megaOffset / VERTEX_STRIDE)
                     baseInstance:(NSUInteger)i];
      } else {
        ResolvedBuf vbRes = resolve_buffer(cmd.bufHandle);
        if (vbRes.buf) {
          [g_currentEncoder setVertexBuffer:vbRes.buf
                                     offset:(NSUInteger)vbRes.offset
                                    atIndex:0];
          [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                       indexCount:(NSUInteger)cmd.idxCount
                                        indexType:MTLIndexTypeUInt32
                                      indexBuffer:ib
                                indexBufferOffset:ibOffset
                                    instanceCount:1
                                       baseVertex:0
                                     baseInstance:(NSUInteger)i];
          if (g_megaVB) {
            [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
          }
        }
      }
      g_drawCallCount++;
    }
  }
  if (cmds != stackCmds)
    delete[] cmds;
  if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
    dbg("DrawBatch: total=%d valid=%d mega=%d nonMega=%d icb=%s "
        "megaVBUsed=%zuMB\n",
        drawCount, validCount, megaCount, validCount - megaCount,
        canICB ? "YES" : "NO", g_megaVBHead / (1024 * 1024));
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRegisterChunkMesh(
    JNIEnv *, jclass, jint cx, jint cy, jint cz, jlong bufferHandle,
    jint quadCount, jint opaqueQuadCount, jint lodLevel) {
  int64_t key = packMeshKey(cx, cy, cz);
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);
  auto it = g_meshKeyToIdx.find(key);
  if (it != g_meshKeyToIdx.end()) {
    NativeMesh &m = g_nativeMeshes[it->second];
    m.bufferHandle = (uint64_t)bufferHandle;
    m.quadCount = quadCount;
    m.opaqueQuadCount = opaqueQuadCount;
    m.lodLevel = lodLevel;
    m.active = true;
    return;
  }
  size_t idx;
  if (!g_meshFreeSlots.empty()) {
    idx = g_meshFreeSlots.back();
    g_meshFreeSlots.pop_back();
  } else {
    idx = g_nativeMeshes.size();
    g_nativeMeshes.push_back({});
  }
  g_nativeMeshes[idx] = {(int32_t)cx,        (int32_t)cy,
                         (int32_t)cz,        (uint64_t)bufferHandle,
                         (int32_t)quadCount, (int32_t)opaqueQuadCount,
                         (int32_t)lodLevel,  true};
  g_meshKeyToIdx[key] = idx;
  g_activeMeshCount++;
  if (g_activeMeshCount <= 5 || g_activeMeshCount % 2000 == 0) {
    dbg("MeshReg: registered (%d,%d,%d) handle=%llu total=%d\n", cx, cy, cz,
        (unsigned long long)bufferHandle, g_activeMeshCount);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUnregisterChunkMesh(
    JNIEnv *, jclass, jint cx, jint cy, jint cz) {
  int64_t key = packMeshKey(cx, cy, cz);
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);
  auto it = g_meshKeyToIdx.find(key);
  if (it == g_meshKeyToIdx.end())
    return;
  size_t idx = it->second;
  g_nativeMeshes[idx].active = false;
  g_meshFreeSlots.push_back(idx);
  g_meshKeyToIdx.erase(it);
  g_activeMeshCount--;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawAllVisibleChunks(
    JNIEnv *, jclass, jlong frameContext, jlong indexBuffer) {
  (void)frameContext;
  uint64_t _prof_t0 = mach_absolute_time();
  if (!g_currentEncoder)
    return 0;
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!ibRes.buf)
    return 0;
  id<MTLBuffer> ib = ibRes.buf;
  NSUInteger ibOffset = (NSUInteger)ibRes.offset;
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return 0;
  float vp[16];
  for (int c = 0; c < 4; c++) {
    for (int r = 0; r < 4; r++) {
      float sum = 0;
      for (int k = 0; k < 4; k++) {
        sum += g_projMatrix[k * 4 + r] * g_mvMatrix[c * 4 + k];
      }
      vp[c * 4 + r] = sum;
    }
  }
  float frustumPlanes[24];
  extractFrustumPlanes(vp, frustumPlanes);
  float camX = g_camX, camY = g_camY, camZ = g_camZ;
  static const int VERTEX_STRIDE = 16; 
  struct DrawCmd {
    uint64_t bufHandle;
    size_t megaOffset;
    int idxCount;
    int opaqueIdxCount; 
    float distSq;
    float ox, oy, oz;
    bool isMega;
  };
  static DrawCmd *s_cmds = nullptr;
  static int s_cmdsCapacity = 0;
  int meshCount;
  {
    std::shared_lock<std::shared_mutex> lock(g_meshRegMutex);
    meshCount = (int)g_nativeMeshes.size();
  }
  if (meshCount == 0)
    return 0;
  if (s_cmdsCapacity < meshCount) {
    delete[] s_cmds;
    s_cmdsCapacity = meshCount * 2;
    s_cmds = new DrawCmd[s_cmdsCapacity];
  }
  int validCount = 0;
  int megaCount = 0;
  {
    std::shared_lock<std::shared_mutex> regLock(g_meshRegMutex);
    std::shared_lock<std::shared_mutex> megaLock(g_megaMutex);
    for (int i = 0; i < (int)g_nativeMeshes.size(); i++) {
      const NativeMesh &nm = g_nativeMeshes[i];
      if (!nm.active || nm.quadCount <= 0 || nm.bufferHandle == 0)
        continue;
      float ox = nm.chunkX * 16.0f - camX;
      float oy = nm.chunkY * 16.0f - camY;
      float oz = nm.chunkZ * 16.0f - camZ;
      if (!frustumTestAABB(frustumPlanes, ox, oy, oz, ox + 16.0f, oy + 16.0f,
                           oz + 16.0f))
        continue;
      float cx = ox + 8.0f, cy = oy + 8.0f, cz = oz + 8.0f;
      int idxCount = nm.quadCount * 6;
      int opaqueIdxCount = nm.opaqueQuadCount * 6;
      float distSq = cx * cx + cy * cy + cz * cz;
      bool mega = isMegaHandle(nm.bufferHandle);
      if (mega) {
        auto it = g_megaAllocs.find(nm.bufferHandle);
        if (it == g_megaAllocs.end())
          continue;
        s_cmds[validCount] = {nm.bufferHandle,
                              it->second.offset,
                              idxCount,
                              opaqueIdxCount,
                              distSq,
                              ox,
                              oy,
                              oz,
                              true};
        megaCount++;
      } else {
        s_cmds[validCount] = {nm.bufferHandle,
                              0,
                              idxCount,
                              opaqueIdxCount,
                              distSq,
                              ox,
                              oy,
                              oz,
                              false};
      }
      validCount++;
    }
  } 
  if (validCount == 0)
    return 0;
  int preCullCount = validCount; 
  static int hiz_culled_acc2 = 0, hiz_total_acc2 = 0;
  if (g_hizReadbackBuf && g_hizReadbackW > 0 && g_hizReadbackH > 0 &&
      g_frameCount > 10) {
    const float *hizData = (const float *)[g_hizReadbackBuf contents];
    int hW = g_hizReadbackW;
    int hH = g_hizReadbackH;
    float screenW = (float)(g_rtWidth * g_scale);
    float screenH = (float)(g_rtHeight * g_scale);
    float texelCoverage = (float)(1 << (HIZ_READBACK_MIP + 1));
    NSUInteger bytesPerRow = (NSUInteger)(g_hizReadbackW * 4 + 255) & ~255;
    int floatsPerRow = (int)(bytesPerRow / 4);
    const float DEPTH_BIAS =
        0.005f; 
    int newValid = 0;
    int occluded = 0;
    for (int i = 0; i < validCount; i++) {
      float minX = s_cmds[i].ox, minY = s_cmds[i].oy, minZ = s_cmds[i].oz;
      float maxX = minX + 16.0f, maxY = minY + 16.0f, maxZ = minZ + 16.0f;
      float ssMinX = 1e10f, ssMinY = 1e10f;
      float ssMaxX = -1e10f, ssMaxY = -1e10f;
      float nearestDepth = 1.0f;
      bool anyBehind = false;
      for (int c = 0; c < 8; c++) {
        float px = (c & 1) ? maxX : minX;
        float py = (c & 2) ? maxY : minY;
        float pz = (c & 4) ? maxZ : minZ;
        float cw = g_vpMatrix[3] * px + g_vpMatrix[7] * py +
                   g_vpMatrix[11] * pz + g_vpMatrix[15];
        if (cw <= 0.01f) {
          anyBehind = true;
          break;
        }
        float clipX = g_vpMatrix[0] * px + g_vpMatrix[4] * py +
                      g_vpMatrix[8] * pz + g_vpMatrix[12];
        float clipY = g_vpMatrix[1] * px + g_vpMatrix[5] * py +
                      g_vpMatrix[9] * pz + g_vpMatrix[13];
        float clipZ = g_vpMatrix[2] * px + g_vpMatrix[6] * py +
                      g_vpMatrix[10] * pz + g_vpMatrix[14];
        float ndcX = clipX / cw;
        float ndcY = clipY / cw;
        float ndcZ = clipZ / cw;
        float sx = (ndcX * 0.5f + 0.5f) * screenW;
        float sy = (1.0f - (ndcY * 0.5f + 0.5f)) * screenH;
        ssMinX = std::min(ssMinX, sx);
        ssMinY = std::min(ssMinY, sy);
        ssMaxX = std::max(ssMaxX, sx);
        ssMaxY = std::max(ssMaxY, sy);
        nearestDepth = std::min(nearestDepth, ndcZ);
      }
      if (anyBehind) {
        s_cmds[newValid++] = s_cmds[i];
        continue;
      }
      ssMinX = std::max(0.0f, std::min(ssMinX, screenW));
      ssMinY = std::max(0.0f, std::min(ssMinY, screenH));
      ssMaxX = std::max(0.0f, std::min(ssMaxX, screenW));
      ssMaxY = std::max(0.0f, std::min(ssMaxY, screenH));
      if (ssMaxX <= ssMinX || ssMaxY <= ssMinY) {
        occluded++;
        if (s_cmds[i].isMega)
          megaCount--;
        continue;
      }
      float maxHizDepth = 0.0f;
      float coords[4][2] = {{ssMinX, ssMinY},
                            {ssMaxX, ssMinY},
                            {ssMinX, ssMaxY},
                            {ssMaxX, ssMaxY}};
      bool validSample = false;
      for (int s = 0; s < 4; s++) {
        int hx =
            std::max(0, std::min((int)(coords[s][0] / texelCoverage), hW - 1));
        int hy =
            std::max(0, std::min((int)(coords[s][1] / texelCoverage), hH - 1));
        float d = hizData[hy * floatsPerRow + hx];
        if (d > 0.001f) {
          maxHizDepth = std::max(maxHizDepth, d);
          validSample = true;
        }
      }
      if (validSample && nearestDepth > maxHizDepth + DEPTH_BIAS) {
        occluded++;
        if (s_cmds[i].isMega)
          megaCount--;
        continue;
      }
      s_cmds[newValid++] = s_cmds[i];
    }
    hiz_culled_acc2 += occluded;
    hiz_total_acc2 += preCullCount;
    validCount = newValid;
    if (g_frameCount % 120 == 0 && hiz_total_acc2 > 0) {
      dbg("V18_HiZ: culled %d/%d (%.0f%%) frustum=%d drawn=%d over 120f\n",
          hiz_culled_acc2, hiz_total_acc2,
          100.0 * hiz_culled_acc2 / hiz_total_acc2, hiz_total_acc2 / 120,
          (hiz_total_acc2 - hiz_culled_acc2) / 120);
      hiz_culled_acc2 = 0;
      hiz_total_acc2 = 0;
    }
  }
  if (validCount == 0)
    return 0;
  if (validCount > 1) {
    std::sort(
        s_cmds, s_cmds + validCount,
        [](const DrawCmd &a, const DrawCmd &b) { return a.distSq < b.distSq; });
  }
  static id<MTLBuffer> g_v18OffsetBuf = nil;
  static size_t g_v18OffsetCap = 0;
  size_t offsetBufSize = (size_t)validCount * 16;
  if (!g_v18OffsetBuf || g_v18OffsetCap < offsetBufSize) {
    g_v18OffsetCap = offsetBufSize * 2;
    g_v18OffsetBuf =
        [g_device newBufferWithLength:g_v18OffsetCap
                              options:MTLResourceStorageModeShared];
  }
  float *offBuf = (float *)[g_v18OffsetBuf contents];
  for (int i = 0; i < validCount; i++) {
    offBuf[i * 4 + 0] = s_cmds[i].ox;
    offBuf[i * 4 + 1] = s_cmds[i].oy;
    offBuf[i * 4 + 2] = s_cmds[i].oz;
    float cx = s_cmds[i].ox + 8.0f; 
    float cy = s_cmds[i].oy + 8.0f;
    float cz = s_cmds[i].oz + 8.0f;
    uint32_t faceMask = 0x3F; 
    const float margin = 8.0f;
    if (cy > margin)
      faceMask &= ~(1u << 1); 
    if (cy < -margin)
      faceMask &= ~(1u << 0); 
    if (cz < -margin)
      faceMask &= ~(1u << 2); 
    if (cz > margin)
      faceMask &= ~(1u << 3); 
    if (cx < -margin)
      faceMask &= ~(1u << 4); 
    if (cx > margin)
      faceMask &= ~(1u << 5); 
    float maskAsFloat;
    memcpy(&maskAsFloat, &faceMask, sizeof(float));
    offBuf[i * 4 + 3] = maskAsFloat;
  }
  if (g_megaVB) {
    [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
  }
  [g_currentEncoder setVertexBuffer:g_v18OffsetBuf offset:0 atIndex:4];
  bool canICB = (megaCount > 0 && g_megaVB && g_pipelineInhouseICB &&
                 g_fragArgBuf && g_fragArgEncoder && g_blockAtlas);
  if (canICB) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICB];
    g_currentPipeline = g_pipelineInhouseICB;
    [g_fragArgEncoder setArgumentBuffer:g_fragArgBuf offset:0];
    [g_fragArgEncoder setTexture:g_blockAtlas atIndex:0];
    [g_currentEncoder setFragmentBuffer:g_fragArgBuf offset:0 atIndex:0];
    [g_currentEncoder useResource:g_blockAtlas
                            usage:MTLResourceUsageRead
                           stages:MTLRenderStageFragment];
    if (!g_icb || g_icbMaxCommands < (NSUInteger)megaCount) {
      NSUInteger newSize = MAX(ICB_INITIAL_SIZE, (NSUInteger)megaCount * 2);
      MTLIndirectCommandBufferDescriptor *desc =
          [MTLIndirectCommandBufferDescriptor new];
      desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
      desc.inheritPipelineState = YES;
      desc.inheritBuffers = YES;
      g_icb = [g_device
          newIndirectCommandBufferWithDescriptor:desc
                                 maxCommandCount:newSize
                                         options:MTLResourceStorageModeShared];
      g_icbMaxCommands = newSize;
    }
    [g_icb resetWithRange:NSMakeRange(0, (NSUInteger)megaCount)];
    int icbIdx = 0;
    for (int i = 0; i < validCount; i++) {
      if (!s_cmds[i].isMega)
        continue;
      int opaqueIdx = s_cmds[i].opaqueIdxCount;
      if (opaqueIdx <= 0) {
        icbIdx++; 
        id<MTLIndirectRenderCommand> icmd =
            [g_icb indirectRenderCommandAtIndex:(NSUInteger)(icbIdx - 1)];
        [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:0
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:0
                       baseInstance:(NSUInteger)i];
        continue;
      }
      id<MTLIndirectRenderCommand> icmd =
          [g_icb indirectRenderCommandAtIndex:(NSUInteger)icbIdx];
      [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                       indexCount:(NSUInteger)opaqueIdx
                        indexType:MTLIndexTypeUInt32
                      indexBuffer:ib
                indexBufferOffset:ibOffset
                    instanceCount:1
                       baseVertex:(NSInteger)(s_cmds[i].megaOffset /
                                              VERTEX_STRIDE)
                     baseInstance:(NSUInteger)i];
      icbIdx++;
    }
    [g_currentEncoder
        executeCommandsInBuffer:g_icb
                      withRange:NSMakeRange(0, (NSUInteger)icbIdx)];
    g_drawCallCount += icbIdx;
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }
    for (int i = 0; i < validCount; i++) {
      if (s_cmds[i].isMega)
        continue;
      int opaqueIdx = s_cmds[i].opaqueIdxCount;
      if (opaqueIdx <= 0)
        continue;
      ResolvedBuf vbRes = resolve_buffer(s_cmds[i].bufHandle);
      if (vbRes.buf) {
        [g_currentEncoder setVertexBuffer:vbRes.buf
                                   offset:(NSUInteger)vbRes.offset
                                  atIndex:0];
        [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                     indexCount:(NSUInteger)opaqueIdx
                                      indexType:MTLIndexTypeUInt32
                                    indexBuffer:ib
                              indexBufferOffset:ibOffset
                                  instanceCount:1
                                     baseVertex:0
                                   baseInstance:(NSUInteger)i];
      }
      g_drawCallCount++;
    }
    if (g_megaVB) {
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    }
  } else {
    for (int i = 0; i < validCount; i++) {
      int opaqueIdx = s_cmds[i].opaqueIdxCount;
      if (opaqueIdx <= 0)
        continue;
      if (s_cmds[i].isMega) {
        [g_currentEncoder
            drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                       indexCount:(NSUInteger)opaqueIdx
                        indexType:MTLIndexTypeUInt32
                      indexBuffer:ib
                indexBufferOffset:ibOffset
                    instanceCount:1
                       baseVertex:(NSInteger)(s_cmds[i].megaOffset /
                                              VERTEX_STRIDE)
                     baseInstance:(NSUInteger)i];
      } else {
        ResolvedBuf vbRes = resolve_buffer(s_cmds[i].bufHandle);
        if (vbRes.buf) {
          [g_currentEncoder setVertexBuffer:vbRes.buf
                                     offset:(NSUInteger)vbRes.offset
                                    atIndex:0];
          [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                       indexCount:(NSUInteger)opaqueIdx
                                        indexType:MTLIndexTypeUInt32
                                      indexBuffer:ib
                                indexBufferOffset:ibOffset
                                    instanceCount:1
                                       baseVertex:0
                                     baseInstance:(NSUInteger)i];
          if (g_megaVB) {
            [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
          }
        }
      }
      g_drawCallCount++;
    }
  }
  int waterDraws = 0;
  for (int i = 0; i < validCount; i++) {
    int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
    if (waterIdxCount <= 0)
      continue;
    waterDraws++;
  }
  if (waterDraws > 0 && g_depthStateNoWrite) {
    [g_currentEncoder setDepthStencilState:g_depthStateNoWrite];
    if (g_megaVB) {
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    }
    for (int i = 0; i < validCount; i++) {
      int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
      if (waterIdxCount <= 0)
        continue;
      int opaqueVertCount = s_cmds[i].opaqueIdxCount / 6 * 4;
      if (s_cmds[i].isMega) {
        [g_currentEncoder
            drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                       indexCount:(NSUInteger)waterIdxCount
                        indexType:MTLIndexTypeUInt32
                      indexBuffer:ib
                indexBufferOffset:ibOffset
                    instanceCount:1
                       baseVertex:(NSInteger)(s_cmds[i].megaOffset /
                                              VERTEX_STRIDE) +
                                  opaqueVertCount
                     baseInstance:(NSUInteger)i];
      } else {
        ResolvedBuf vbRes = resolve_buffer(s_cmds[i].bufHandle);
        if (vbRes.buf) {
          [g_currentEncoder setVertexBuffer:vbRes.buf
                                     offset:(NSUInteger)vbRes.offset
                                    atIndex:0];
          [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                       indexCount:(NSUInteger)waterIdxCount
                                        indexType:MTLIndexTypeUInt32
                                      indexBuffer:ib
                                indexBufferOffset:ibOffset
                                    instanceCount:1
                                       baseVertex:opaqueVertCount
                                     baseInstance:(NSUInteger)i];
          if (g_megaVB) {
            [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
          }
        }
      }
      g_drawCallCount++;
    }
    [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (g_frameCount < 5 || g_frameCount % 600 == 0) {
    float gpuMs2 = g_lastGpuMs.load(std::memory_order_relaxed);
    dbg("V18_Draw: meshes=%d visible=%d mega=%d icb=%s gpu=%.1fms\n",
        g_activeMeshCount, validCount, megaCount, canICB ? "Y" : "N", gpuMs2);
  }
  g_prof_drawAll_acc += (mach_absolute_time() - _prof_t0);
  return (jint)validCount;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jint vertexCount,
    jint baseVertex) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
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
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf) {
    dbg("nDrawLineBuffer: buffer lookup failed\n");
    return;
  }
  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];
  if (g_depthStateLessEqual)
    [g_currentEncoder setDepthStencilState:g_depthStateLessEqual];
  [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
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
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawTriangleBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jint vertexCount) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0 || !g_pipelineDebugLines)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf) return;
  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];
  if (g_depthStateLessEqual)
    [g_currentEncoder setDepthStencilState:g_depthStateLessEqual];
  [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:0
                       vertexCount:(NSUInteger)vertexCount];
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
    if (g_frameSemaphore && !g_shuttingDown) {
      dispatch_semaphore_wait(g_frameSemaphore, DISPATCH_TIME_FOREVER);
    }
    g_currentFrameReady = false; 
    g_currentCmdBuffer = [[g_queue commandBuffer] retain];
    MTLRenderPassDescriptor *rp =
        [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = g_color;
    bool reuseFrame = g_reuseTerrainFrame;
    g_reuseTerrainFrame = false; 
    rp.colorAttachments[0].loadAction =
        reuseFrame ? MTLLoadActionLoad : MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    rp.depthAttachment.texture = g_depth;
    rp.depthAttachment.loadAction =
        reuseFrame ? MTLLoadActionLoad : MTLLoadActionClear;
    rp.depthAttachment.storeAction = MTLStoreActionStore;
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
  uint64_t _prof_ef_t0 = mach_absolute_time();
  g_frameCount++;
  static uint64_t ft_last = 0;
  static uint64_t ft_acc = 0;
  static int ft_count = 0;
  uint64_t ft_now = mach_absolute_time();
  if (ft_last > 0) {
    ft_acc += (ft_now - ft_last);
    ft_count++;
  }
  ft_last = ft_now;
  if (ft_count > 0 && (g_frameCount % 120 == 0)) {
    mach_timebase_info_data_t tbi;
    mach_timebase_info(&tbi);
    double avg_ms =
        (double)(ft_acc / ft_count) * tbi.numer / tbi.denom / 1000000.0;
    double avg_fps = (avg_ms > 0) ? (1000.0 / avg_ms) : 0;
    dbg("FRAME_TIMING: avg=%.2fms (%.1f FPS) over %d frames, draws=%d\n",
        avg_ms, avg_fps, ft_count, g_drawCallCount);
    ft_acc = 0;
    ft_count = 0;
  }
  if (g_frameCount <= 5 || g_frameCount % 500 == 0) {
    dbg("EndFrame #%d: draws=%d skips=%d encoder=%p pipeline=%p "
        "pipelineInhouse=%p texture=%dx%d proj[0]=%.3f mv[0]=%.3f "
        "cam=%.1f,%.1f,%.1f gpuDriven=%d meshShaders=%d\n",
        g_frameCount, g_drawCallCount, g_drawSkipCount, g_currentEncoder,
        g_currentPipeline, g_pipelineInhouse, g_color ? (int)g_color.width : 0,
        g_color ? (int)g_color.height : 0, g_projMatrix[0], g_mvMatrix[0],
        g_camX, g_camY, g_camZ, g_gpuDrivenEnabled ? 1 : 0,
        g_meshShadersActive ? 1 : 0);
  }
  g_drawCallCount = 0;
  g_drawSkipCount = 0;
  if (g_currentEncoder) {
    [g_currentEncoder endEncoding];
    [g_currentEncoder release];
    g_currentEncoder = nil;
  }
  if (g_currentCmdBuffer && g_depth && g_hizPyramid &&
      g_hizDownsamplePipeline && g_hizMipCount > 0) {
    id<MTLComputeCommandEncoder> hizEnc =
        [g_currentCmdBuffer computeCommandEncoder];
    if (hizEnc) {
      [hizEnc setComputePipelineState:g_hizDownsamplePipeline];
      int srcW = (int)g_depth.width;
      int srcH = (int)g_depth.height;
      for (uint32_t mip = 0; mip < g_hizMipCount; mip++) {
        int dstW = std::max(1, srcW / 2);
        int dstH = std::max(1, srcH / 2);
        if (!g_hizViewsValid && mip < 16) {
          for (uint32_t m = 0; m < g_hizMipCount && m < 16; m++) {
            if (m > 0 && !g_hizSrcViews[m]) {
              g_hizSrcViews[m] = [g_hizPyramid
                  newTextureViewWithPixelFormat:MTLPixelFormatR32Float
                                    textureType:MTLTextureType2D
                                         levels:NSMakeRange(m - 1, 1)
                                         slices:NSMakeRange(0, 1)];
            }
            if (!g_hizDstViews[m]) {
              g_hizDstViews[m] = [g_hizPyramid
                  newTextureViewWithPixelFormat:MTLPixelFormatR32Float
                                    textureType:MTLTextureType2D
                                         levels:NSMakeRange(m, 1)
                                         slices:NSMakeRange(0, 1)];
            }
          }
          g_hizViewsValid = 1;
        }
        id<MTLTexture> srcTex = (mip == 0) ? g_depth : g_hizSrcViews[mip];
        id<MTLTexture> dstTex = g_hizDstViews[mip];
        if (!srcTex || !dstTex)
          continue;
        struct {
          uint32_t srcW, srcH, dstW, dstH, mipLevel, _pad0, _pad1, _pad2;
        } params = {(uint32_t)srcW,
                    (uint32_t)srcH,
                    (uint32_t)dstW,
                    (uint32_t)dstH,
                    mip,
                    0,
                    0,
                    0};
        [hizEnc setTexture:srcTex atIndex:0];
        [hizEnc setTexture:dstTex atIndex:1];
        [hizEnc setBytes:&params length:sizeof(params) atIndex:0];
        NSUInteger tgW = g_hizDownsamplePipeline.threadExecutionWidth;
        NSUInteger tgH =
            g_hizDownsamplePipeline.maxTotalThreadsPerThreadgroup / tgW;
        MTLSize threadgroups =
            MTLSizeMake((dstW + tgW - 1) / tgW, (dstH + tgH - 1) / tgH, 1);
        MTLSize threadsPerGroup = MTLSizeMake(tgW, tgH, 1);
        [hizEnc dispatchThreadgroups:threadgroups
               threadsPerThreadgroup:threadsPerGroup];
        srcW = dstW;
        srcH = dstH;
      }
      [hizEnc endEncoding];
      if (g_frameCount <= 3) {
        dbg("Hi-Z pyramid generated: %d mips from %dx%d depth\n", g_hizMipCount,
            (int)g_depth.width, (int)g_depth.height);
      }
      if (g_hizMipCount > HIZ_READBACK_MIP) {
        int mipW = std::max(1, (int)g_hizPyramid.width >> HIZ_READBACK_MIP);
        int mipH = std::max(1, (int)g_hizPyramid.height >> HIZ_READBACK_MIP);
        NSUInteger bytesPerRow = (NSUInteger)(mipW * 4 + 255) & ~255;
        NSUInteger bufSize = bytesPerRow * mipH;
        if (!g_hizReadbackBuf || g_hizReadbackBuf.length < bufSize) {
          g_hizReadbackBuf =
              [g_device newBufferWithLength:bufSize
                                    options:MTLResourceStorageModeShared];
        }
        if (g_hizReadbackBuf) {
          id<MTLBlitCommandEncoder> blitEnc =
              [g_currentCmdBuffer blitCommandEncoder];
          [blitEnc copyFromTexture:g_hizPyramid
                           sourceSlice:0
                           sourceLevel:HIZ_READBACK_MIP
                          sourceOrigin:MTLOriginMake(0, 0, 0)
                            sourceSize:MTLSizeMake(mipW, mipH, 1)
                              toBuffer:g_hizReadbackBuf
                     destinationOffset:0
                destinationBytesPerRow:bytesPerRow
              destinationBytesPerImage:bufSize];
          [blitEnc endEncoding];
          g_hizReadbackW = mipW;
          g_hizReadbackH = mipH;
          for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
              float sum = 0;
              for (int k = 0; k < 4; k++) {
                sum += g_projMatrix[k * 4 + r] * g_mvMatrix[c * 4 + k];
              }
              g_vpMatrix[c * 4 + r] = sum;
            }
          }
          if (g_frameCount <= 3) {
            dbg("Hi-Z readback: mip %d → %dx%d, bytesPerRow=%lu\n",
                HIZ_READBACK_MIP, mipW, mipH, (unsigned long)bytesPerRow);
          }
        }
      }
    }
  }
  if (g_currentCmdBuffer) {
    if (g_frameEvent) {
      g_eventCounter++;
      [g_currentCmdBuffer encodeSignalEvent:g_frameEvent value:g_eventCounter];
    }
    if (g_depthCmdBuffer) {
      [g_depthCmdBuffer release];
      g_depthCmdBuffer = nil;
    }
    g_depthCmdBuffer = [g_currentCmdBuffer retain];
    {
      static uint64_t gpu_acc = 0;
      static int gpu_frame_count = 0;
      [g_currentCmdBuffer addCompletedHandler:^(id<MTLCommandBuffer> cb) {
        g_currentFrameReady = true;
        if (g_frameSemaphore) {
          dispatch_semaphore_signal(g_frameSemaphore);
        }
        if (@available(macOS 10.15, *)) {
          CFTimeInterval gpuStart = cb.GPUStartTime;
          CFTimeInterval gpuEnd = cb.GPUEndTime;
          if (gpuStart > 0 && gpuEnd > gpuStart) {
            double gpuMs = (gpuEnd - gpuStart) * 1000.0;
            g_lastGpuMs.store((float)gpuMs, std::memory_order_relaxed);
            uint64_t gpuUs = (uint64_t)(gpuMs * 1000.0);
            gpu_acc += gpuUs;
            gpu_frame_count++;
            if (gpu_frame_count % 120 == 0) {
              double avg = (double)gpu_acc / gpu_frame_count;
              dbg("GPU_TIMING: avg=%.2fms (%.1f max-FPS) over %d frames\n",
                  avg / 1000.0, 1000000.0 / avg, gpu_frame_count);
              gpu_acc = 0;
              gpu_frame_count = 0;
            }
          }
        }
      }];
    }
    [g_currentCmdBuffer commit];
    g_currentBufferIndex = (g_currentBufferIndex + 1) % kTripleBufferCount;
    [g_currentCmdBuffer release];
    g_currentCmdBuffer = nil;
  }
  g_currentPipeline = nil;
  double now = CFAbsoluteTimeGetCurrent();
  if (now - g_lastThermalCheckTime > 1.0) {
    g_lastThermalCheckTime = now;
    NSProcessInfoThermalState state =
        [[NSProcessInfo processInfo] thermalState];
    g_thermalState = (int)state;
    if (state >= NSProcessInfoThermalStateCritical) {
      g_lodRadiusReduction = 50; 
      if (g_frameCount % 60 == 0)
        dbg("THERMAL: Critical! LOD radius -50%%\n");
    } else if (state >= NSProcessInfoThermalStateSerious) {
      g_lodRadiusReduction = 25;
      if (g_frameCount % 300 == 0)
        dbg("THERMAL: Serious. LOD radius -25%%\n");
    } else {
      g_lodRadiusReduction = 0;
    }
  }
  {
    std::lock_guard<std::mutex> lock(g_deferredMutex);
    int freed = 0;
    auto it = g_deferredDeletions.begin();
    while (it != g_deferredDeletions.end()) {
      if (g_frameCount - it->frameQueued >= DEFERRED_FRAME_DELAY) {
        if (it->isMega) {
          megaFree(it->handle);
        } else {
          std::unique_lock<std::shared_mutex> bufLock(g_bufferMutex);
          auto bufIt = g_buffers.find(it->handle);
          if (bufIt != g_buffers.end()) {
            g_buffers.erase(bufIt);
          }
        }
        it = g_deferredDeletions.erase(it);
        freed++;
      } else {
        ++it;
      }
    }
    if (freed > 0 && (g_frameCount % 300 == 0)) {
      dbg("DeferredDelete: freed %d buffers (%zu pending)\n", freed,
          g_deferredDeletions.size());
    }
  }
  g_prof_endFrame_acc += (mach_absolute_time() - _prof_ef_t0);
  g_prof_count++;
  if (g_prof_count > 0 && (g_frameCount % 120 == 0)) {
    mach_timebase_info_data_t tbi;
    mach_timebase_info(&tbi);
    double ns_per_tick = (double)tbi.numer / tbi.denom;
    double drawAll_ms =
        (double)g_prof_drawAll_acc / g_prof_count * ns_per_tick / 1e6;
    double endFrame_ms =
        (double)g_prof_endFrame_acc / g_prof_count * ns_per_tick / 1e6;
    double waitRender_ms =
        (double)g_prof_waitRender_acc / g_prof_count * ns_per_tick / 1e6;
    double cglBind_ms =
        (double)g_prof_cglBind_acc / g_prof_count * ns_per_tick / 1e6;
    dbg("PROFILE: drawAll=%.2fms endFrame=%.2fms waitRender=%.2fms "
        "cglBind=%.2fms (avg over %d frames)\n",
        drawAll_ms, endFrame_ms, waitRender_ms, cglBind_ms, g_prof_count);
    g_prof_drawAll_acc = 0;
    g_prof_endFrame_acc = 0;
    g_prof_waitRender_acc = 0;
    g_prof_cglBind_acc = 0;
    g_prof_count = 0;
  }
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
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUpdateTexture2D(
    JNIEnv *env, jclass, jlong textureHandle, jint width, jint height,
    jbyteArray pixelData) {
  if (!textureHandle || width <= 0 || height <= 0)
    return;
  id<MTLTexture> tex = (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (!tex)
    return;
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
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsFrameReady(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return g_currentFrameReady ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nWaitForRender(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  uint64_t _prof_wr_t0 = mach_absolute_time();
  if (!g_currentFrameReady && g_depthCmdBuffer) {
    [g_depthCmdBuffer waitUntilCompleted];
  }
  g_prof_waitRender_acc += (mach_absolute_time() - _prof_wr_t0);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindIOSurfaceToTexture(
    JNIEnv *, jclass, jlong handle, jint glTexture) {
  (void)handle;
  uint64_t _prof_cgl_t0 = mach_absolute_time();
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
  g_prof_cglBind_acc += (mach_absolute_time() - _prof_cgl_t0);
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
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
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
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
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
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!vbRes.buf || !ibRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  id<MTLBuffer> ib = ibRes.buf;
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
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                              length:sizeof(g_entityOverlayParams)
                             atIndex:5];
  [g_currentEncoder setCullMode:MTLCullModeNone];
  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)(ibRes.offset +
                                         (size_t)baseIndex * sizeof(uint32_t))];
  [g_currentEncoder setCullMode:MTLCullModeBack];
  g_drawCallCount++;
}
struct CameraUniformsCPU {
  float viewProjection[16]; 
  float projection[16];     
  float modelView[16];      
  float cameraPosition[4];  
  float frustumPlanes[24];  
  float screenSize[2];      
  float nearPlane;          
  float farPlane;           
  uint32_t frameIndex;      
  uint32_t hizMipCount;     
  uint32_t totalChunks;     
  uint32_t _pad;            
};
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadCameraUniforms(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj, jfloatArray proj,
    jfloatArray modelView, jfloatArray cameraPos, jfloatArray frustumPlanes,
    jfloat screenW, jfloat screenH, jfloat nearPlane, jfloat farPlane,
    jint totalChunks) {
  (void)handle;
  int bufIdx = g_currentBufferIndex % kTripleBufferCount;
  id<MTLBuffer> buf = g_tripleBuffers[bufIdx];
  if (!buf)
    return;
  CameraUniformsCPU *u = (CameraUniformsCPU *)[buf contents];
  if (viewProj && env->GetArrayLength(viewProj) >= 16)
    env->GetFloatArrayRegion(viewProj, 0, 16, u->viewProjection);
  if (proj && env->GetArrayLength(proj) >= 16)
    env->GetFloatArrayRegion(proj, 0, 16, u->projection);
  if (modelView && env->GetArrayLength(modelView) >= 16)
    env->GetFloatArrayRegion(modelView, 0, 16, u->modelView);
  if (cameraPos && env->GetArrayLength(cameraPos) >= 4)
    env->GetFloatArrayRegion(cameraPos, 0, 4, u->cameraPosition);
  if (frustumPlanes && env->GetArrayLength(frustumPlanes) >= 24)
    env->GetFloatArrayRegion(frustumPlanes, 0, 24, u->frustumPlanes);
  u->screenSize[0] = screenW;
  u->screenSize[1] = screenH;
  u->nearPlane = nearPlane;
  u->farPlane = farPlane;
  u->frameIndex = (uint32_t)g_frameCount;
  u->hizMipCount = g_hizMipCount;
  u->totalChunks = (uint32_t)totalChunks;
  u->_pad = 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadSubChunkData(
    JNIEnv *env, jclass, jlong handle, jobject directBuffer, jint count) {
  (void)handle;
  if (!g_device || !directBuffer || count <= 0)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!ptr)
    return;
  size_t entrySize = 48;
  size_t totalSize = (size_t)count * entrySize;
  if ((size_t)cap < totalSize)
    return;
  if (!g_subChunkBuffer || g_subChunkBuffer.length < totalSize) {
    g_subChunkBuffer =
        [g_device newBufferWithLength:totalSize
                              options:MTLResourceStorageModeShared];
  }
  memcpy([g_subChunkBuffer contents], ptr, totalSize);
  g_gpuSubChunkCount = (uint32_t)count;
  size_t argsSize = (size_t)count * sizeof(uint32_t) * 4;
  if (!g_cullDrawArgsBuffer || g_cullDrawArgsBuffer.length < argsSize) {
    g_cullDrawArgsBuffer =
        [g_device newBufferWithLength:argsSize
                              options:MTLResourceStorageModeShared];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetGPUDrivenEnabled(
    JNIEnv *, jclass, jlong handle, jboolean enabled) {
  (void)handle;
  g_gpuDrivenEnabled = (enabled == JNI_TRUE);
  dbg("GPU-driven rendering: %s\n",
      g_gpuDrivenEnabled ? "enabled" : "disabled");
}
static id<MTLTexture> g_hizFallbackTexture = nil;
static int g_cullMode = 0;
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRunGPUCulling(
    JNIEnv *, jclass, jlong handle, jint chunkCount) {
  (void)handle;
  if (!g_device || chunkCount <= 0)
    return 0;
  if (!g_visibleIndicesBuffer || !g_cullDrawCountBuffer) {
    dbg("GPU Cull: buffers not allocated\n");
    return 0;
  }
  uint32_t count = (uint32_t)chunkCount;
  size_t neededSize = (size_t)count * sizeof(uint32_t);
  if (g_visibleIndicesBuffer.length < neededSize) {
    g_visibleIndicesBuffer =
        [g_device newBufferWithLength:neededSize
                              options:MTLResourceStorageModeShared];
  }
  if (g_cullMode == 1 && g_queue && g_cullEncodePipeline &&
      g_resetCullPipeline && g_subChunkBuffer && g_cullStatsBuffer) {
    int bufIdx = g_currentBufferIndex % kTripleBufferCount;
    id<MTLBuffer> cameraBuf = g_tripleBuffers[bufIdx];
    if (!cameraBuf) {
      g_cullMode = 0;
      goto cpu_path;
    }
    CameraUniformsCPU *cam = (CameraUniformsCPU *)[cameraBuf contents];
    cam->totalChunks = count;
    cam->hizMipCount = 0;
    id<MTLTexture> hizTex = g_hizPyramid;
    if (!hizTex) {
      if (!g_hizFallbackTexture) {
        MTLTextureDescriptor *desc = [MTLTextureDescriptor
            texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                         width:1
                                        height:1
                                     mipmapped:NO];
        desc.usage = MTLTextureUsageShaderRead;
        desc.storageMode = MTLStorageModePrivate;
        g_hizFallbackTexture = [g_device newTextureWithDescriptor:desc];
      }
      hizTex = g_hizFallbackTexture;
    }
    @autoreleasepool {
      id<MTLCommandBuffer> cmdBuf = [g_queue commandBuffer];
      if (!cmdBuf)
        goto cpu_path;
      id<MTLComputeCommandEncoder> encoder = [cmdBuf computeCommandEncoder];
      if (!encoder)
        goto cpu_path;
      [encoder setComputePipelineState:g_resetCullPipeline];
      [encoder setBuffer:g_cullDrawCountBuffer offset:0 atIndex:0];
      [encoder setBuffer:g_cullStatsBuffer offset:0 atIndex:1];
      [encoder dispatchThreads:MTLSizeMake(1, 1, 1)
          threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
      [encoder setComputePipelineState:g_cullEncodePipeline];
      [encoder setBuffer:g_subChunkBuffer offset:0 atIndex:0];
      [encoder setBuffer:g_visibleIndicesBuffer offset:0 atIndex:1];
      [encoder setBuffer:g_cullDrawCountBuffer offset:0 atIndex:2];
      [encoder setBuffer:g_cullStatsBuffer offset:0 atIndex:3];
      [encoder setBuffer:cameraBuf offset:0 atIndex:4];
      [encoder setTexture:hizTex atIndex:0];
      NSUInteger threadCount = (NSUInteger)count;
      NSUInteger maxTG =
          (NSUInteger)g_cullEncodePipeline.maxTotalThreadsPerThreadgroup;
      NSUInteger tgSize =
          std::min(threadCount, std::min(maxTG, (NSUInteger)256));
      [encoder dispatchThreads:MTLSizeMake(threadCount, 1, 1)
          threadsPerThreadgroup:MTLSizeMake(tgSize, 1, 1)];
      [encoder endEncoding];
      [cmdBuf commit];
      [cmdBuf waitUntilCompleted];
    }
    uint32_t visibleCount = *(uint32_t *)[g_cullDrawCountBuffer contents];
    if (g_frameCount < 5 || (g_frameCount % 300 == 0)) {
      uint32_t *stats = (uint32_t *)[g_cullStatsBuffer contents];
      dbg("GPU Cull [compute]: input=%u visible=%u frustumCulled=%u "
          "distCulled=%u\n",
          count, visibleCount, stats[1], stats[3]);
    }
    return (jint)visibleCount;
  }
cpu_path:
  {
    uint32_t *indices = (uint32_t *)[g_visibleIndicesBuffer contents];
    for (uint32_t i = 0; i < count; i++) {
      indices[i] = i;
    }
    *(uint32_t *)[g_cullDrawCountBuffer contents] = count;
    if (g_frameCount < 5 || (g_frameCount % 300 == 0)) {
      dbg("GPU Cull [cpu-passthrough]: all %u chunks marked visible\n", count);
    }
    return (jint)count;
  }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGPUVisibleCount(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_cullDrawCountBuffer)
    return 0;
  uint32_t *count = (uint32_t *)[g_cullDrawCountBuffer contents];
  return (jint)(*count);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nExecuteIndirectDraws(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jlong indexBuffer) {
  (void)frameContext;
  (void)vertexBuffer; 
  if (!g_currentEncoder || !g_visibleIndicesBuffer || !g_cullDrawCountBuffer ||
      !g_subChunkBuffer)
    return;
  uint32_t visibleCount = *(uint32_t *)[g_cullDrawCountBuffer contents];
  if (visibleCount == 0)
    return;
  visibleCount = std::min(visibleCount, g_maxGPUDrawCalls);
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  struct SubChunkCPU {
    float aabbMin[4];
    float aabbMax[4];
    uint32_t bufHandleHi;
    uint32_t bufHandleLo;
    uint32_t indexCount;
    uint32_t flags;
  };
  const uint32_t *visibleIndices =
      (const uint32_t *)[g_visibleIndicesBuffer contents];
  const SubChunkCPU *chunks = (const SubChunkCPU *)[g_subChunkBuffer contents];
  const float *chunkUniforms =
      g_chunkUniformsBuffer ? (const float *)[g_chunkUniformsBuffer contents]
                            : nullptr;
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  id<MTLBuffer> lastVB = nil;
  size_t lastVBOffset = 0;
  for (uint32_t i = 0; i < visibleCount; i++) {
    uint32_t chunkIdx = visibleIndices[i];
    if (chunkIdx >= g_gpuSubChunkCount)
      continue;
    const SubChunkCPU &entry = chunks[chunkIdx];
    uint64_t bufHandle =
        ((uint64_t)entry.bufHandleHi << 32) | (uint64_t)entry.bufHandleLo;
    uint32_t idxCount = entry.indexCount;
    if (idxCount == 0)
      continue;
    ResolvedBuf vbRes = resolve_buffer(bufHandle);
    if (!vbRes.buf)
      continue;
    if (chunkUniforms) {
      [g_currentEncoder setVertexBytes:&chunkUniforms[chunkIdx * 4]
                                length:16
                               atIndex:4];
    }
    if (vbRes.buf != lastVB || vbRes.offset != lastVBOffset) {
      [g_currentEncoder setVertexBuffer:vbRes.buf
                                 offset:(NSUInteger)vbRes.offset
                                atIndex:0];
      lastVB = vbRes.buf;
      lastVBOffset = vbRes.offset;
    }
    if (ibRes.buf) {
      [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                   indexCount:(NSUInteger)idxCount
                                    indexType:MTLIndexTypeUInt32
                                  indexBuffer:ibRes.buf
                            indexBufferOffset:(NSUInteger)ibRes.offset];
    } else {
      [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                           vertexStart:0
                           vertexCount:(NSUInteger)idxCount];
    }
    g_drawCallCount++;
  }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetThermalState(
    JNIEnv *, jclass) {
  return (jint)g_thermalState;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetThermalLODReduction(
    JNIEnv *, jclass) {
  return (jint)g_lodRadiusReduction;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetAvailableMemory(
    JNIEnv *, jclass) {
  mach_port_t host = mach_host_self();
  vm_size_t pageSize;
  host_page_size(host, &pageSize);
  vm_statistics64_data_t vmStats;
  mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
  if (host_statistics64(host, HOST_VM_INFO64, (host_info64_t)&vmStats,
                        &count) == KERN_SUCCESS) {
    uint64_t freePages = vmStats.free_count + vmStats.inactive_count;
    return (jlong)(freePages * pageSize);
  }
  return (jlong)(2ULL * 1024 * 1024 * 1024);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetHiZMipCount(
    JNIEnv *, jclass) {
  return (jint)g_hizMipCount;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGPUCullStats(
    JNIEnv *env, jclass, jintArray outStats) {
  if (!outStats || !g_cullStatsBuffer)
    return;
  if (env->GetArrayLength(outStats) < 5)
    return;
  uint32_t *stats = (uint32_t *)[g_cullStatsBuffer contents];
  jint jstats[5] = {(jint)stats[0], (jint)stats[1], (jint)stats[2],
                    (jint)stats[3], (jint)stats[4]};
  env->SetIntArrayRegion(outStats, 0, 5, jstats);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadChunkUniforms(
    JNIEnv *env, jclass, jlong handle, jobject directBuffer, jint count) {
  (void)handle;
  if (!g_device || !directBuffer || count <= 0)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!ptr)
    return;
  size_t entrySize = 16; 
  size_t totalSize = (size_t)count * entrySize;
  if ((size_t)cap < totalSize)
    return;
  if (!g_chunkUniformsBuffer || g_chunkUniformsBuffer.length < totalSize) {
    g_chunkUniformsBuffer =
        [g_device newBufferWithLength:totalSize
                              options:MTLResourceStorageModeShared];
  }
  memcpy([g_chunkUniformsBuffer contents], ptr, totalSize);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreMeshShadersActive(
    JNIEnv *, jclass) {
  return g_meshShadersActive ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsGPUDrivenActive(
    JNIEnv *, jclass) {
  return g_gpuDrivenEnabled ? JNI_TRUE : JNI_FALSE;
}
