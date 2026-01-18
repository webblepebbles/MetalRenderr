#import <AppKit/AppKit.h>
#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <cstdio>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <unordered_map>
#include <vector>

// ============================================================================
// GL2Metal Translation Layer - Native Implementation
//
// This creates a standalone Metal window and provides OpenGL-like calls
// that are translated to Metal operations.
// ============================================================================

// Basic shader source for passthrough rendering
static const char *kBasicShaderSource = R"METAL(
#include <metal_stdlib>
using namespace metal;

struct VertexIn {
    float3 position [[attribute(0)]];
    float4 color [[attribute(1)]];
    float2 texCoord [[attribute(2)]];
};

struct VertexOut {
    float4 position [[position]];
    float4 color;
    float2 texCoord;
};

struct Uniforms {
    float4x4 projectionMatrix;
    float4x4 modelViewMatrix;
};

vertex VertexOut basic_vertex(VertexIn in [[stage_in]],
                              constant Uniforms& uniforms [[buffer(1)]]) {
    VertexOut out;
    float4 pos = float4(in.position, 1.0);
    out.position = uniforms.projectionMatrix * uniforms.modelViewMatrix * pos;
    out.color = in.color;
    out.texCoord = in.texCoord;
    return out;
}

fragment float4 basic_fragment(VertexOut in [[stage_in]],
                               texture2d<float> tex [[texture(0)]],
                               sampler texSampler [[sampler(0)]]) {
    float4 texColor = tex.sample(texSampler, in.texCoord);
    return in.color * texColor;
}

// No-texture variant
fragment float4 color_only_fragment(VertexOut in [[stage_in]]) {
    return in.color;
}
)METAL";

// Vertex attribute info
struct VertexAttribute {
  int index;
  int size; // 1, 2, 3, or 4 components
  int type; // GL type
  bool normalized;
  int stride;
  size_t offset;
  int bufferIndex;
};

// VAO state
struct VAOState {
  std::vector<VertexAttribute> attributes;
  std::vector<bool> enabledAttributes;
  uint32_t elementBuffer = 0;

  VAOState() : enabledAttributes(16, false) {}
};

// GL2Metal context
struct GL2MetalContext {
  id<MTLDevice> device = nil;
  id<MTLCommandQueue> commandQueue = nil;
  id<MTLLibrary> shaderLibrary = nil;

  // Pipelines
  id<MTLRenderPipelineState> texturedPipeline = nil;
  id<MTLRenderPipelineState> colorOnlyPipeline = nil;
  id<MTLDepthStencilState> depthStateEnabled = nil;
  id<MTLDepthStencilState> depthStateDisabled = nil;
  id<MTLSamplerState> defaultSampler = nil;

  // 1x1 white texture for no-texture rendering
  id<MTLTexture> whiteTexture = nil;

  // Uniforms buffer
  id<MTLBuffer> uniformsBuffer = nil;

  // Render targets
  id<MTLTexture> colorTexture = nil;
  id<MTLTexture> depthTexture = nil;
  uint32_t width = 0;
  uint32_t height = 0;

  // Clear state
  float clearR = 0.0f, clearG = 0.0f, clearB = 0.0f, clearA = 1.0f;
  float clearDepth = 1.0f;

  // Current render state
  id<MTLRenderCommandEncoder> currentEncoder = nil;
  id<MTLCommandBuffer> currentCommandBuffer = nil;
  bool renderPassActive = false;

  // Viewport
  int viewportX = 0, viewportY = 0;
  int viewportW = 0, viewportH = 0;

  // Blend state
  bool blendEnabled = false;
  int blendSrcRGB = 1;
  int blendDstRGB = 0;
  int blendSrcAlpha = 1;
  int blendDstAlpha = 0;

  // Depth state
  bool depthTestEnabled = false;
  int depthFunc = 0x0201; // GL_LESS
  bool depthWriteEnabled = true;

  // Cull state
  bool cullFaceEnabled = false;
  int cullFaceMode = 0x0405; // GL_BACK

  // Bound resources
  uint32_t boundArrayBuffer = 0;
  uint32_t boundElementBuffer = 0;
  uint32_t boundTexture2D = 0;
  uint32_t boundVAO = 0;

  // Resource maps
  std::unordered_map<uint32_t, id<MTLTexture>> textures;
  std::unordered_map<uint32_t, id<MTLBuffer>> buffers;
  std::unordered_map<uint32_t, VAOState> vaos;

  // Matrix state
  float projectionMatrix[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  float modelViewMatrix[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  bool matricesDirty = true;

  // Draw call batching
  std::vector<uint8_t> batchedVertices;
  int batchVertexCount = 0;
  int batchPrimitiveType = 0;
  bool hasTexture = false;
};

// Metal window context
struct MetalWindowContext {
  NSWindow *window = nil;
  NSView *contentView = nil;
  CAMetalLayer *metalLayer = nil;
  id<MTLDevice> device = nil;
  id<CAMetalDrawable> currentDrawable = nil;
  GL2MetalContext *gl2metalCtx = nil;

  int width = 0;
  int height = 0;
  bool shouldClose = false;
};

// Window delegate
@interface MetalWindowDelegate : NSObject <NSWindowDelegate>
@property(nonatomic) MetalWindowContext *context;
@end

// Custom window that refuses to become key (so clicks pass through to GLFW)
@interface MetalOverlayWindow : NSWindow
@end

@implementation MetalOverlayWindow
- (BOOL)canBecomeKeyWindow {
  return NO; // Refuse to become key window
}
- (BOOL)canBecomeMainWindow {
  return NO; // Refuse to become main window
}
@end

@implementation MetalWindowDelegate
- (BOOL)windowShouldClose:(NSWindow *)sender {
  (void)sender;
  if (self.context) {
    self.context->shouldClose = YES;
  }
  return NO;
}

- (void)windowDidResize:(NSNotification *)notification {
  NSWindow *win = notification.object;
  if (self.context && win == self.context->window) {
    NSRect frame = [win.contentView bounds];
    CGFloat scale = win.backingScaleFactor;
    self.context->width = (int)(frame.size.width * scale);
    self.context->height = (int)(frame.size.height * scale);

    if (self.context->metalLayer) {
      self.context->metalLayer.drawableSize =
          CGSizeMake(self.context->width, self.context->height);
    }

    printf("[GL2Metal] Window resized to %dx%d\n", self.context->width,
           self.context->height);
  }
}
@end

// Global state
static std::mutex gGL2MetalMutex;
static GL2MetalContext *gGlobalGL2MetalCtx = nullptr;
static MetalWindowContext *gGlobalWindowCtx = nullptr;

// ============================================================================
// Helper Functions
// ============================================================================

static MTLBlendFactor translateBlendFactor(int glFactor) {
  switch (glFactor) {
  case 0:
    return MTLBlendFactorZero;
  case 1:
    return MTLBlendFactorOne;
  case 0x0300:
    return MTLBlendFactorSourceColor;
  case 0x0301:
    return MTLBlendFactorOneMinusSourceColor;
  case 0x0302:
    return MTLBlendFactorSourceAlpha;
  case 0x0303:
    return MTLBlendFactorOneMinusSourceAlpha;
  case 0x0304:
    return MTLBlendFactorDestinationAlpha;
  case 0x0305:
    return MTLBlendFactorOneMinusDestinationAlpha;
  case 0x0306:
    return MTLBlendFactorDestinationColor;
  case 0x0307:
    return MTLBlendFactorOneMinusDestinationColor;
  default:
    return MTLBlendFactorOne;
  }
}

static MTLCompareFunction translateDepthFunc(int glFunc) {
  switch (glFunc) {
  case 0x0200:
    return MTLCompareFunctionNever;
  case 0x0201:
    return MTLCompareFunctionLess;
  case 0x0202:
    return MTLCompareFunctionEqual;
  case 0x0203:
    return MTLCompareFunctionLessEqual;
  case 0x0204:
    return MTLCompareFunctionGreater;
  case 0x0205:
    return MTLCompareFunctionNotEqual;
  case 0x0206:
    return MTLCompareFunctionGreaterEqual;
  case 0x0207:
    return MTLCompareFunctionAlways;
  default:
    return MTLCompareFunctionLess;
  }
}

static MTLPrimitiveType translatePrimitive(int glPrimitive) {
  switch (glPrimitive) {
  case 0x0000:
    return MTLPrimitiveTypePoint; // GL_POINTS
  case 0x0001:
    return MTLPrimitiveTypeLine; // GL_LINES
  case 0x0003:
    return MTLPrimitiveTypeLineStrip; // GL_LINE_STRIP
  case 0x0004:
    return MTLPrimitiveTypeTriangle; // GL_TRIANGLES
  case 0x0005:
    return MTLPrimitiveTypeTriangleStrip; // GL_TRIANGLE_STRIP
  default:
    return MTLPrimitiveTypeTriangle;
  }
}

static bool initShaderLibrary(GL2MetalContext *ctx) {
  if (ctx->shaderLibrary)
    return true;

  NSError *error = nil;
  ctx->shaderLibrary = [ctx->device
      newLibraryWithSource:[NSString stringWithUTF8String:kBasicShaderSource]
                   options:nil
                     error:&error];
  if (!ctx->shaderLibrary) {
    printf("[GL2Metal] Failed to compile shaders: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  printf("[GL2Metal] Shader library compiled successfully\n");
  return true;
}

static bool initPipelines(GL2MetalContext *ctx) {
  if (ctx->texturedPipeline && ctx->colorOnlyPipeline)
    return true;
  if (!initShaderLibrary(ctx))
    return false;

  NSError *error = nil;

  // Vertex descriptor
  MTLVertexDescriptor *vertexDesc = [[MTLVertexDescriptor alloc] init];

  // Position (float3)
  vertexDesc.attributes[0].format = MTLVertexFormatFloat3;
  vertexDesc.attributes[0].offset = 0;
  vertexDesc.attributes[0].bufferIndex = 0;

  // Color (float4)
  vertexDesc.attributes[1].format = MTLVertexFormatFloat4;
  vertexDesc.attributes[1].offset = 12;
  vertexDesc.attributes[1].bufferIndex = 0;

  // TexCoord (float2)
  vertexDesc.attributes[2].format = MTLVertexFormatFloat2;
  vertexDesc.attributes[2].offset = 28;
  vertexDesc.attributes[2].bufferIndex = 0;

  // Layout: stride 36 bytes (3*4 + 4*4 + 2*4)
  vertexDesc.layouts[0].stride = 36;
  vertexDesc.layouts[0].stepFunction = MTLVertexStepFunctionPerVertex;

  id<MTLFunction> vertexFunc =
      [ctx->shaderLibrary newFunctionWithName:@"basic_vertex"];
  id<MTLFunction> texturedFragFunc =
      [ctx->shaderLibrary newFunctionWithName:@"basic_fragment"];
  id<MTLFunction> colorOnlyFragFunc =
      [ctx->shaderLibrary newFunctionWithName:@"color_only_fragment"];

  if (!vertexFunc || !texturedFragFunc || !colorOnlyFragFunc) {
    printf("[GL2Metal] Failed to get shader functions\n");
    return false;
  }

  // Textured pipeline
  MTLRenderPipelineDescriptor *pipelineDesc =
      [[MTLRenderPipelineDescriptor alloc] init];
  pipelineDesc.vertexFunction = vertexFunc;
  pipelineDesc.fragmentFunction = texturedFragFunc;
  pipelineDesc.vertexDescriptor = vertexDesc;
  pipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  pipelineDesc.colorAttachments[0].blendingEnabled = YES;
  pipelineDesc.colorAttachments[0].sourceRGBBlendFactor =
      MTLBlendFactorSourceAlpha;
  pipelineDesc.colorAttachments[0].destinationRGBBlendFactor =
      MTLBlendFactorOneMinusSourceAlpha;
  pipelineDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
  pipelineDesc.colorAttachments[0].destinationAlphaBlendFactor =
      MTLBlendFactorOneMinusSourceAlpha;
  pipelineDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;

  ctx->texturedPipeline =
      [ctx->device newRenderPipelineStateWithDescriptor:pipelineDesc
                                                  error:&error];
  if (!ctx->texturedPipeline) {
    printf("[GL2Metal] Failed to create textured pipeline: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  // Color-only pipeline
  pipelineDesc.fragmentFunction = colorOnlyFragFunc;
  ctx->colorOnlyPipeline =
      [ctx->device newRenderPipelineStateWithDescriptor:pipelineDesc
                                                  error:&error];
  if (!ctx->colorOnlyPipeline) {
    printf("[GL2Metal] Failed to create color-only pipeline: %s\n",
           error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  // Depth states
  MTLDepthStencilDescriptor *depthDesc =
      [[MTLDepthStencilDescriptor alloc] init];
  depthDesc.depthCompareFunction = MTLCompareFunctionLess;
  depthDesc.depthWriteEnabled = YES;
  ctx->depthStateEnabled =
      [ctx->device newDepthStencilStateWithDescriptor:depthDesc];

  depthDesc.depthCompareFunction = MTLCompareFunctionAlways;
  depthDesc.depthWriteEnabled = NO;
  ctx->depthStateDisabled =
      [ctx->device newDepthStencilStateWithDescriptor:depthDesc];

  // Sampler
  MTLSamplerDescriptor *samplerDesc = [[MTLSamplerDescriptor alloc] init];
  samplerDesc.minFilter = MTLSamplerMinMagFilterNearest;
  samplerDesc.magFilter = MTLSamplerMinMagFilterNearest;
  samplerDesc.mipFilter = MTLSamplerMipFilterNearest;
  samplerDesc.sAddressMode = MTLSamplerAddressModeRepeat;
  samplerDesc.tAddressMode = MTLSamplerAddressModeRepeat;
  ctx->defaultSampler = [ctx->device newSamplerStateWithDescriptor:samplerDesc];

  // 1x1 white texture
  MTLTextureDescriptor *texDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                   width:1
                                  height:1
                               mipmapped:NO];
  ctx->whiteTexture = [ctx->device newTextureWithDescriptor:texDesc];
  uint8_t white[] = {255, 255, 255, 255};
  [ctx->whiteTexture replaceRegion:MTLRegionMake2D(0, 0, 1, 1)
                       mipmapLevel:0
                         withBytes:white
                       bytesPerRow:4];

  // Uniforms buffer
  ctx->uniformsBuffer =
      [ctx->device newBufferWithLength:256
                               options:MTLResourceStorageModeShared];

  printf("[GL2Metal] Pipelines initialized successfully\n");
  return true;
}

static void ensureRenderTarget(GL2MetalContext *ctx, int width, int height) {
  if (ctx->colorTexture && ctx->width == (uint32_t)width &&
      ctx->height == (uint32_t)height) {
    return;
  }

  ctx->width = width;
  ctx->height = height;

  MTLTextureDescriptor *colorDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                   width:width
                                  height:height
                               mipmapped:NO];
  colorDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  colorDesc.storageMode = MTLStorageModePrivate;
  ctx->colorTexture = [ctx->device newTextureWithDescriptor:colorDesc];

  MTLTextureDescriptor *depthDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                   width:width
                                  height:height
                               mipmapped:NO];
  depthDesc.usage = MTLTextureUsageRenderTarget;
  depthDesc.storageMode = MTLStorageModePrivate;
  ctx->depthTexture = [ctx->device newTextureWithDescriptor:depthDesc];

  printf("[GL2Metal] Created render targets %dx%d\n", width, height);
}

static void beginRenderPass(GL2MetalContext *ctx, id<MTLTexture> colorTarget,
                            id<MTLTexture> depthTarget, bool clearColor,
                            bool clearDepth) {
  if (ctx->renderPassActive)
    return;

  ctx->currentCommandBuffer = [ctx->commandQueue commandBuffer];

  MTLRenderPassDescriptor *pass =
      [MTLRenderPassDescriptor renderPassDescriptor];
  pass.colorAttachments[0].texture = colorTarget;
  pass.colorAttachments[0].loadAction =
      clearColor ? MTLLoadActionClear : MTLLoadActionLoad;
  pass.colorAttachments[0].storeAction = MTLStoreActionStore;
  pass.colorAttachments[0].clearColor =
      MTLClearColorMake(ctx->clearR, ctx->clearG, ctx->clearB, ctx->clearA);

  if (depthTarget) {
    pass.depthAttachment.texture = depthTarget;
    pass.depthAttachment.loadAction =
        clearDepth ? MTLLoadActionClear : MTLLoadActionLoad;
    pass.depthAttachment.storeAction = MTLStoreActionStore;
    pass.depthAttachment.clearDepth = ctx->clearDepth;
  }

  ctx->currentEncoder =
      [ctx->currentCommandBuffer renderCommandEncoderWithDescriptor:pass];
  ctx->renderPassActive = true;
}

static void endRenderPass(GL2MetalContext *ctx) {
  if (!ctx->renderPassActive)
    return;

  [ctx->currentEncoder endEncoding];
  ctx->currentEncoder = nil;
  ctx->renderPassActive = false;
}

extern "C" {

// ============================================================================
// Window Management
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nCreateMetalWindow(
    JNIEnv *env, jclass, jint width, jint height, jstring titleStr) {

  std::lock_guard<std::mutex> lock(gGL2MetalMutex);

  printf("[GL2Metal] Creating Metal window %dx%d\n", width, height);

  @autoreleasepool {
    const char *titleChars = env->GetStringUTFChars(titleStr, nullptr);
    NSString *title = [NSString stringWithUTF8String:titleChars];
    env->ReleaseStringUTFChars(titleStr, titleChars);

    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (!device) {
      printf("[GL2Metal] ERROR: Failed to create Metal device\n");
      return 0;
    }

    MetalWindowContext *ctx = new MetalWindowContext();
    ctx->device = device;
    ctx->width = width;
    ctx->height = height;

    __block NSWindow *window = nil;
    __block CAMetalLayer *metalLayer = nil;
    __block MetalWindowDelegate *delegate = nil;

    void (^createWindow)(void) = ^{
      NSRect contentRect = NSMakeRect(100, 100, width / 2, height / 2);
      NSWindowStyleMask styleMask =
          NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
          NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable;

      // Use custom window class that refuses to become key
      window =
          [[MetalOverlayWindow alloc] initWithContentRect:contentRect
                                                styleMask:styleMask
                                                  backing:NSBackingStoreBuffered
                                                    defer:NO];
      [window setTitle:title];
      [window setReleasedWhenClosed:NO];

      // Make window ignore mouse events so they pass through to GLFW window
      [window setIgnoresMouseEvents:YES];

      NSView *contentView = [[NSView alloc] initWithFrame:contentRect];
      [contentView setWantsLayer:YES];

      metalLayer = [CAMetalLayer layer];
      metalLayer.device = device;
      metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
      metalLayer.framebufferOnly = NO;
      metalLayer.drawableSize = CGSizeMake(width, height);
      metalLayer.opaque = YES;
      metalLayer.displaySyncEnabled = YES;
      metalLayer.maximumDrawableCount = 3;

      [contentView setLayer:metalLayer];
      [window setContentView:contentView];

      delegate = [[MetalWindowDelegate alloc] init];
      delegate.context = ctx;
      [window setDelegate:delegate];

      // Keep Metal window on top
      [window setLevel:NSFloatingWindowLevel];

      // Don't make it key - just bring it to front
      [window orderFront:nil];
      [window center];

      printf(
          "[GL2Metal] Window created (overlay mode - ignores mouse events)\n");
    };

    if ([NSThread isMainThread]) {
      createWindow();
    } else {
      dispatch_sync(dispatch_get_main_queue(), createWindow);
    }

    if (!window || !metalLayer) {
      printf("[GL2Metal] ERROR: Failed to create window/layer\n");
      delete ctx;
      return 0;
    }

    ctx->window = window;
    ctx->metalLayer = metalLayer;

    // Create GL2Metal context
    GL2MetalContext *gl2ctx = new GL2MetalContext();
    gl2ctx->device = device;
    gl2ctx->commandQueue = [device newCommandQueue];
    gl2ctx->width = width;
    gl2ctx->height = height;
    gl2ctx->viewportW = width;
    gl2ctx->viewportH = height;

    // Initialize pipelines
    if (!initPipelines(gl2ctx)) {
      printf("[GL2Metal] ERROR: Failed to initialize pipelines\n");
      delete gl2ctx;
      delete ctx;
      return 0;
    }

    ctx->gl2metalCtx = gl2ctx;

    gGlobalWindowCtx = ctx;
    gGlobalGL2MetalCtx = gl2ctx;

    printf("[GL2Metal] Window context created: %p, gl2ctx: %p\n", ctx, gl2ctx);

    return reinterpret_cast<jlong>(ctx);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nDestroyMetalWindow(
    JNIEnv *, jclass, jlong windowHandle) {

  std::lock_guard<std::mutex> lock(gGL2MetalMutex);

  MetalWindowContext *ctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!ctx)
    return;

  printf("[GL2Metal] Destroying window\n");

  if (ctx == gGlobalWindowCtx) {
    gGlobalWindowCtx = nullptr;
    gGlobalGL2MetalCtx = nullptr;
  }

  if (ctx->gl2metalCtx) {
    delete ctx->gl2metalCtx;
  }

  if (ctx->window) {
    dispatch_async(dispatch_get_main_queue(), ^{
      [ctx->window close];
    });
  }

  delete ctx;
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nPresentFrame(
    JNIEnv *, jclass, jlong windowHandle) {

  MetalWindowContext *wctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!wctx || !wctx->metalLayer)
    return;

  GL2MetalContext *ctx = wctx->gl2metalCtx;
  if (!ctx)
    return;

  @autoreleasepool {
    // End any active render pass
    endRenderPass(ctx);

    // Commit any pending command buffer
    if (ctx->currentCommandBuffer) {
      [ctx->currentCommandBuffer commit];
      [ctx->currentCommandBuffer waitUntilCompleted];
      ctx->currentCommandBuffer = nil;
    }

    // Get drawable and blit to it
    id<CAMetalDrawable> drawable = [wctx->metalLayer nextDrawable];
    if (!drawable) {
      printf("[GL2Metal] WARNING: No drawable available\n");
      return;
    }

    id<MTLCommandBuffer> cb = [ctx->commandQueue commandBuffer];

    // If we have rendered content, blit it to the drawable
    if (ctx->colorTexture &&
        ctx->colorTexture.width == drawable.texture.width &&
        ctx->colorTexture.height == drawable.texture.height) {
      id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
      [blit copyFromTexture:ctx->colorTexture toTexture:drawable.texture];
      [blit endEncoding];
    } else {
      // Just clear the drawable
      MTLRenderPassDescriptor *pass =
          [MTLRenderPassDescriptor renderPassDescriptor];
      pass.colorAttachments[0].texture = drawable.texture;
      pass.colorAttachments[0].loadAction = MTLLoadActionClear;
      pass.colorAttachments[0].storeAction = MTLStoreActionStore;
      pass.colorAttachments[0].clearColor =
          MTLClearColorMake(ctx->clearR, ctx->clearG, ctx->clearB, ctx->clearA);

      id<MTLRenderCommandEncoder> enc =
          [cb renderCommandEncoderWithDescriptor:pass];
      [enc endEncoding];
    }

    [cb presentDrawable:drawable];
    [cb commit];
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSetWindowSize(
    JNIEnv *, jclass, jlong windowHandle, jint width, jint height) {

  MetalWindowContext *ctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!ctx)
    return;

  ctx->width = width;
  ctx->height = height;

  if (ctx->metalLayer) {
    ctx->metalLayer.drawableSize = CGSizeMake(width, height);
  }

  if (ctx->gl2metalCtx) {
    ctx->gl2metalCtx->width = width;
    ctx->gl2metalCtx->height = height;
  }
}

// ============================================================================
// GL State Functions
// ============================================================================

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nClearFramebuffer(
    JNIEnv *, jclass, jlong, jboolean clearColor, jboolean clearDepth,
    jboolean) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  ensureRenderTarget(ctx, ctx->viewportW > 0 ? ctx->viewportW : ctx->width,
                     ctx->viewportH > 0 ? ctx->viewportH : ctx->height);

  // End current pass if any
  endRenderPass(ctx);

  // Start new pass with clear
  beginRenderPass(ctx, ctx->colorTexture, ctx->depthTexture, clearColor,
                  clearDepth);
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSetClearColor(
    JNIEnv *, jclass, jlong, jfloat r, jfloat g, jfloat b, jfloat a) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  ctx->clearR = r;
  ctx->clearG = g;
  ctx->clearB = b;
  ctx->clearA = a;
}

// ============================================================================
// Texture Functions
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nCreateTexture(
    JNIEnv *env, jclass, jlong, jint width, jint height, jint,
    jobject dataBuffer) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx || !ctx->device)
    return 0;

  MTLTextureDescriptor *desc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                   width:width
                                  height:height
                               mipmapped:NO];
  desc.usage = MTLTextureUsageShaderRead;
  desc.storageMode = MTLStorageModeShared;

  id<MTLTexture> texture = [ctx->device newTextureWithDescriptor:desc];
  if (!texture) {
    printf("[GL2Metal] Failed to create texture %dx%d\n", width, height);
    return 0;
  }

  // Upload data if provided
  if (dataBuffer) {
    void *data = env->GetDirectBufferAddress(dataBuffer);
    if (data) {
      [texture replaceRegion:MTLRegionMake2D(0, 0, width, height)
                 mipmapLevel:0
                   withBytes:data
                 bytesPerRow:width * 4];
    }
  }

  return reinterpret_cast<jlong>((__bridge_retained void *)texture);
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nUpdateTexture(
    JNIEnv *env, jclass, jlong, jlong texHandle, jint x, jint y, jint w, jint h,
    jobject dataBuffer) {

  id<MTLTexture> texture = (__bridge id<MTLTexture>)(void *)texHandle;
  if (!texture || !dataBuffer)
    return;

  void *data = env->GetDirectBufferAddress(dataBuffer);
  if (!data)
    return;

  [texture replaceRegion:MTLRegionMake2D(x, y, w, h)
             mipmapLevel:0
               withBytes:data
             bytesPerRow:w * 4];
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nDeleteTexture(
    JNIEnv *, jclass, jlong, jlong texHandle) {

  if (texHandle != 0) {
    id<MTLTexture> texture =
        (__bridge_transfer id<MTLTexture>)(void *)texHandle;
    texture = nil;
  }
}

// ============================================================================
// Buffer Functions
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nCreateBuffer(
    JNIEnv *env, jclass, jlong, jobject dataBuffer, jint size) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx || !ctx->device || !dataBuffer)
    return 0;

  void *data = env->GetDirectBufferAddress(dataBuffer);
  if (!data)
    return 0;

  id<MTLBuffer> buffer =
      [ctx->device newBufferWithBytes:data
                               length:size
                              options:MTLResourceStorageModeShared];
  if (!buffer) {
    printf("[GL2Metal] Failed to create buffer of size %d\n", size);
    return 0;
  }

  return reinterpret_cast<jlong>((__bridge_retained void *)buffer);
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nCreateBufferEmpty(JNIEnv *env,
                                                                    jclass,
                                                                    jlong,
                                                                    jint size) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx || !ctx->device || size <= 0)
    return 0;

  id<MTLBuffer> buffer =
      [ctx->device newBufferWithLength:size
                               options:MTLResourceStorageModeShared];
  if (!buffer) {
    printf("[GL2Metal] Failed to create empty buffer of size %d\n", size);
    return 0;
  }

  return reinterpret_cast<jlong>((__bridge_retained void *)buffer);
}

JNIEXPORT jlong JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nCreateBufferFromPointer(
    JNIEnv *env, jclass, jlong, jlong dataPtr, jint size) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx || !ctx->device || dataPtr == 0 || size <= 0)
    return 0;

  void *data = (void *)dataPtr;

  id<MTLBuffer> buffer =
      [ctx->device newBufferWithBytes:data
                               length:size
                              options:MTLResourceStorageModeShared];
  if (!buffer) {
    printf("[GL2Metal] Failed to create buffer from pointer of size %d\n",
           size);
    return 0;
  }

  return reinterpret_cast<jlong>((__bridge_retained void *)buffer);
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nUpdateBuffer(
    JNIEnv *env, jclass, jlong, jlong bufHandle, jlong offset,
    jobject dataBuffer, jint size) {

  id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)(void *)bufHandle;
  if (!buffer || !dataBuffer)
    return;

  void *data = env->GetDirectBufferAddress(dataBuffer);
  if (!data)
    return;

  memcpy((uint8_t *)[buffer contents] + offset, data, size);
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nUpdateBufferFromPointer(
    JNIEnv *env, jclass, jlong, jlong bufHandle, jlong offset, jlong dataPtr,
    jint size) {

  id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)(void *)bufHandle;
  if (!buffer || dataPtr == 0)
    return;

  void *data = (void *)dataPtr;
  memcpy((uint8_t *)[buffer contents] + offset, data, size);
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nDeleteBuffer(
    JNIEnv *, jclass, jlong, jlong bufHandle) {

  if (bufHandle != 0) {
    id<MTLBuffer> buffer = (__bridge_transfer id<MTLBuffer>)(void *)bufHandle;
    buffer = nil;
  }
}

// ============================================================================
// Render State Functions
// ============================================================================

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSetDepthTest(JNIEnv *, jclass,
                                                               jlong,
                                                               jboolean enabled,
                                                               jint func) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  ctx->depthTestEnabled = enabled;
  ctx->depthFunc = func;
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSetBlend(
    JNIEnv *, jclass, jlong, jboolean enabled, jint srcRGB, jint dstRGB,
    jint srcA, jint dstA) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  ctx->blendEnabled = enabled;
  ctx->blendSrcRGB = srcRGB;
  ctx->blendDstRGB = dstRGB;
  ctx->blendSrcAlpha = srcA;
  ctx->blendDstAlpha = dstA;
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSetCullFace(JNIEnv *, jclass,
                                                              jlong,
                                                              jboolean enabled,
                                                              jint mode) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  ctx->cullFaceEnabled = enabled;
  ctx->cullFaceMode = mode;
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSetViewport(
    JNIEnv *, jclass, jlong, jint x, jint y, jint width, jint height) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  ctx->viewportX = x;
  ctx->viewportY = y;
  ctx->viewportW = width;
  ctx->viewportH = height;

  // Apply viewport to encoder if active
  if (ctx->currentEncoder) {
    MTLViewport viewport;
    viewport.originX = x;
    viewport.originY = y;
    viewport.width = width;
    viewport.height = height;
    viewport.znear = 0.0;
    viewport.zfar = 1.0;
    [ctx->currentEncoder setViewport:viewport];
  }
}

// ============================================================================
// Draw Functions
// ============================================================================

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nDrawArrays(
    JNIEnv *, jclass, jlong, jint primitive, jint first, jint count) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  // Ensure render pass is active
  if (!ctx->renderPassActive) {
    ensureRenderTarget(ctx, ctx->viewportW > 0 ? ctx->viewportW : ctx->width,
                       ctx->viewportH > 0 ? ctx->viewportH : ctx->height);
    beginRenderPass(ctx, ctx->colorTexture, ctx->depthTexture, false, false);
  }

  // Set pipeline and state
  bool hasTexture = ctx->boundTexture2D != 0;
  [ctx->currentEncoder setRenderPipelineState:hasTexture
                                                  ? ctx->texturedPipeline
                                                  : ctx->colorOnlyPipeline];
  [ctx->currentEncoder setDepthStencilState:ctx->depthTestEnabled
                                                ? ctx->depthStateEnabled
                                                : ctx->depthStateDisabled];

  // Set viewport
  MTLViewport viewport;
  viewport.originX = ctx->viewportX;
  viewport.originY = ctx->viewportY;
  viewport.width = ctx->viewportW;
  viewport.height = ctx->viewportH;
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [ctx->currentEncoder setViewport:viewport];

  // Update uniforms
  struct {
    float projectionMatrix[16];
    float modelViewMatrix[16];
  } uniforms;
  memcpy(uniforms.projectionMatrix, ctx->projectionMatrix, 64);
  memcpy(uniforms.modelViewMatrix, ctx->modelViewMatrix, 64);
  memcpy([ctx->uniformsBuffer contents], &uniforms, sizeof(uniforms));
  [ctx->currentEncoder setVertexBuffer:ctx->uniformsBuffer offset:0 atIndex:1];

  // Set texture
  if (hasTexture) {
    auto it = ctx->textures.find(ctx->boundTexture2D);
    if (it != ctx->textures.end()) {
      [ctx->currentEncoder setFragmentTexture:it->second atIndex:0];
    } else {
      [ctx->currentEncoder setFragmentTexture:ctx->whiteTexture atIndex:0];
    }
    [ctx->currentEncoder setFragmentSamplerState:ctx->defaultSampler atIndex:0];
  }

  // Set vertex buffer from bound VAO
  if (ctx->boundVAO != 0) {
    auto vaoIt = ctx->vaos.find(ctx->boundVAO);
    if (vaoIt != ctx->vaos.end()) {
      // For now, use the bound array buffer
      if (ctx->boundArrayBuffer != 0) {
        auto bufIt = ctx->buffers.find(ctx->boundArrayBuffer);
        if (bufIt != ctx->buffers.end()) {
          [ctx->currentEncoder setVertexBuffer:bufIt->second
                                        offset:0
                                       atIndex:0];
        }
      }
    }
  } else if (ctx->boundArrayBuffer != 0) {
    auto bufIt = ctx->buffers.find(ctx->boundArrayBuffer);
    if (bufIt != ctx->buffers.end()) {
      [ctx->currentEncoder setVertexBuffer:bufIt->second offset:0 atIndex:0];
    }
  }

  // Draw
  [ctx->currentEncoder drawPrimitives:translatePrimitive(primitive)
                          vertexStart:first
                          vertexCount:count];
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nDrawElements(
    JNIEnv *, jclass, jlong, jint primitive, jint count,
    jlong indexBufferHandle, jlong offset) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx || indexBufferHandle == 0)
    return;

  // Ensure render pass is active
  if (!ctx->renderPassActive) {
    ensureRenderTarget(ctx, ctx->viewportW > 0 ? ctx->viewportW : ctx->width,
                       ctx->viewportH > 0 ? ctx->viewportH : ctx->height);
    beginRenderPass(ctx, ctx->colorTexture, ctx->depthTexture, false, false);
  }

  // Set pipeline and state
  bool hasTexture = ctx->boundTexture2D != 0;
  [ctx->currentEncoder setRenderPipelineState:hasTexture
                                                  ? ctx->texturedPipeline
                                                  : ctx->colorOnlyPipeline];
  [ctx->currentEncoder setDepthStencilState:ctx->depthTestEnabled
                                                ? ctx->depthStateEnabled
                                                : ctx->depthStateDisabled];

  // Set viewport
  MTLViewport viewport;
  viewport.originX = ctx->viewportX;
  viewport.originY = ctx->viewportY;
  viewport.width = ctx->viewportW;
  viewport.height = ctx->viewportH;
  viewport.znear = 0.0;
  viewport.zfar = 1.0;
  [ctx->currentEncoder setViewport:viewport];

  // Update uniforms
  struct {
    float projectionMatrix[16];
    float modelViewMatrix[16];
  } uniforms;
  memcpy(uniforms.projectionMatrix, ctx->projectionMatrix, 64);
  memcpy(uniforms.modelViewMatrix, ctx->modelViewMatrix, 64);
  memcpy([ctx->uniformsBuffer contents], &uniforms, sizeof(uniforms));
  [ctx->currentEncoder setVertexBuffer:ctx->uniformsBuffer offset:0 atIndex:1];

  // Set texture
  if (hasTexture) {
    auto it = ctx->textures.find(ctx->boundTexture2D);
    if (it != ctx->textures.end()) {
      [ctx->currentEncoder setFragmentTexture:it->second atIndex:0];
    } else {
      [ctx->currentEncoder setFragmentTexture:ctx->whiteTexture atIndex:0];
    }
    [ctx->currentEncoder setFragmentSamplerState:ctx->defaultSampler atIndex:0];
  }

  // Set vertex buffer
  if (ctx->boundArrayBuffer != 0) {
    auto bufIt = ctx->buffers.find(ctx->boundArrayBuffer);
    if (bufIt != ctx->buffers.end()) {
      [ctx->currentEncoder setVertexBuffer:bufIt->second offset:0 atIndex:0];
    }
  }

  // Get index buffer
  id<MTLBuffer> indexBuffer = (__bridge id<MTLBuffer>)(void *)indexBufferHandle;

  // Draw indexed
  [ctx->currentEncoder drawIndexedPrimitives:translatePrimitive(primitive)
                                  indexCount:count
                                   indexType:MTLIndexTypeUInt32
                                 indexBuffer:indexBuffer
                           indexBufferOffset:offset];
}

// New draw function that receives all state from Java
JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nDrawArraysWithHandles(
    JNIEnv *, jclass, jlong, jint primitive, jint first, jint count,
    jlong vertexBufferHandle, jlong textureHandle,
    jboolean depthTestEnabled, jint depthFunc,
    jboolean blendEnabled, jint blendSrcRGB, jint blendDstRGB, jint blendSrcAlpha, jint blendDstAlpha,
    jboolean cullEnabled, jint cullMode,
    jint viewportX, jint viewportY, jint viewportW, jint viewportH) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx)
    return;

  @autoreleasepool {
    // Ensure render pass is active
    if (!ctx->renderPassActive) {
      ensureRenderTarget(ctx, viewportW > 0 ? viewportW : ctx->width,
                         viewportH > 0 ? viewportH : ctx->height);
      beginRenderPass(ctx, ctx->colorTexture, ctx->depthTexture, false, false);
    }

    // Set pipeline based on whether we have a texture
    bool hasTexture = (textureHandle != 0);
    [ctx->currentEncoder setRenderPipelineState:hasTexture
                                                    ? ctx->texturedPipeline
                                                    : ctx->colorOnlyPipeline];
    
    // Set depth state
    [ctx->currentEncoder setDepthStencilState:depthTestEnabled
                                                  ? ctx->depthStateEnabled
                                                  : ctx->depthStateDisabled];

    // Set viewport
    MTLViewport viewport;
    viewport.originX = viewportX;
    viewport.originY = viewportY;
    viewport.width = viewportW;
    viewport.height = viewportH;
    viewport.znear = 0.0;
    viewport.zfar = 1.0;
    [ctx->currentEncoder setViewport:viewport];

    // Update uniforms (identity matrices for now - need proper MVP upload)
    struct {
      float projectionMatrix[16];
      float modelViewMatrix[16];
    } uniforms;
    memcpy(uniforms.projectionMatrix, ctx->projectionMatrix, 64);
    memcpy(uniforms.modelViewMatrix, ctx->modelViewMatrix, 64);
    memcpy([ctx->uniformsBuffer contents], &uniforms, sizeof(uniforms));
    [ctx->currentEncoder setVertexBuffer:ctx->uniformsBuffer offset:0 atIndex:1];

    // Set texture
    if (hasTexture) {
      id<MTLTexture> texture = (__bridge id<MTLTexture>)(void *)textureHandle;
      [ctx->currentEncoder setFragmentTexture:texture atIndex:0];
      [ctx->currentEncoder setFragmentSamplerState:ctx->defaultSampler atIndex:0];
    } else {
      [ctx->currentEncoder setFragmentTexture:ctx->whiteTexture atIndex:0];
      [ctx->currentEncoder setFragmentSamplerState:ctx->defaultSampler atIndex:0];
    }

    // Set vertex buffer
    if (vertexBufferHandle != 0) {
      id<MTLBuffer> vertexBuffer = (__bridge id<MTLBuffer>)(void *)vertexBufferHandle;
      [ctx->currentEncoder setVertexBuffer:vertexBuffer offset:0 atIndex:0];
    }

    // Draw
    [ctx->currentEncoder drawPrimitives:translatePrimitive(primitive)
                            vertexStart:first
                            vertexCount:count];
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nDrawElementsWithHandles(
    JNIEnv *, jclass, jlong, jint primitive, jint count,
    jlong indexBufferHandle, jlong indexOffset,
    jlong vertexBufferHandle, jlong textureHandle,
    jboolean depthTestEnabled, jint depthFunc,
    jboolean blendEnabled, jint blendSrcRGB, jint blendDstRGB, jint blendSrcAlpha, jint blendDstAlpha,
    jboolean cullEnabled, jint cullMode,
    jint viewportX, jint viewportY, jint viewportW, jint viewportH) {

  GL2MetalContext *ctx = gGlobalGL2MetalCtx;
  if (!ctx || indexBufferHandle == 0)
    return;

  @autoreleasepool {
    // Ensure render pass is active
    if (!ctx->renderPassActive) {
      ensureRenderTarget(ctx, viewportW > 0 ? viewportW : ctx->width,
                         viewportH > 0 ? viewportH : ctx->height);
      beginRenderPass(ctx, ctx->colorTexture, ctx->depthTexture, false, false);
    }

    // Set pipeline based on whether we have a texture
    bool hasTexture = (textureHandle != 0);
    [ctx->currentEncoder setRenderPipelineState:hasTexture
                                                    ? ctx->texturedPipeline
                                                    : ctx->colorOnlyPipeline];
    
    // Set depth state
    [ctx->currentEncoder setDepthStencilState:depthTestEnabled
                                                  ? ctx->depthStateEnabled
                                                  : ctx->depthStateDisabled];

    // Set viewport
    MTLViewport viewport;
    viewport.originX = viewportX;
    viewport.originY = viewportY;
    viewport.width = viewportW;
    viewport.height = viewportH;
    viewport.znear = 0.0;
    viewport.zfar = 1.0;
    [ctx->currentEncoder setViewport:viewport];

    // Update uniforms
    struct {
      float projectionMatrix[16];
      float modelViewMatrix[16];
    } uniforms;
    memcpy(uniforms.projectionMatrix, ctx->projectionMatrix, 64);
    memcpy(uniforms.modelViewMatrix, ctx->modelViewMatrix, 64);
    memcpy([ctx->uniformsBuffer contents], &uniforms, sizeof(uniforms));
    [ctx->currentEncoder setVertexBuffer:ctx->uniformsBuffer offset:0 atIndex:1];

    // Set texture
    if (hasTexture) {
      id<MTLTexture> texture = (__bridge id<MTLTexture>)(void *)textureHandle;
      [ctx->currentEncoder setFragmentTexture:texture atIndex:0];
      [ctx->currentEncoder setFragmentSamplerState:ctx->defaultSampler atIndex:0];
    } else {
      [ctx->currentEncoder setFragmentTexture:ctx->whiteTexture atIndex:0];
      [ctx->currentEncoder setFragmentSamplerState:ctx->defaultSampler atIndex:0];
    }

    // Set vertex buffer
    if (vertexBufferHandle != 0) {
      id<MTLBuffer> vertexBuffer = (__bridge id<MTLBuffer>)(void *)vertexBufferHandle;
      [ctx->currentEncoder setVertexBuffer:vertexBuffer offset:0 atIndex:0];
    }

    // Get index buffer
    id<MTLBuffer> indexBuffer = (__bridge id<MTLBuffer>)(void *)indexBufferHandle;

    // Draw indexed
    [ctx->currentEncoder drawIndexedPrimitives:translatePrimitive(primitive)
                                    indexCount:count
                                     indexType:MTLIndexTypeUInt32
                                   indexBuffer:indexBuffer
                             indexBufferOffset:indexOffset];
  }
}

// ============================================================================
// Window Management - GLFW Sync
// ============================================================================

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSyncWithGLFWWindow(
    JNIEnv *, jclass, jlong windowHandle, jlong glfwWindowPtr, jint x, jint y,
    jint width, jint height) {
  (void)glfwWindowPtr;

  MetalWindowContext *wctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!wctx || !wctx->window)
    return;

  // Always update to ensure sync (don't cache - let Java side decide if
  // changed)

  // Update Metal window position and size to match GLFW
  dispatch_async(dispatch_get_main_queue(), ^{
    // Get the scale factor for Retina displays
    CGFloat scale = wctx->window.backingScaleFactor;
    if (scale < 1.0)
      scale = 1.0;

    // GLFW getX/getY are the window position (content area top-left in GLFW
    // space) GLFW getFramebufferWidth/Height are in pixels (need to divide by
    // scale for points)
    CGFloat windowWidth = width / scale;
    CGFloat windowHeight = height / scale;

    // GLFW uses top-left origin, NSWindow uses bottom-left origin for the
    // WINDOW FRAME But we need to set the content frame, not the window frame

    // Get screen height for coordinate conversion (in points)
    NSScreen *screen = wctx->window.screen ?: [NSScreen mainScreen];
    CGFloat screenHeight = screen.frame.size.height;

    // GLFW y is from top of screen to top of content area
    // For NSWindow contentRect, we need bottom-left of content area
    // bottomY = screenHeight - (glfwY + contentHeight)
    CGFloat contentY = screenHeight - y - windowHeight;

    // Create the content rect (position and size of the content area)
    NSRect contentRect = NSMakeRect(x, contentY, windowWidth, windowHeight);

    // Convert content rect to frame rect to include title bar
    // Since we have NSWindowStyleMaskTitled, the frame will be larger
    NSRect frameRect =
        [NSWindow frameRectForContentRect:contentRect
                                styleMask:wctx->window.styleMask];

    // Only log occasionally to avoid spam
    static int logCounter = 0;
    if (logCounter++ % 300 == 0) {
      printf("[GL2Metal] Sync: GLFW pos=(%d,%d) fb=%dx%d scale=%.1f -> "
             "content=(%.0f,%.0f,%.0f,%.0f) frame=(%.0f,%.0f,%.0f,%.0f)\n",
             x, y, width, height, scale, contentRect.origin.x,
             contentRect.origin.y, contentRect.size.width,
             contentRect.size.height, frameRect.origin.x, frameRect.origin.y,
             frameRect.size.width, frameRect.size.height);
    }

    // Set the window frame
    [wctx->window setFrame:frameRect display:YES animate:NO];

    if (wctx->metalLayer) {
      wctx->metalLayer.drawableSize = CGSizeMake(width, height);
    }

    wctx->width = width;
    wctx->height = height;

    if (wctx->gl2metalCtx) {
      wctx->gl2metalCtx->width = width;
      wctx->gl2metalCtx->height = height;
    }
  });
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nBringWindowToFront(
    JNIEnv *, jclass, jlong windowHandle) {

  MetalWindowContext *wctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!wctx || !wctx->window)
    return;

  dispatch_async(dispatch_get_main_queue(), ^{
    [wctx->window makeKeyAndOrderFront:nil];
  });
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nShouldClose(
    JNIEnv *, jclass, jlong windowHandle) {

  MetalWindowContext *wctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!wctx)
    return JNI_FALSE; // Don't close if context is null

  // Only return true if user explicitly closed the window
  return wctx->shouldClose ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nPollEvents(
    JNIEnv *, jclass, jlong windowHandle) {
  (void)windowHandle;
  // In Option 2 mode, GLFW handles all input events
  // We don't need to poll NSApp events ourselves as that would interfere
  // with GLFW's event handling
  // Just a no-op to keep the API consistent
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nEnableInputForwarding(
    JNIEnv *, jclass, jlong windowHandle, jlong glfwWindowPtr, jint x, jint y,
    jint width, jint height) {
  (void)glfwWindowPtr;

  MetalWindowContext *wctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!wctx || !wctx->window)
    return;

  // Now that GLFW window is hidden, Metal window should accept mouse events
  // and be the primary window for interaction
  dispatch_async(dispatch_get_main_queue(), ^{
    // Re-enable mouse events on Metal window
    [wctx->window setIgnoresMouseEvents:NO];

    // Set the window frame to match GLFW window position
    // Note: on Retina, framebuffer size is 2x window size
    CGFloat scale = [NSScreen mainScreen].backingScaleFactor;
    CGFloat windowWidth = width / scale;
    CGFloat windowHeight = height / scale;

    // Get screen height for coordinate conversion (NSWindow uses bottom-left
    // origin)
    CGFloat screenHeight = [[NSScreen mainScreen] frame].size.height;
    CGFloat flippedY = screenHeight - y - windowHeight;

    NSRect frame = NSMakeRect(x, flippedY, windowWidth, windowHeight);
    [wctx->window setFrame:frame display:YES animate:NO];

    // Make this window key since it's now the primary window
    [wctx->window makeKeyAndOrderFront:nil];

    printf("[GL2Metal] Input forwarding enabled - Metal window is now "
           "interactive at (%d, %d) size %dx%d\n",
           x, y, width, height);
  });
}

JNIEXPORT void JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nSetMinimized(
    JNIEnv *, jclass, jlong windowHandle, jboolean minimized) {

  MetalWindowContext *wctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!wctx || !wctx->window)
    return;

  dispatch_async(dispatch_get_main_queue(), ^{
    if (minimized) {
      // Miniaturize (minimize) the Metal window
      if (![wctx->window isMiniaturized]) {
        [wctx->window miniaturize:nil];
      }
    } else {
      // Restore the Metal window from dock
      if ([wctx->window isMiniaturized]) {
        [wctx->window deminiaturize:nil];
      }
    }
  });
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_gl2metal_GL2MetalTranslator_nIsMinimized(
    JNIEnv *, jclass, jlong windowHandle) {

  MetalWindowContext *wctx =
      reinterpret_cast<MetalWindowContext *>(windowHandle);
  if (!wctx || !wctx->window)
    return JNI_FALSE;

  __block BOOL isMin = NO;
  if ([NSThread isMainThread]) {
    isMin = [wctx->window isMiniaturized];
  } else {
    dispatch_sync(dispatch_get_main_queue(), ^{
      isMin = [wctx->window isMiniaturized];
    });
  }
  return isMin ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
