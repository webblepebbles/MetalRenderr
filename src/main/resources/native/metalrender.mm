#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
// JNI headers
#include <jni.h>
#include <unordered_map>
#include <vector>
#include <mutex>
#include <atomic>
#include <dispatch/dispatch.h>
#include <thread>


// Forward declarations
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
static id<MTLRenderPipelineState> g_pipelineCutout = nil;
static id<MTLRenderPipelineState> g_pipelineTranslucent = nil;
// ...existing code...


// Offscreen render target (to enable valid passes without MC drawable hookup yet)
static id<MTLTexture> g_color = nil;
static id<MTLTexture> g_depth = nil;
static int g_rtWidth = 16;
static int g_rtHeight = 16;
static float g_scale = 1.0f;

// ...existing code...

static void ensure_device()
{
	if (!g_device) {
		g_device = MTLCreateSystemDefaultDevice();
		if (g_device) {
			g_queue = [g_device newCommandQueue];
		}
	}
}

static void ensure_offscreen()
{
	if (!g_device) return;
	int w = std::max(1, (int)(g_rtWidth * g_scale));
	int h = std::max(1, (int)(g_rtHeight * g_scale));
	bool recreate = (!g_color) || (g_color.width != w) || (g_color.height != h);
	if (!recreate) return;
	MTLTextureDescriptor* cd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
	cd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	g_color = [g_device newTextureWithDescriptor:cd];
	MTLTextureDescriptor* dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:w height:h mipmapped:NO];
	dd.usage = MTLTextureUsageRenderTarget;
	g_depth = [g_device newTextureWithDescriptor:dd];
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_NativeBridge_nIsAvailable
	(JNIEnv*, jclass) {
		ensure_device();
		return (g_available && g_device) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_NativeBridge_nInit
	(JNIEnv*, jclass, jint width, jint height, jfloat scale) {
		// Return a non-zero handle to indicate init success (dummy)
		ensure_device();
	g_rtWidth = (int)width; g_rtHeight = (int)height; g_scale = scale;
	ensure_offscreen();
	return (g_device != nil) ? (jlong)0x1 : (jlong)0;
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nResize
	(JNIEnv*, jclass, jlong handle, jint width, jint height, jfloat scale) {
	(void)handle; g_rtWidth = (int)width; g_rtHeight = (int)height; g_scale = scale; ensure_offscreen();
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nBeginFrame
	(JNIEnv*, jclass, jlong handle, jfloatArray proj, jfloatArray view, jfloat fogStart, jfloat fogEnd) {
	(void)handle; (void)proj; (void)view; (void)fogStart; (void)fogEnd; ensure_device(); ensure_offscreen();
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain
	(JNIEnv*, jclass, jlong handle, jint layerId) {
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDrawOverlay
	(JNIEnv*, jclass, jlong handle, jint layerId) {
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nOnWorldLoaded
	(JNIEnv*, jclass, jlong handle) {
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nOnWorldUnloaded
	(JNIEnv*, jclass, jlong handle) {
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDestroy
	(JNIEnv*, jclass, jlong handle) {
}

extern "C" JNIEXPORT jstring JNICALL Java_com_metalrender_nativebridge_NativeBridge_nGetDeviceName
	(JNIEnv* env, jclass) {
		ensure_device();
		if (!g_device) return env->NewStringUTF("unknown");
		NSString* name = [g_device name];
		return env->NewStringUTF([name UTF8String]);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_NativeBridge_nSupportsIndirect
	(JNIEnv*, jclass) {
		return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_NativeBridge_nSupportsMeshShaders
	(JNIEnv*, jclass) {
		return JNI_TRUE;
}

// =========================

// --- MetalBackend JNI stubs ---
// package: com.metalrender.nativebridge.MetalBackend

static uint64_t store_buffer(id<MTLBuffer> buf) {
	if (!buf) return 0;
	uint64_t h = g_nextHandle++;
	g_buffers[h] = buf;
	return h;
}

static id<MTLBuffer> get_buffer(uint64_t h) {
	auto it = g_buffers.find(h);
	if (it == g_buffers.end()) return nil;
	return it->second;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MetalBackend_initNative(JNIEnv*, jclass, jlong windowHandle, jboolean someFlag) {
	(void)windowHandle; (void)someFlag;
	ensure_device();
	return (g_device != nil) ? (jlong)0xBEEF : (jlong)0;
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_uploadStaticMesh(JNIEnv* env, jclass, jlong handle, jobject vertexData, jint vertexCount, jint stride) {
	(void)handle; (void)vertexCount; (void)stride;
	if (!vertexData) return;
	void* ptr = env->GetDirectBufferAddress(vertexData);
	jlong cap = env->GetDirectBufferCapacity(vertexData);
	(void)ptr; (void)cap;
	// No-op stub: would create MTLBuffer and stage upload here.
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_resize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
	(void)handle; (void)width; (void)height;
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_setCamera(JNIEnv* env, jclass, jlong handle, jfloatArray viewProj4x4) {
	(void)handle;
	if (!viewProj4x4) return;
	jfloat tmp[16];
	if (env->GetArrayLength(viewProj4x4) >= 16) {
		env->GetFloatArrayRegion(viewProj4x4, 0, 16, tmp);
	}
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_render(JNIEnv*, jclass, jlong handle, jfloat timeSeconds) {
	(void)handle;
	(void)timeSeconds;
	ensure_device();
	ensure_offscreen();
	if (!g_device || !g_queue || !g_color || !g_depth) return;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = [g_queue commandBuffer];
		MTLRenderPassDescriptor* rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = g_color;
		rp.colorAttachments[0].loadAction = MTLLoadActionClear;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
		rp.depthAttachment.texture = g_depth;
		rp.depthAttachment.loadAction = MTLLoadActionClear;
		rp.depthAttachment.storeAction = MTLStoreActionDontCare;
		rp.depthAttachment.clearDepth = 1.0;

		id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
		// TODO: Replace with actual draw calls; for now, no draw queue
		[enc endEncoding];
		[cb commit];
	}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MetalBackend_createVertexBuffer(JNIEnv* env, jclass, jlong handle, jobject data, jint size) {
	(void)handle; ensure_device(); if (!g_device || !data || size <= 0) return 0;
	void* ptr = env->GetDirectBufferAddress(data);
	jlong cap = env->GetDirectBufferCapacity(data);
	if (!ptr || cap < size) return 0;
	id<MTLBuffer> buf = [g_device newBufferWithLength:(size_t)size options:MTLResourceStorageModeShared];
	memcpy([buf contents], ptr, (size_t)size);
	uint64_t h = store_buffer(buf);
	return (jlong)h;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MetalBackend_createIndexBuffer(JNIEnv* env, jclass, jlong handle, jobject data, jint size) {
	(void)handle; ensure_device(); if (!g_device || !data || size <= 0) return 0;
	void* ptr = env->GetDirectBufferAddress(data);
	jlong cap = env->GetDirectBufferCapacity(data);
	if (!ptr || cap < size) return 0;
	id<MTLBuffer> buf = [g_device newBufferWithLength:(size_t)size options:MTLResourceStorageModeShared];
	memcpy([buf contents], ptr, (size_t)size);
	uint64_t h = store_buffer(buf);
	return (jlong)h;
}

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_destroyBuffer(JNIEnv*, jclass, jlong handle, jlong bufferHandle) {
	(void)handle; auto it = g_buffers.find((uint64_t)bufferHandle);
	if (it != g_buffers.end()) {
		g_buffers.erase(it);
	}
}
