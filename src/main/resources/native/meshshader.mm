#include "meshshader.h"
#import <Cocoa/Cocoa.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <mutex>
#include <queue>
#include <stdlib.h>
#include <vector>

#define STRINGIFY(x) #x
static inline bool metal_debug_enabled() {
  const char *e = getenv("METALRENDER_DEBUG");
  return (e && e[0] != '\0');
}
static inline void METAL_LOG_DEBUG(const char *fmt, ...) {
  if (!metal_debug_enabled())
    return;
  va_list ap;
  va_start(ap, fmt);
  fprintf(stderr, "[metalrender DEBUG] ");
  vfprintf(stderr, fmt, ap);
  fprintf(stderr, "\n");
  va_end(ap);
}
static inline void METAL_LOG_ERROR(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fprintf(stderr, "[metalrender ERROR] ");
  vfprintf(stderr, fmt, ap);
  fprintf(stderr, "\n");
  va_end(ap);
}

static std::mutex g_mutex;

static id<MTLDevice> gDevice = nil;
static id<MTLLibrary> gLibrary = nil;
static id<MTLRenderPipelineState> gPipeline = nil;
static id<MTLCommandQueue> gCommandQueue = nil;

static id<CAMetalDrawable> gDrawable = nil;
static MTLRenderPassDescriptor *gRenderPass = nil;
static id<MTLRenderCommandEncoder> gCurrentEncoder =
    nil; // Reuse encoder across multiple draws
static id<MTLCommandBuffer> gCurrentCommandBuffer = nil; // Reuse command buffer
static std::string gShadersPath;

struct NativeChunkMesh {
  id<MTLBuffer> vertexBuffer;
  id<MTLBuffer> indexBuffer;
  uint32_t indexCount;
  uint32_t indexType;
  uint32_t vertexCount;
};

struct PooledBuffer {
  id<MTLBuffer> buffer;
  size_t size;
  bool inUse;
};

static std::vector<PooledBuffer> gVertexBufferPool;
static std::vector<PooledBuffer> gIndexBufferPool;
static const size_t MAX_POOL_SIZE = 64;

static id<MTLBuffer> getPooledVertexBuffer(id<MTLDevice> device, size_t size) {
  for (auto &pooled : gVertexBufferPool) {
    if (!pooled.inUse && pooled.size >= size) {
      pooled.inUse = true;
      METAL_LOG_DEBUG("Reusing vertex buffer from pool, size: %zu", size);
      return pooled.buffer;
    }
  }

  if (gVertexBufferPool.size() < MAX_POOL_SIZE) {
    id<MTLBuffer> newBuffer =
        [device newBufferWithLength:size options:MTLResourceStorageModeShared];
    if (newBuffer) {
      gVertexBufferPool.push_back({newBuffer, size, true});
      METAL_LOG_DEBUG("Created new vertex buffer for pool, size: %zu", size);
      return newBuffer;
    }
  }

  METAL_LOG_DEBUG("Creating non-pooled vertex buffer, size: %zu", size);
  return [device newBufferWithLength:size options:MTLResourceStorageModeShared];
}

static id<MTLBuffer> getPooledIndexBuffer(id<MTLDevice> device, size_t size) {
  for (auto &pooled : gIndexBufferPool) {
    if (!pooled.inUse && pooled.size >= size) {
      pooled.inUse = true;
      METAL_LOG_DEBUG("Reusing index buffer from pool, size: %zu", size);
      return pooled.buffer;
    }
  }

  if (gIndexBufferPool.size() < MAX_POOL_SIZE) {
    id<MTLBuffer> newBuffer =
        [device newBufferWithLength:size options:MTLResourceStorageModeShared];
    if (newBuffer) {
      gIndexBufferPool.push_back({newBuffer, size, true});
      METAL_LOG_DEBUG("Created new index buffer for pool, size: %zu", size);
      return newBuffer;
    }
  }

  METAL_LOG_DEBUG("Creating non-pooled index buffer, size: %zu", size);
  return [device newBufferWithLength:size options:MTLResourceStorageModeShared];
}

static void returnVertexBufferToPool(id<MTLBuffer> buffer) {
  for (auto &pooled : gVertexBufferPool) {
    if (pooled.buffer == buffer) {
      pooled.inUse = false;
      METAL_LOG_DEBUG("Returned vertex buffer to pool");
      return;
    }
  }
}

static void returnIndexBufferToPool(id<MTLBuffer> buffer) {
  for (auto &pooled : gIndexBufferPool) {
    if (pooled.buffer == buffer) {
      pooled.inUse = false;
      METAL_LOG_DEBUG("Returned index buffer to pool");
      return;
    }
  }
}

static void logBufferPoolStats() {
  size_t vertexInUse = 0, vertexAvailable = 0;
  size_t indexInUse = 0, indexAvailable = 0;

  for (const auto &pooled : gVertexBufferPool) {
    if (pooled.inUse)
      vertexInUse++;
    else
      vertexAvailable++;
  }

  for (const auto &pooled : gIndexBufferPool) {
    if (pooled.inUse)
      indexInUse++;
    else
      indexAvailable++;
  }

  METAL_LOG_DEBUG("Buffer Pool Stats - Vertex: %zu in use, %zu available | "
                  "Index: %zu in use, %zu available",
                  vertexInUse, vertexAvailable, indexInUse, indexAvailable);
}

static CAMetalLayer *layerForWindow(NSWindow *win) {
  if (!win)
    return nil;
  NSView *cv = [win contentView];
  if (!cv)
    return nil;
  CALayer *l = [cv layer];
  if (l && [l isKindOfClass:[CAMetalLayer class]])
    return (CAMetalLayer *)l;

  return nil;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_initMeshDevice(
    JNIEnv *env, jclass cls, jlong nsWindowPtr, jboolean srgb) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);

    gDevice = MTLCreateSystemDefaultDevice();
    if (!gDevice) {
      METAL_LOG_ERROR("initMeshDevice: MTLCreateSystemDefaultDevice failed");
      return (jlong)0;
    }
    METAL_LOG_DEBUG("initMeshDevice: device created");
  }
  if (!gCommandQueue)
    gCommandQueue = [gDevice newCommandQueue];
  id<MTLDevice> dev = gDevice;
  CFRetain((__bridge CFTypeRef)dev);
  return (jlong)(intptr_t)(__bridge void *)dev;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_supportsMeshShaders(
    JNIEnv *env, jclass cls, jlong deviceHandle) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0) {
      METAL_LOG_DEBUG("supportsMeshShaders: deviceHandle is 0");
      return JNI_FALSE;
    }
    id<MTLDevice> dev = (__bridge id<MTLDevice>)(void *)deviceHandle;
    if (!dev) {
      METAL_LOG_DEBUG("supportsMeshShaders: device is nil");
      return JNI_FALSE;
    }
    NSError *err;
    std::string path = gShadersPath;
    if (path.empty()) {
      NSString *bundlePath =
          [[NSBundle mainBundle] pathForResource:@"shaders" ofType:@"metallib"];
      if (bundlePath)
        path = [bundlePath UTF8String];
      METAL_LOG_DEBUG("supportsMeshShaders: using bundle path: %s",
                      path.c_str());
    } else {
      METAL_LOG_DEBUG("supportsMeshShaders: using set path: %s", path.c_str());
    }
    if (path.empty()) {
      METAL_LOG_DEBUG("supportsMeshShaders: no metallib path available");
      return JNI_FALSE;
    }
    NSString *pathString = [NSString stringWithUTF8String:path.c_str()];
    id<MTLLibrary> lib =
        [dev newLibraryWithURL:[NSURL fileURLWithPath:pathString] error:&err];
    if (!lib) {
      METAL_LOG_DEBUG(
          "supportsMeshShaders: failed to load library from %s, error: %s",
          path.c_str(), [[err localizedDescription] UTF8String]);
      return JNI_FALSE;
    }
    id<MTLFunction> f = [lib newFunctionWithName:@"vertex_main"];
    if (!f) {
      METAL_LOG_DEBUG(
          "supportsMeshShaders: vertex_main function not found in library");
      return JNI_FALSE;
    }
    METAL_LOG_DEBUG(
        "supportsMeshShaders: all checks passed, mesh shaders supported");
    return JNI_TRUE;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_initMeshPipeline(
    JNIEnv *env, jclass cls, jlong deviceHandle) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0)
      return;
    id<MTLDevice> dev = (__bridge id<MTLDevice>)(void *)deviceHandle;
    if (!dev)
      return;
    NSError *firstErr;
    std::string path = gShadersPath;
    if (path.empty()) {
      NSString *bundlePath =
          [[NSBundle mainBundle] pathForResource:@"shaders" ofType:@"metallib"];
      if (bundlePath)
        path = [bundlePath UTF8String];
    }
    if (path.empty()) {
      METAL_LOG_ERROR("initMeshPipeline: shaders.metallib path not set");
      return;
    }
    NSString *pathString = [NSString stringWithUTF8String:path.c_str()];
    if (!gLibrary)
      gLibrary = [dev newLibraryWithURL:[NSURL fileURLWithPath:pathString]
                                  error:&firstErr];
    if (!gLibrary) {
      METAL_LOG_ERROR("initMeshPipeline: unable to load default library");
      return;
    }
    id<MTLFunction> v = [gLibrary newFunctionWithName:@"vertex_main"];
    id<MTLFunction> f = [gLibrary newFunctionWithName:@"fragment_main"];
    if (!v || !f) {
      METAL_LOG_ERROR("initMeshPipeline: missing shader functions");
      return;
    }
    MTLRenderPipelineDescriptor *desc =
        [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = v;
    desc.fragmentFunction = f;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    NSError *secondErr = nil;
    gPipeline = [dev newRenderPipelineStateWithDescriptor:desc
                                                    error:&secondErr];
    if (!gPipeline)
      gPipeline = nil;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_setShadersPath(
    JNIEnv *env, jclass, jstring jpath) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!jpath) {
      gShadersPath.clear();
      return;
    }
    const char *c = env->GetStringUTFChars(jpath, nullptr);
    if (c) {
      gShadersPath = c;
      env->ReleaseStringUTFChars(jpath, c);
      METAL_LOG_DEBUG("setShadersPath: %s", gShadersPath.c_str());
    }
  }
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_createNativeChunkMesh(
    JNIEnv *env, jclass cls, jlong deviceHandle, jobject vertexBuf,
    jint vertexCount, jint vertexStride, jobject indexBuf, jint indexCount,
    jint indexType) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0)
      return (jlong)0;
    id<MTLDevice> dev = (__bridge id<MTLDevice>)(void *)deviceHandle;
    if (!dev)
      return (jlong)0;
    if (!vertexBuf)
      return (jlong)0;
    void *vptr = env->GetDirectBufferAddress(vertexBuf);
    if (!vptr)
      return (jlong)0;
    size_t vsize = (size_t)vertexCount * (size_t)vertexStride;

    id<MTLBuffer> vbuf = getPooledVertexBuffer(dev, vsize);
    if (!vbuf) {
      METAL_LOG_ERROR("createNativeChunkMesh: vertex buffer allocation failed");
      return (jlong)0;
    }
    memcpy([vbuf contents], vptr, vsize);

    id<MTLBuffer> ibuf = nil;
    if (indexBuf) {
      void *iptr = env->GetDirectBufferAddress(indexBuf);
      if (!iptr) {
        returnVertexBufferToPool(vbuf);
        METAL_LOG_ERROR(
            "createNativeChunkMesh: index buffer direct address null");
        return (jlong)0;
      }
      size_t isize = (size_t)indexCount * (indexType == 1 ? 4 : 2);

      ibuf = getPooledIndexBuffer(dev, isize);
      if (!ibuf) {
        returnVertexBufferToPool(vbuf);
        return (jlong)0;
      }

      memcpy([ibuf contents], iptr, isize);
    }
    NativeChunkMesh *mesh = (NativeChunkMesh *)malloc(sizeof(NativeChunkMesh));
    mesh->vertexBuffer = vbuf;
    mesh->indexBuffer = ibuf;
    mesh->indexCount = (uint32_t)indexCount;
    mesh->indexType = (uint32_t)indexType;
    mesh->vertexCount = (uint32_t)vertexCount;
    METAL_LOG_DEBUG(
        "createNativeChunkMesh: created mesh %p (vcount=%d, icount=%d)", mesh,
        vertexCount, indexCount);
    return (jlong)(intptr_t)mesh;
  }
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_updateNativeChunkMesh(
    JNIEnv *env, jclass cls, jlong existingHandle, jlong deviceHandle,
    jobject vertexBuf, jint vertexCount, jint vertexStride, jobject indexBuf,
    jint indexCount, jint indexType) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0)
      return (jlong)0;
    id<MTLDevice> dev = (__bridge id<MTLDevice>)(void *)deviceHandle;
    if (!dev)
      return (jlong)0;
    if (!vertexBuf)
      return (jlong)0;
    void *vptr = env->GetDirectBufferAddress(vertexBuf);
    if (!vptr)
      return (jlong)0;
    size_t vsize = (size_t)vertexCount * (size_t)vertexStride;

    NativeChunkMesh *existing = nullptr;
    if (existingHandle != 0)
      existing = (NativeChunkMesh *)(intptr_t)existingHandle;
    if (existing) {
      if (existing->vertexBuffer)
        returnVertexBufferToPool(existing->vertexBuffer);
      if (existing->indexBuffer)
        returnIndexBufferToPool(existing->indexBuffer);
    }

    id<MTLBuffer> newVBuf = getPooledVertexBuffer(dev, vsize);
    if (!newVBuf)
      return (jlong)0;

    memcpy([newVBuf contents], vptr, vsize);

    id<MTLBuffer> newIBuf = nil;
    if (indexBuf) {
      void *iptr = env->GetDirectBufferAddress(indexBuf);
      if (!iptr) {
        returnVertexBufferToPool(newVBuf);
        return (jlong)0;
      }
      size_t isize = (size_t)indexCount * (indexType == 1 ? 4 : 2);

      newIBuf = getPooledIndexBuffer(dev, isize);
      if (!newIBuf) {
        returnVertexBufferToPool(newVBuf);
        METAL_LOG_ERROR(
            "updateNativeChunkMesh: index buffer allocation failed");
        return (jlong)0;
      }

      memcpy([newIBuf contents], iptr, isize);
    }

    if (existing) {
      existing->vertexBuffer = newVBuf;
      existing->indexBuffer = newIBuf;
      existing->indexCount = (uint32_t)indexCount;
      existing->indexType = (uint32_t)indexType;
      existing->vertexCount = (uint32_t)vertexCount;
      METAL_LOG_DEBUG("updateNativeChunkMesh: updated existing mesh %p",
                      existing);
      return (jlong)(intptr_t)existing;
    }

    NativeChunkMesh *mesh = (NativeChunkMesh *)malloc(sizeof(NativeChunkMesh));
    mesh->vertexBuffer = newVBuf;
    mesh->indexBuffer = newIBuf;
    mesh->indexCount = (uint32_t)indexCount;
    mesh->indexType = (uint32_t)indexType;
    mesh->vertexCount = (uint32_t)vertexCount;
    METAL_LOG_DEBUG("updateNativeChunkMesh: created new mesh %p", mesh);
    return (jlong)(intptr_t)mesh;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_destroyNativeChunkMesh(
    JNIEnv *env, jclass cls, jlong meshHandle) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (meshHandle == 0)
      return;
    NativeChunkMesh *mesh = (NativeChunkMesh *)(intptr_t)meshHandle;
    if (!mesh)
      return;

    if (mesh->vertexBuffer) {
      returnVertexBufferToPool(mesh->vertexBuffer);
      mesh->vertexBuffer = nil;
    }
    if (mesh->indexBuffer) {
      returnIndexBufferToPool(mesh->indexBuffer);
      mesh->indexBuffer = nil;
    }

    free(mesh);
    METAL_LOG_DEBUG(
        "destroyNativeChunkMesh: mesh destroyed and buffers returned to pool");
  }
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_startMeshFrame(
    JNIEnv *env, jclass cls, jlong deviceHandle) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0)
      return JNI_FALSE;
    if (!gDevice)
      gDevice = (__bridge id<MTLDevice>)(void *)deviceHandle;
    NSWindow *win = [NSApp keyWindow];
    if (!win) {
      NSArray<NSWindow *> *wins = [NSApp windows];
      if ([wins count] > 0)
        win = [wins objectAtIndex:0];
    }
    if (!win)
      return JNI_FALSE;
    CAMetalLayer *ml = layerForWindow(win);
    if (!ml)
      return JNI_FALSE;
    id<CAMetalDrawable> d = [ml nextDrawable];
    if (!d)
      return JNI_FALSE;
    MTLRenderPassDescriptor *rp =
        [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = d.texture;
    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
    gDrawable = d;
    gRenderPass = rp;
    return JNI_TRUE;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_drawNativeChunkMesh(
    JNIEnv *env, jclass cls, jlong deviceHandle, jlong meshHandle, jint layer) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (deviceHandle == 0 || meshHandle == 0)
      return;
    if (!gPipeline || !gRenderPass || !gCommandQueue)
      return;
    NativeChunkMesh *mesh = (NativeChunkMesh *)(intptr_t)meshHandle;
    if (!mesh || !mesh->vertexBuffer)
      return;

    // Create encoder only if we don't have one yet (batch all draws into one
    // encoder)
    if (!gCurrentEncoder) {
      if (!gCurrentCommandBuffer) {
        gCurrentCommandBuffer = [gCommandQueue commandBuffer];
        if (!gCurrentCommandBuffer)
          return;
      }
      gCurrentEncoder = [gCurrentCommandBuffer
          renderCommandEncoderWithDescriptor:gRenderPass];
      if (!gCurrentEncoder) {
        [gCurrentCommandBuffer commit];
        gCurrentCommandBuffer = nil;
        return;
      }
      [gCurrentEncoder setRenderPipelineState:gPipeline];
    }

    // Draw this chunk into the existing encoder (no commit per chunk!)
    [gCurrentEncoder setVertexBuffer:mesh->vertexBuffer offset:0 atIndex:0];
    if (mesh->indexBuffer) {
      MTLIndexType it =
          mesh->indexType == 1 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
      [gCurrentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                  indexCount:mesh->indexCount
                                   indexType:it
                                 indexBuffer:mesh->indexBuffer
                           indexBufferOffset:0];
    } else {
      [gCurrentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                          vertexStart:0
                          vertexCount:mesh->vertexCount];
    }
    // Note: encoder is NOT ended here - it stays open for the next chunk
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_endMeshFrame(
    JNIEnv *env, jclass cls, jlong deviceHandle) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);

    // End the encoder if it's still open (batch commit!)
    if (gCurrentEncoder) {
      [gCurrentEncoder endEncoding];
      gCurrentEncoder = nil;
    }

    // Commit the batched command buffer
    if (gCurrentCommandBuffer) {
      if (gDrawable) {
        [gCurrentCommandBuffer presentDrawable:gDrawable];
      }
      [gCurrentCommandBuffer commit];
      gCurrentCommandBuffer = nil;
    }

    gDrawable = nil;
    gRenderPass = nil;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_MeshShaderNative_destroyMeshDevice(
    JNIEnv *env, jclass cls, jlong deviceHandle) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (gCurrentEncoder)
      gCurrentEncoder = nil;
    if (gCurrentCommandBuffer)
      gCurrentCommandBuffer = nil;
    if (gDrawable)
      gDrawable = nil;
    if (gRenderPass)
      gRenderPass = nil;
    gPipeline = nil;
    gLibrary = nil;
    if (gCommandQueue)
      gCommandQueue = nil;

    METAL_LOG_DEBUG("Cleaning up buffer pools - vertex: %zu, index: %zu",
                    gVertexBufferPool.size(), gIndexBufferPool.size());
    for (auto &pooled : gVertexBufferPool) {
      pooled.buffer = nil;
    }
    gVertexBufferPool.clear();

    for (auto &pooled : gIndexBufferPool) {
      pooled.buffer = nil;
    }
    gIndexBufferPool.clear();

    if (gDevice) {
      CFRelease((__bridge CFTypeRef)gDevice);
      gDevice = nil;
    }
  }
}
}