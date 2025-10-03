#include "metalrender.h"
#import <Cocoa/Cocoa.h>
#include <string>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <vector>
#include <mutex>

static inline bool metal_debug_enabled_mr() {
    const char* e = getenv("METALRENDER_DEBUG");
    return (e && e[0] != '\0');
}
static inline void METAL_LOG_DEBUG_MR(const char* fmt, ...) {
    if (!metal_debug_enabled_mr()) return;
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[metalrender DEBUG] "); vfprintf(stderr, fmt, ap); fprintf(stderr, "\n");
    va_end(ap);
}
static inline void METAL_LOG_ERROR_MR(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[metalrender ERROR] "); vfprintf(stderr, fmt, ap); fprintf(stderr, "\n");
    va_end(ap);
}

static std::string lastInitError;
static std::mutex lastInitErrorMutex;

static void setLastInitError(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    char buf[1024]; vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    std::lock_guard<std::mutex> lock(lastInitErrorMutex);
    lastInitError = buf;
}

static id<MTLDevice> device = nil;
static id<MTLCommandQueue> commandQueue = nil;
static CAMetalLayer *metalLayer = nil;
static NSWindow *minecraftWindow = nil;

struct MRMesh {
    id<MTLBuffer> vertexBuffer;
    uint32_t vertexCount;
    uint32_t stride;
};
struct MRContext {
    id<MTLDevice> dev;
    id<MTLCommandQueue> queue;
    CAMetalLayer* layer;
    id<MTLLibrary> library;
    id<MTLRenderPipelineState> pipeline;
    id<MTLDepthStencilState> depth;
    id<MTLBuffer> uniformBuffer;
    float viewProj[16];
    std::vector<MRMesh> meshes;
    std::mutex mtx;
    NSWindow* window;
};

static id<MTLRenderPipelineState> createPipeline(id<MTLDevice> dev) {
    static const char* kSource = R"metal(
        #include <metal_stdlib>
        using namespace metal;
        struct VSIn {
            short3 position [[attribute(0)]];
            float4 color [[attribute(1)]];
        };
        struct VSOut {
            float4 position [[position]];
            float4 color;
        };
        struct Uniforms { float4x4 mvp; };
        vertex VSOut vs_main(VSIn in [[stage_in]], constant Uniforms& u [[buffer(1)]]) {
            VSOut o;
            float3 pos = float3(in.position);
            o.position = u.mvp * float4(pos, 1.0);
            o.color = in.color;
            return o;
        }
        fragment float4 fs_main(VSOut in [[stage_in]]) {
            return in.color;
        }
    )metal";
        NSError* err = nil;
        id<MTLLibrary> lib = [dev newLibraryWithSource:[NSString stringWithUTF8String:kSource]
                                                                                        options:nil
                                                                                            error:&err];
        if (!lib) {
                const char* msg = err ? [[err localizedDescription] UTF8String] : "unknown";
                METAL_LOG_ERROR_MR("createPipeline: shader compile failed: %s", msg);
                setLastInitError("shader compile failed: %s", msg);
                return nil;
        }
    id<MTLFunction> v = [lib newFunctionWithName:@"vs_main"];
    id<MTLFunction> f = [lib newFunctionWithName:@"fs_main"];
    if (!v || !f) {
        METAL_LOG_ERROR_MR("createPipeline: failed to get functions (v=%p, f=%p)", v, f);
        setLastInitError("failed to get shader functions");
        return nil;
    }
    MTLVertexDescriptor* vd = [[MTLVertexDescriptor alloc] init];
    vd.attributes[0].format = MTLVertexFormatShort3;
    vd.attributes[0].offset = 0;
    vd.attributes[0].bufferIndex = 0;
    vd.attributes[1].format = MTLVertexFormatFloat4;
    vd.attributes[1].offset = 8; 
    vd.attributes[1].bufferIndex = 0;
    static const NSUInteger kVertexStrideBytes = 24;
    vd.layouts[0].stride = kVertexStrideBytes;
    vd.layouts[0].stepFunction = MTLVertexStepFunctionPerVertex;
    MTLRenderPipelineDescriptor* desc = [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = v;
    desc.fragmentFunction = f;
    desc.vertexDescriptor = vd;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    NSError* pErr = nil;
    id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:desc error:&pErr];
    if (!pErr && pso) return pso;
    const char* pmsg = pErr ? [[pErr localizedDescription] UTF8String] : "unknown";
    METAL_LOG_ERROR_MR("createPipeline: pipeline creation failed: %s", pmsg);
    setLastInitError("pipeline creation failed: %s", pmsg);
    return nil;
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_NativeBridge_nIsAvailable(JNIEnv *, jclass) {
    id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
    if (dev) { dev = nil; METAL_LOG_DEBUG_MR("nIsAvailable: device present"); return JNI_TRUE; }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nInit(JNIEnv *, jobject) {

    device = MTLCreateSystemDefaultDevice();
    if (!device) return;
    METAL_LOG_DEBUG_MR("nInit: device created (no layer attached)");
    commandQueue = [device newCommandQueue];
    metalLayer = nil;
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nResize(JNIEnv *, jobject, jint width, jint height) {

}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nRender(JNIEnv *, jobject) {
  
}
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDestroy(JNIEnv *, jobject) {
    metalLayer = nil;
    commandQueue = nil;
    device = nil;
}

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MetalBackend_initNative(JNIEnv* env, jclass, jlong nsWindow, jboolean) {
    if (nsWindow == 0) {
        setLastInitError("nsWindow is 0");
        METAL_LOG_ERROR_MR("initNative: nsWindow is 0");
        return 0;
    }
    @autoreleasepool {
        MRContext* ctx = new MRContext();
        ctx->dev = MTLCreateSystemDefaultDevice();
        if (!ctx->dev) {
            setLastInitError("MTLCreateSystemDefaultDevice failed");
            METAL_LOG_ERROR_MR("initNative: MTLCreateSystemDefaultDevice failed");
            delete ctx;
            return 0;
        }
        METAL_LOG_DEBUG_MR("initNative: device created");
        ctx->queue = [ctx->dev newCommandQueue];
        if (!ctx->queue) {
            setLastInitError("newCommandQueue failed");
            METAL_LOG_ERROR_MR("initNative: newCommandQueue failed");
            delete ctx;
            return 0;
        }
        METAL_LOG_DEBUG_MR("initNative: command queue created");
        ctx->window = (__bridge NSWindow*)(void*)nsWindow;
        
        ctx->layer = nil;
        ctx->pipeline = createPipeline(ctx->dev);
        if (!ctx->pipeline) {
            setLastInitError("createPipeline failed");
            METAL_LOG_ERROR_MR("initNative: createPipeline failed");
            delete ctx;
            return 0;
        }
        METAL_LOG_DEBUG_MR("initNative: pipeline created");
        ctx->uniformBuffer = [ctx->dev newBufferWithLength:sizeof(float)*16 options:MTLResourceStorageModeShared];
        if (!ctx->uniformBuffer) {
            setLastInitError("uniform buffer creation failed");
            METAL_LOG_ERROR_MR("initNative: uniform buffer creation failed");
            delete ctx;
            return 0;
        }
      
        for (int i=0;i<16;i++) ctx->viewProj[i] = (i%5==0)?1.f:0.f;
        METAL_LOG_DEBUG_MR("initNative: initialization complete");
        return (jlong)(intptr_t)ctx;
    }
}

JNIEXPORT jstring JNICALL Java_com_metalrender_nativebridge_MetalBackend_getLastInitError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(lastInitErrorMutex);
    if (lastInitError.empty()) return nullptr;
    return env->NewStringUTF(lastInitError.c_str());
}

JNIEXPORT jstring JNICALL Java_com_metalrender_nativebridge_MetalBackend_getLastInitErrorNative(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(lastInitErrorMutex);
    if (lastInitError.empty()) return nullptr;
    return env->NewStringUTF(lastInitError.c_str());
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_resize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
    if (handle == 0) return;
    MRContext* ctx = (MRContext*)(intptr_t)handle;
    if (!ctx || !ctx->layer) return;
    CGSize sz; sz.width = width; sz.height = height;
    ctx->layer.drawableSize = sz;
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_render(JNIEnv*, jclass, jlong handle, jfloat /*timeSeconds*/) {
    if (handle == 0) return;
    MRContext* ctx = (MRContext*)(intptr_t)handle;
    if (!ctx || !ctx->queue || !ctx->layer) return;
    @autoreleasepool {
        id<CAMetalDrawable> drawable = [ctx->layer nextDrawable];
        if (!drawable) return;
        MTLRenderPassDescriptor* rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = drawable.texture;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(0.1,0.1,0.12,1.0);
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLCommandBuffer> cmd = [ctx->queue commandBuffer];
        id<MTLRenderCommandEncoder> enc = [cmd renderCommandEncoderWithDescriptor:rp];
        if (!enc) { [cmd commit]; return; }
        [enc setRenderPipelineState:ctx->pipeline];
        memcpy([ctx->uniformBuffer contents], ctx->viewProj, sizeof(float)*16);
        [enc setVertexBuffer:ctx->uniformBuffer offset:0 atIndex:1];

        for (const MRMesh& m : ctx->meshes) {
            if (!m.vertexBuffer || m.vertexCount == 0) continue;
            [enc setVertexBuffer:m.vertexBuffer offset:0 atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:m.vertexCount];
        }
        [enc endEncoding];
        [cmd presentDrawable:drawable];
        [cmd commit];
    }
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_destroy(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    MRContext* ctx = (MRContext*)(intptr_t)handle;
    if (!ctx) return;
    @autoreleasepool {
        ctx->pipeline = nil;
        ctx->uniformBuffer = nil;
        for (auto &m : ctx->meshes) { m.vertexBuffer = nil; }
        ctx->meshes.clear();
        ctx->layer = nil;
        ctx->queue = nil;
        ctx->dev = nil;
    }
    delete ctx;
}

JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MetalBackend_supportsMeshShaders(JNIEnv*, jclass) {
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_uploadStaticMesh(JNIEnv* env, jclass, jlong handle, jobject vertexData, jint vertexCount, jint stride) {
    if (handle == 0 || vertexData == nullptr || vertexCount <= 0 || stride <= 0) return;
    MRContext* ctx = (MRContext*)(intptr_t)handle;
    if (!ctx || !ctx->dev) return;
    void* ptr = env->GetDirectBufferAddress(vertexData);
    if (!ptr) return;
    size_t size = (size_t)vertexCount * (size_t)stride;
    id<MTLBuffer> buf = [ctx->dev newBufferWithBytes:ptr length:size options:MTLResourceStorageModeShared];
    if (!buf) return;
    MRMesh mesh{buf, (uint32_t)vertexCount, (uint32_t)stride};
    std::lock_guard<std::mutex> lock(ctx->mtx);
    ctx->meshes.push_back(mesh);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_setCamera(JNIEnv* env, jclass, jlong handle, jfloatArray arr) {
    if (handle == 0 || arr == nullptr) return;
    MRContext* ctx = (MRContext*)(intptr_t)handle;
    if (!ctx) return;
    jsize len = env->GetArrayLength(arr);
    if (len < 16) return;
    jfloat* data = env->GetFloatArrayElements(arr, nullptr);
    if (!data) return;
    memcpy(ctx->viewProj, data, sizeof(float)*16);
    env->ReleaseFloatArrayElements(arr, data, JNI_ABORT);
}

}