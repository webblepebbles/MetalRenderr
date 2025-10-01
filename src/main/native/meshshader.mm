#include "meshshader.h"
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Cocoa/Cocoa.h>
#import <Foundation/Foundation.h>
#include <mutex>
#include <stdlib.h>


#define STRINGIFY(x) #x
static inline bool metal_debug_enabled() {
    const char* e = getenv("METALRENDER_DEBUG");
    return (e && e[0] != '\0');
}
static inline void METAL_LOG_DEBUG(const char* fmt, ...) {
    if (!metal_debug_enabled()) return;
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[metalrender DEBUG] "); vfprintf(stderr, fmt, ap); fprintf(stderr, "\n");
    va_end(ap);
}
static inline void METAL_LOG_ERROR(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[metalrender ERROR] "); vfprintf(stderr, fmt, ap); fprintf(stderr, "\n");
    va_end(ap);
}

static std::mutex g_mutex;

static id<MTLDevice> gDevice = nil;
static id<MTLLibrary> gLibrary = nil;
static id<MTLRenderPipelineState> gPipeline = nil;
static id<MTLCommandQueue> gCommandQueue = nil;

static id<CAMetalDrawable> gDrawable = nil;
static MTLRenderPassDescriptor* gRenderPass = nil;

struct NativeChunkMesh {
    id<MTLBuffer> vertexBuffer;
    id<MTLBuffer> indexBuffer;
    uint32_t indexCount;
    uint32_t indexType;
    uint32_t vertexCount;
};

static CAMetalLayer* layerForWindow(NSWindow* win) {
    if (!win) return nil;
    NSView* cv = [win contentView];
    if (!cv) return nil;
    CALayer* l = [cv layer];
    if (l && [l isKindOfClass:[CAMetalLayer class]]) return (CAMetalLayer*)l;
    CAMetalLayer* ml = [CAMetalLayer layer];
    ml.device = gDevice ? gDevice : MTLCreateSystemDefaultDevice();
    ml.pixelFormat = MTLPixelFormatBGRA8Unorm;
    ml.framebufferOnly = NO;
    [cv setWantsLayer:YES];
    [cv setLayer:ml];
    return ml;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_initMeshDevice(JNIEnv* env, jclass cls, jlong nsWindowPtr, jboolean srgb) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
       
            gDevice = MTLCreateSystemDefaultDevice();
            if (!gDevice) {
                METAL_LOG_ERROR("initMeshDevice: MTLCreateSystemDefaultDevice failed");
                return (jlong)0;
            }
            METAL_LOG_DEBUG("initMeshDevice: device created");
    }
    if (!gCommandQueue) gCommandQueue = [gDevice newCommandQueue];
    id<MTLDevice> dev = gDevice;
    CFRetain((__bridge CFTypeRef)dev);
    return (jlong)(intptr_t)(__bridge void*) dev;
}

JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_supportsMeshShaders(JNIEnv* env, jclass cls, jlong deviceHandle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0) return JNI_FALSE;
    id<MTLDevice> dev = (__bridge id<MTLDevice>)(void*)deviceHandle;
    if (!dev) return JNI_FALSE;
    NSError *err;
    std::string path = std::string(getenv("HOME")) + "/lib/shaders.metallib"; // just where my local path to this metallib is
    NSString *pathString = [NSString stringWithUTF8String:path.c_str()];
    id<MTLLibrary> lib = [dev newLibraryWithURL:[NSURL fileURLWithPath:pathString] error:&err];
        if (!lib) return JNI_FALSE;
        id<MTLFunction> f = [lib newFunctionWithName:@"vertex_main"];
        if (!f) return JNI_FALSE;
        return JNI_TRUE;
    }
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_initMeshPipeline(JNIEnv* env, jclass cls, jlong deviceHandle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (deviceHandle == 0) return;
        id<MTLDevice> dev = (__bridge id<MTLDevice>)(void*)deviceHandle;
        if (!dev) return;
        NSError *firstErr;
        std::string path = std::string(getenv("HOME")) + "/lib/shaders.metallib"; // just where my local path to this metallib is
        NSString *pathString = [NSString stringWithUTF8String:path.c_str()];
    if (!gLibrary) gLibrary = [dev newLibraryWithURL:[NSURL fileURLWithPath:pathString] error:&firstErr];
    if (!gLibrary) { METAL_LOG_ERROR("initMeshPipeline: unable to load default library"); return; }
        id<MTLFunction> v = [gLibrary newFunctionWithName:@"vertex_main"];
        id<MTLFunction> f = [gLibrary newFunctionWithName:@"fragment_main"];
    if (!v || !f) { METAL_LOG_ERROR("initMeshPipeline: missing shader functions"); return; }
        MTLRenderPipelineDescriptor* desc = [[MTLRenderPipelineDescriptor alloc] init];
        desc.vertexFunction = v;
        desc.fragmentFunction = f;
        desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
        NSError* secondErr = nil;
        gPipeline = [dev newRenderPipelineStateWithDescriptor:desc error:&secondErr];
        if (!gPipeline) gPipeline = nil;
    }
}

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_createNativeChunkMesh
  (JNIEnv* env, jclass cls, jlong deviceHandle, jobject vertexBuf, jint vertexCount, jint vertexStride,
   jobject indexBuf, jint indexCount, jint indexType)
{
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0) return (jlong)0;
        id<MTLDevice> dev = (__bridge id<MTLDevice>)(void*)deviceHandle;
        if (!dev) return (jlong)0;
        if (!vertexBuf) return (jlong)0;
        void* vptr = env->GetDirectBufferAddress(vertexBuf);
        if (!vptr) return (jlong)0;
        size_t vsize = (size_t)vertexCount * (size_t)vertexStride;
        id<MTLBuffer> vbuf = [dev newBufferWithBytes:vptr length:vsize options:MTLResourceStorageModeShared];
    if (!vbuf) { METAL_LOG_ERROR("createNativeChunkMesh: vertex buffer allocation failed"); return (jlong)0; }
        id<MTLBuffer> ibuf = nil;
        if (indexBuf) {
            void* iptr = env->GetDirectBufferAddress(indexBuf);
            if (!iptr) { METAL_LOG_ERROR("createNativeChunkMesh: index buffer direct address null"); return (jlong)0; }
            size_t isize = (size_t)indexCount * (indexType == 1 ? 4 : 2);
            ibuf = [dev newBufferWithBytes:iptr length:isize options:MTLResourceStorageModeShared];
            if (!ibuf) return (jlong)0;
        }
        NativeChunkMesh* mesh = (NativeChunkMesh*) malloc(sizeof(NativeChunkMesh));
        mesh->vertexBuffer = vbuf;
        mesh->indexBuffer = ibuf;
        mesh->indexCount = (uint32_t)indexCount;
        mesh->indexType = (uint32_t)indexType;
        mesh->vertexCount = (uint32_t)vertexCount;
        METAL_LOG_DEBUG("createNativeChunkMesh: created mesh %p (vcount=%d, icount=%d)", mesh, vertexCount, indexCount);
        return (jlong)(intptr_t)mesh;
    }
}

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_updateNativeChunkMesh
  (JNIEnv* env, jclass cls, jlong existingHandle, jlong deviceHandle, jobject vertexBuf, jint vertexCount, jint vertexStride,
   jobject indexBuf, jint indexCount, jint indexType)
{
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (deviceHandle == 0) return (jlong)0;
        id<MTLDevice> dev = (__bridge id<MTLDevice>)(void*)deviceHandle;
        if (!dev) return (jlong)0;
        if (!vertexBuf) return (jlong)0;
        void* vptr = env->GetDirectBufferAddress(vertexBuf);
        if (!vptr) return (jlong)0;
        size_t vsize = (size_t)vertexCount * (size_t)vertexStride;

        NativeChunkMesh* existing = nullptr;
        if (existingHandle != 0) existing = (NativeChunkMesh*)(intptr_t)existingHandle;

    
        id<MTLBuffer> newVBuf = [dev newBufferWithBytes:vptr length:vsize options:MTLResourceStorageModeShared];
        id<MTLBuffer> newIBuf = nil;
        if (indexBuf) {
            void* iptr = env->GetDirectBufferAddress(indexBuf);
            if (!iptr) {
                if (newVBuf) newVBuf = nil;
                return (jlong)0;
            }
            size_t isize = (size_t)indexCount * (indexType == 1 ? 4 : 2);
            newIBuf = [dev newBufferWithBytes:iptr length:isize options:MTLResourceStorageModeShared];
        if (!newIBuf) { if (newVBuf) newVBuf = nil; METAL_LOG_ERROR("updateNativeChunkMesh: index buffer allocation failed"); return (jlong)0; }
        }

        if (existing) {
            existing->vertexBuffer = newVBuf;
            existing->indexBuffer = newIBuf;
            existing->indexCount = (uint32_t)indexCount;
            existing->indexType = (uint32_t)indexType;
            existing->vertexCount = (uint32_t)vertexCount;
            METAL_LOG_DEBUG("updateNativeChunkMesh: updated existing mesh %p", existing);
            return (jlong)(intptr_t)existing;
        }

   
        NativeChunkMesh* mesh = (NativeChunkMesh*) malloc(sizeof(NativeChunkMesh));
        mesh->vertexBuffer = newVBuf;
        mesh->indexBuffer = newIBuf;
        mesh->indexCount = (uint32_t)indexCount;
        mesh->indexType = (uint32_t)indexType;
        mesh->vertexCount = (uint32_t)vertexCount;
        METAL_LOG_DEBUG("updateNativeChunkMesh: created new mesh %p", mesh);
        return (jlong)(intptr_t)mesh;
    }
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_destroyNativeChunkMesh(JNIEnv* env, jclass cls, jlong meshHandle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (meshHandle == 0) return;
        NativeChunkMesh* mesh = (NativeChunkMesh*)(intptr_t)meshHandle;
        if (!mesh) return;
        mesh->vertexBuffer = nil;
        mesh->indexBuffer = nil;
        free(mesh);
    }
}

JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_startMeshFrame(JNIEnv* env, jclass cls, jlong deviceHandle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (deviceHandle == 0) return JNI_FALSE;
        if (!gDevice) gDevice = (__bridge id<MTLDevice>)(void*)deviceHandle;
        NSWindow* win = [NSApp keyWindow];
        if (!win) {
            NSArray<NSWindow*>* wins = [NSApp windows];
            if ([wins count] > 0) win = [wins objectAtIndex:0];
        }
        if (!win) return JNI_FALSE;
        CAMetalLayer* ml = layerForWindow(win);
        if (!ml) return JNI_FALSE;
        id<CAMetalDrawable> d = [ml nextDrawable];
        if (!d) return JNI_FALSE;
        MTLRenderPassDescriptor* rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = d.texture;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
        gDrawable = d;
        gRenderPass = rp;
        return JNI_TRUE;
    }
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_drawNativeChunkMesh(JNIEnv* env, jclass cls, jlong deviceHandle, jlong meshHandle, jint layer) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (deviceHandle == 0 || meshHandle == 0) return;
        if (!gPipeline || !gRenderPass || !gCommandQueue) return;
        NativeChunkMesh* mesh = (NativeChunkMesh*)(intptr_t)meshHandle;
        if (!mesh || !mesh->vertexBuffer) return;
        id<MTLCommandBuffer> cmd = [gCommandQueue commandBuffer];
        if (!cmd) return;
        id<MTLRenderCommandEncoder> enc = [cmd renderCommandEncoderWithDescriptor:gRenderPass];
        if (!enc) { [cmd commit]; return; }
        [enc setRenderPipelineState:gPipeline];
        [enc setVertexBuffer:mesh->vertexBuffer offset:0 atIndex:0];
        if (mesh->indexBuffer) {
            MTLIndexType it = mesh->indexType == 1 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
            [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:mesh->indexCount indexType:it indexBuffer:mesh->indexBuffer indexBufferOffset:0];
        } else {
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:mesh->vertexCount];
        }
        [enc endEncoding];
        [cmd commit];
    }
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_endMeshFrame(JNIEnv* env, jclass cls, jlong deviceHandle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!gDrawable || !gCommandQueue) {
            gDrawable = nil;
            gRenderPass = nil;
            return;
        }
        id<MTLCommandBuffer> present = [gCommandQueue commandBuffer];
        if (present) {
            [present presentDrawable:gDrawable];
            [present commit];
        }
        gDrawable = nil;
        gRenderPass = nil;
    }
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_destroyMeshDevice(JNIEnv* env, jclass cls, jlong deviceHandle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (gDrawable) gDrawable = nil;
        if (gRenderPass) gRenderPass = nil;
        gPipeline = nil;
        gLibrary = nil;
        if (gCommandQueue) gCommandQueue = nil;
        if (gDevice) {
            CFRelease((__bridge CFTypeRef)gDevice);
            gDevice = nil;
        }
    }
}
} 