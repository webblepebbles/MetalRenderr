#include "metalrender.h"
#import <Cocoa/Cocoa.h>
#include <string>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <dlfcn.h>

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

static id<MTLDevice> device = nil;
static id<MTLCommandQueue> commandQueue = nil;
static CAMetalLayer *metalLayer = nil;
static NSWindow *minecraftWindow = nil;
static void *handle;

void *symbolWithErr(void *handle, const char *symbol) {
    if (void *func = dlsym(handle, symbol)) { 
        return func;
    }
    else {
        METAL_LOG_ERROR_MR("unable to open symbol %s: %s", symbol, dlerror());
        return nullptr;
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_NativeBridge_nIsAvailable(JNIEnv *, jclass) {

    id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
    if (dev) {
        dev = nil;
        METAL_LOG_DEBUG_MR("nIsAvailable: device present");
        std::string path = std::string(getenv("HOME")) + "/lib/libmetalrender.dylib"; // just where my local path to this library is
        handle = dlopen(path.c_str(), RTLD_LAZY);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nInit(JNIEnv *, jobject) {
    device = MTLCreateSystemDefaultDevice();
    if (!device) return;
    METAL_LOG_DEBUG_MR("nInit: device created");
    commandQueue = [device newCommandQueue];
    dispatch_async(dispatch_get_main_queue(), ^{
        for (NSWindow *win in [NSApp windows]) {
            if ([[win title] containsString:@"Minecraft"]) {
                minecraftWindow = win;
                break;
            }
        }
            if (minecraftWindow) {
            NSView *contentView = [minecraftWindow contentView];
            metalLayer = [CAMetalLayer layer];
            metalLayer.device = device;
            metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
            metalLayer.framebufferOnly = YES;
            metalLayer.contentsScale = [contentView window].backingScaleFactor;
            metalLayer.frame = contentView.layer.frame;
            [contentView setWantsLayer:YES];
            [contentView setLayer:metalLayer];
                METAL_LOG_DEBUG_MR("nInit: attached CAMetalLayer to Minecraft window");
        }
    });
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nResize(JNIEnv *, jobject, jint width, jint height) {
    if (!metalLayer) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        metalLayer.drawableSize = CGSizeMake(width, height);
    });
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nRender(JNIEnv *, jobject) {
    if (!metalLayer || !commandQueue) return;
    @autoreleasepool {
        id<CAMetalDrawable> drawable = [metalLayer nextDrawable];
        if (!drawable) return;
        MTLRenderPassDescriptor *passDesc = [MTLRenderPassDescriptor renderPassDescriptor];
        passDesc.colorAttachments[0].texture = drawable.texture;
        passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
        passDesc.colorAttachments[0].clearColor = MTLClearColorMake(0.15, 0.15, 0.2, 1.0);
        passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLCommandBuffer> commandBuffer = [commandQueue commandBuffer];
        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:passDesc];
        [encoder endEncoding];
        [commandBuffer presentDrawable:drawable];
        [commandBuffer commit];
    }
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDestroy(JNIEnv *, jobject) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (minecraftWindow && metalLayer) {
            [[minecraftWindow contentView].layer removeFromSuperlayer];
        }
        metalLayer = nil;
        commandQueue = nil;
        device = nil;
    });
}

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MetalBackend_initNative(JNIEnv* env, jclass klass, jlong device, jboolean someOption) {
    return reinterpret_cast<decltype(&Java_com_metalrender_nativebridge_MetalBackend_initNative)>(symbolWithErr(handle, "Java_com_metalrender_nativebridge_MetalBackend_init"))(env, klass, device, someOption);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_resize(JNIEnv* env, jclass klass, jlong device, jint width, jint height) {
    return reinterpret_cast<decltype(&Java_com_metalrender_nativebridge_MetalBackend_resize)>(symbolWithErr(handle, "Java_com_metalrender_nativebridge_MetalBackend_resize"))(env, klass, device, width, height);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_render(JNIEnv* env, jclass klass, jlong device, jfloat thing) {
    return reinterpret_cast<decltype(&Java_com_metalrender_nativebridge_MetalBackend_render)>(symbolWithErr(handle, "Java_com_metalrender_nativebridge_MetalBackend_render"))(env, klass, device, thing);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_destroy(JNIEnv* env, jclass klass, jlong device) {
    return reinterpret_cast<decltype(&Java_com_metalrender_nativebridge_MetalBackend_destroy)>(symbolWithErr(handle, "Java_com_metalrender_nativebridge_MetalBackend_destroy"))(env, klass, device);
}

JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MetalBackend_supportsMeshShaders(JNIEnv* env, jclass klass) {
    return reinterpret_cast<decltype(&Java_com_metalrender_nativebridge_MetalBackend_supportsMeshShaders)>(symbolWithErr(handle, "Java_com_metalrender_nativebridge_MetalBackend_supportsMeshShaders"))(env, klass);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_uploadStaticMesh(JNIEnv* env, jclass klass, jlong device, jobject obj, jint int1, jint int2) {
    return reinterpret_cast<decltype(&Java_com_metalrender_nativebridge_MetalBackend_uploadStaticMesh)>(symbolWithErr(handle, "Java_com_metalrender_nativebridge_MetalBackend_uploadStaticMesh"))(env, klass, device, obj, int1, int2);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_setCamera(JNIEnv* env, jclass klass, jlong device, jfloatArray array) {
    return reinterpret_cast<decltype(&Java_com_metalrender_nativebridge_MetalBackend_setCamera)>(symbolWithErr(handle, "Java_com_metalrender_nativebridge_MetalBackend_setCamera"))(env, klass, device, array);
}

}