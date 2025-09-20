#include <jni.h>
#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>


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

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MetalRendererBackend_nIsAvailable(JNIEnv *, jobject) {

    id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
    if (dev) {
        dev = nil;
        METAL_LOG_DEBUG_MR("nIsAvailable: device present");
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalRendererBackend_nInit(JNIEnv *, jobject) {
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

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalRendererBackend_nResize(JNIEnv *, jobject, jint width, jint height) {
    if (!metalLayer) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        metalLayer.drawableSize = CGSizeMake(width, height);
    });
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalRendererBackend_nRender(JNIEnv *, jobject) {
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
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalRendererBackend_nDestroy(JNIEnv *, jobject) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (minecraftWindow && metalLayer) {
            [[minecraftWindow contentView].layer removeFromSuperlayer];
        }
        metalLayer = nil;
        commandQueue = nil;
        device = nil;
    });
}

}