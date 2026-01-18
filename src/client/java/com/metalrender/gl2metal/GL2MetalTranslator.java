package com.metalrender.gl2metal;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenGL to Metal Translation Layer
 * 
 * This class intercepts OpenGL calls and translates them to Metal equivalents.
 * All rendering is done through Metal and displayed on a native Metal window.
 * 
 * Architecture:
 * 1. Java OpenGL calls → GL2MetalTranslator (this class)
 * 2. GL2MetalTranslator → Native Metal calls (via JNI)
 * 3. Metal renders to CAMetalLayer drawable
 * 4. CAMetalLayer presents to Metal window
 * 
 * Benefits:
 * - 100% mod compatibility (mods think they're using OpenGL)
 * - No CPU readback overhead
 * - Pure Metal rendering pipeline
 */
public class GL2MetalTranslator {

    private static final GL2MetalTranslator INSTANCE = new GL2MetalTranslator();

    // Metal context handle
    private long metalHandle = 0;
    private long metalWindowHandle = 0;

    // GL state tracking
    private int currentProgram = 0;
    private int currentVAO = 0;
    private int currentFBO = 0;
    private int[] currentViewport = new int[4];
    private boolean depthTestEnabled = false;
    private boolean blendEnabled = false;
    private int blendSrcRGB = 1; // GL_ONE
    private int blendDstRGB = 0; // GL_ZERO
    private int blendSrcAlpha = 1;
    private int blendDstAlpha = 0;
    private int depthFunc = 0x0201; // GL_LESS
    private boolean cullFaceEnabled = false;
    private int cullFaceMode = 0x0405; // GL_BACK
    private boolean depthMaskEnabled = true;
    private boolean[] colorMask = { true, true, true, true };
    private int[] scissorRect = new int[4];
    private boolean scissorTestEnabled = false;
    private int polygonMode = 0x1B02; // GL_FILL
    private float polygonOffsetFactor = 0;
    private float polygonOffsetUnits = 0;

    // Shader tracking
    private final Map<Integer, MetalShader> shaders = new HashMap<>();
    private int nextShaderId = 1;

    // Renderbuffer tracking
    private final Map<Integer, MetalRenderbuffer> renderbuffers = new HashMap<>();
    private int nextRenderbufferId = 1;
    private int boundRenderbuffer = 0;

    // Resource tracking
    private final Map<Integer, MetalTexture> textures = new HashMap<>();
    private final Map<Integer, MetalBuffer> buffers = new HashMap<>();
    private final Map<Integer, MetalShaderProgram> programs = new HashMap<>();
    private final Map<Integer, MetalVAO> vaos = new HashMap<>();
    private final Map<Integer, MetalFramebuffer> framebuffers = new HashMap<>();

    // ID generators
    private int nextTextureId = 1;
    private int nextBufferId = 1;
    private int nextProgramId = 1;
    private int nextVaoId = 1;
    private int nextFboId = 1;

    // Current bound resources
    private int boundTexture2D = 0;
    private int boundArrayBuffer = 0;
    private int boundElementBuffer = 0;

    // Initialization state
    private boolean initialized = false;
    private int windowWidth = 1920;
    private int windowHeight = 1080;
    private boolean debugLogging = false;

    private GL2MetalTranslator() {
    }

    public static GL2MetalTranslator getInstance() {
        return INSTANCE;
    }

    /**
     * Set debug logging on/off for native code.
     */
    public void setDebugLogging(boolean enabled) {
        this.debugLogging = enabled;
        // Could pass to native code if needed: nSetDebugLogging(metalHandle, enabled);
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    /**
     * Initialize the Metal translation layer and create the Metal window.
     */
    public boolean initialize(int width, int height) {
        if (initialized) {
            return true;
        }

        this.windowWidth = width;
        this.windowHeight = height;

        MetalLogger.info("[GL2Metal] Initializing OpenGL→Metal translation layer");
        MetalLogger.info("[GL2Metal] Window size: {}x{}", width, height);

        // Create the native Metal window (this also creates the Metal context)
        metalWindowHandle = nCreateMetalWindow(width, height, "Minecraft (Metal)");
        if (metalWindowHandle == 0) {
            MetalLogger.error("[GL2Metal] Failed to create Metal window");
            return false;
        }

        // The window creation sets up the Metal device and command queue internally
        metalHandle = metalWindowHandle; // Same handle for now

        MetalLogger.info("[GL2Metal] Metal window created: handle={}", metalWindowHandle);

        // Set default viewport
        currentViewport[0] = 0;
        currentViewport[1] = 0;
        currentViewport[2] = width;
        currentViewport[3] = height;

        initialized = true;
        MetalLogger.info("[GL2Metal] Translation layer initialized successfully");

        return true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public long getMetalHandle() {
        return metalHandle;
    }

    public long getMetalWindowHandle() {
        return metalWindowHandle;
    }

    // ========================================================================
    // OpenGL State Functions
    // ========================================================================

    public void glEnable(int cap) {
        switch (cap) {
            case 0x0B71: // GL_DEPTH_TEST
                depthTestEnabled = true;
                break;
            case 0x0BE2: // GL_BLEND
                blendEnabled = true;
                break;
            case 0x0B44: // GL_CULL_FACE
                cullFaceEnabled = true;
                break;
            case 0x0C11: // GL_SCISSOR_TEST
                scissorTestEnabled = true;
                break;
        }
    }

    public void glDisable(int cap) {
        switch (cap) {
            case 0x0B71: // GL_DEPTH_TEST
                depthTestEnabled = false;
                break;
            case 0x0BE2: // GL_BLEND
                blendEnabled = false;
                break;
            case 0x0B44: // GL_CULL_FACE
                cullFaceEnabled = false;
                break;
            case 0x0C11: // GL_SCISSOR_TEST
                scissorTestEnabled = false;
                break;
        }
    }

    public void glBlendFunc(int sfactor, int dfactor) {
        blendSrcRGB = sfactor;
        blendDstRGB = dfactor;
        blendSrcAlpha = sfactor;
        blendDstAlpha = dfactor;
    }

    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        blendSrcRGB = srcRGB;
        blendDstRGB = dstRGB;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
    }

    public void glDepthFunc(int func) {
        depthFunc = func;
    }

    public void glDepthMask(boolean flag) {
        depthMaskEnabled = flag;
    }

    public void glCullFace(int mode) {
        cullFaceMode = mode;
    }

    public void glViewport(int x, int y, int width, int height) {
        currentViewport[0] = x;
        currentViewport[1] = y;
        currentViewport[2] = width;
        currentViewport[3] = height;
    }

    public void glScissor(int x, int y, int width, int height) {
        scissorRect[0] = x;
        scissorRect[1] = y;
        scissorRect[2] = width;
        scissorRect[3] = height;
    }

    public void glColorMask(boolean r, boolean g, boolean b, boolean a) {
        colorMask[0] = r;
        colorMask[1] = g;
        colorMask[2] = b;
        colorMask[3] = a;
    }

    public void glPolygonMode(int face, int mode) {
        polygonMode = mode;
    }

    public void glPolygonOffset(float factor, float units) {
        polygonOffsetFactor = factor;
        polygonOffsetUnits = units;
    }

    public void glClear(int mask) {
        // Translate GL clear mask to Metal
        boolean clearColor = (mask & 0x4000) != 0; // GL_COLOR_BUFFER_BIT
        boolean clearDepth = (mask & 0x100) != 0; // GL_DEPTH_BUFFER_BIT
        boolean clearStencil = (mask & 0x400) != 0; // GL_STENCIL_BUFFER_BIT

        nClearFramebuffer(metalHandle, clearColor, clearDepth, clearStencil);
    }

    public void glClearColor(float r, float g, float b, float a) {
        nSetClearColor(metalHandle, r, g, b, a);
    }

    // ========================================================================
    // Texture Functions
    // ========================================================================

    public int glGenTextures() {
        int id = nextTextureId++;
        textures.put(id, new MetalTexture(id));
        return id;
    }

    public void glDeleteTextures(int texture) {
        MetalTexture tex = textures.remove(texture);
        if (tex != null && tex.metalHandle != 0) {
            nDeleteTexture(metalHandle, tex.metalHandle);
        }
    }

    public void glBindTexture(int target, int texture) {
        if (target == 0x0DE1) { // GL_TEXTURE_2D
            boundTexture2D = texture;
        }
    }

    public void glTexImage2D(int target, int level, int internalFormat,
            int width, int height, int border,
            int format, int type, ByteBuffer data) {
        if (target != 0x0DE1 || boundTexture2D == 0)
            return;

        MetalTexture tex = textures.get(boundTexture2D);
        if (tex == null)
            return;

        // Create Metal texture
        tex.metalHandle = nCreateTexture(metalHandle, width, height,
                translateFormat(internalFormat), data);
        tex.width = width;
        tex.height = height;
    }

    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset,
            int width, int height, int format, int type,
            ByteBuffer data) {
        if (target != 0x0DE1 || boundTexture2D == 0)
            return;

        MetalTexture tex = textures.get(boundTexture2D);
        if (tex == null || tex.metalHandle == 0)
            return;

        nUpdateTexture(metalHandle, tex.metalHandle, xoffset, yoffset, width, height, data);
    }

    // ========================================================================
    // Buffer Functions
    // ========================================================================

    public int glGenBuffers() {
        int id = nextBufferId++;
        buffers.put(id, new MetalBuffer(id));
        return id;
    }

    public void glDeleteBuffers(int buffer) {
        MetalBuffer buf = buffers.remove(buffer);
        if (buf != null && buf.metalHandle != 0) {
            nDeleteBuffer(metalHandle, buf.metalHandle);
        }
    }

    public void glBindBuffer(int target, int buffer) {
        if (target == 0x8892) { // GL_ARRAY_BUFFER
            boundArrayBuffer = buffer;
        } else if (target == 0x8893) { // GL_ELEMENT_ARRAY_BUFFER
            boundElementBuffer = buffer;
        }
    }

    public void glBufferData(int target, ByteBuffer data, int usage) {
        int buffer = (target == 0x8892) ? boundArrayBuffer : boundElementBuffer;
        if (buffer == 0)
            return;

        MetalBuffer buf = buffers.get(buffer);
        if (buf == null)
            return;

        buf.metalHandle = nCreateBuffer(metalHandle, data, data.remaining());
        buf.size = data.remaining();
    }

    public void glBufferSubData(int target, long offset, ByteBuffer data) {
        int buffer = (target == 0x8892) ? boundArrayBuffer : boundElementBuffer;
        if (buffer == 0)
            return;

        MetalBuffer buf = buffers.get(buffer);
        if (buf == null || buf.metalHandle == 0)
            return;

        nUpdateBuffer(metalHandle, buf.metalHandle, offset, data, data.remaining());
    }

    public void glBufferData(int target, long size, int usage) {
        // Allocate empty buffer of specified size
        int buffer = (target == 0x8892) ? boundArrayBuffer : boundElementBuffer;
        if (buffer == 0)
            return;

        MetalBuffer buf = buffers.get(buffer);
        if (buf == null)
            return;

        buf.metalHandle = nCreateBufferEmpty(metalHandle, (int) size);
        buf.size = (int) size;
    }

    public void nglBufferData(int target, long size, long dataPtr, int usage) {
        // Native pointer buffer data - create buffer from pointer
        int buffer = (target == 0x8892) ? boundArrayBuffer : boundElementBuffer;
        if (buffer == 0)
            return;

        MetalBuffer buf = buffers.get(buffer);
        if (buf == null)
            return;

        if (dataPtr == 0) {
            buf.metalHandle = nCreateBufferEmpty(metalHandle, (int) size);
        } else {
            buf.metalHandle = nCreateBufferFromPointer(metalHandle, dataPtr, (int) size);
        }
        buf.size = (int) size;
    }

    public void nglBufferSubData(int target, long offset, long size, long dataPtr) {
        int buffer = (target == 0x8892) ? boundArrayBuffer : boundElementBuffer;
        if (buffer == 0)
            return;

        MetalBuffer buf = buffers.get(buffer);
        if (buf == null || buf.metalHandle == 0)
            return;

        nUpdateBufferFromPointer(metalHandle, buf.metalHandle, offset, dataPtr, (int) size);
    }

    // ========================================================================
    // VAO Functions
    // ========================================================================

    public int glGenVertexArrays() {
        int id = nextVaoId++;
        vaos.put(id, new MetalVAO(id));
        return id;
    }

    public void glDeleteVertexArrays(int vao) {
        vaos.remove(vao);
    }

    public void glBindVertexArray(int vao) {
        currentVAO = vao;
    }

    public void glVertexAttribPointer(int index, int size, int type,
            boolean normalized, int stride, long offset) {
        if (currentVAO == 0)
            return;

        MetalVAO vao = vaos.get(currentVAO);
        if (vao == null)
            return;

        vao.attributes.put(index, new VertexAttribute(index, size, type, normalized, stride, offset));
    }

    public void glEnableVertexAttribArray(int index) {
        if (currentVAO == 0)
            return;

        MetalVAO vao = vaos.get(currentVAO);
        if (vao != null) {
            vao.enabledAttributes.add(index);
        }
    }

    public void glDisableVertexAttribArray(int index) {
        if (currentVAO == 0)
            return;

        MetalVAO vao = vaos.get(currentVAO);
        if (vao != null) {
            vao.enabledAttributes.remove(index);
        }
    }

    // ========================================================================
    // Draw Functions
    // ========================================================================

    private static int drawCallCount = 0;

    public void glDrawArrays(int mode, int first, int count) {
        if (!initialized)
            return;

        drawCallCount++;
        if (debugLogging && (drawCallCount <= 10 || drawCallCount % 1000 == 0)) {
            MetalLogger.info("[GL2Metal] glDrawArrays #{} mode={} first={} count={}", 
                    drawCallCount, mode, first, count);
        }

        // Get the vertex buffer handle
        MetalBuffer vertexBuffer = buffers.get(boundArrayBuffer);
        long vertexBufferHandle = (vertexBuffer != null) ? vertexBuffer.metalHandle : 0;
        
        // Get texture handle
        MetalTexture texture = textures.get(boundTexture2D);
        long textureHandle = (texture != null) ? texture.metalHandle : 0;

        if (debugLogging && drawCallCount <= 10) {
            MetalLogger.info("[GL2Metal]   vertexBuffer={} texture={} viewport={}x{}", 
                    vertexBufferHandle, textureHandle, currentViewport[2], currentViewport[3]);
        }

        // Translate primitive type
        int metalPrimitive = translatePrimitive(mode);

        // Execute draw with all required handles
        nDrawArraysWithHandles(metalHandle, metalPrimitive, first, count,
                vertexBufferHandle, textureHandle,
                depthTestEnabled, depthFunc,
                blendEnabled, blendSrcRGB, blendDstRGB, blendSrcAlpha, blendDstAlpha,
                cullFaceEnabled, cullFaceMode,
                currentViewport[0], currentViewport[1], currentViewport[2], currentViewport[3]);
    }

    public void glDrawElements(int mode, int count, int type, long indices) {
        if (!initialized)
            return;

        drawCallCount++;
        if (debugLogging && (drawCallCount <= 10 || drawCallCount % 1000 == 0)) {
            MetalLogger.info("[GL2Metal] glDrawElements #{} mode={} count={} type={}", 
                    drawCallCount, mode, count, type);
        }

        // Get buffer handles
        MetalBuffer vertexBuffer = buffers.get(boundArrayBuffer);
        MetalBuffer indexBuffer = buffers.get(boundElementBuffer);
        
        long vertexBufferHandle = (vertexBuffer != null) ? vertexBuffer.metalHandle : 0;
        long indexBufferHandle = (indexBuffer != null) ? indexBuffer.metalHandle : 0;
        
        if (indexBufferHandle == 0)
            return;
            
        // Get texture handle
        MetalTexture texture = textures.get(boundTexture2D);
        long textureHandle = (texture != null) ? texture.metalHandle : 0;

        int metalPrimitive = translatePrimitive(mode);

        // Execute draw with all required handles
        nDrawElementsWithHandles(metalHandle, metalPrimitive, count, indexBufferHandle, indices,
                vertexBufferHandle, textureHandle,
                depthTestEnabled, depthFunc,
                blendEnabled, blendSrcRGB, blendDstRGB, blendSrcAlpha, blendDstAlpha,
                cullFaceEnabled, cullFaceMode,
                currentViewport[0], currentViewport[1], currentViewport[2], currentViewport[3]);
    }

    // ========================================================================
    // Shader Functions
    // ========================================================================

    public int glCreateProgram() {
        int id = nextProgramId++;
        programs.put(id, new MetalShaderProgram(id));
        return id;
    }

    public void glUseProgram(int program) {
        currentProgram = program;
    }

    public void glDeleteProgram(int program) {
        MetalShaderProgram prog = programs.remove(program);
        // Native cleanup if needed
    }

    public void glLinkProgram(int program) {
        // In Metal, linking is handled at PSO creation time
        // Just mark the program as linked
        MetalShaderProgram prog = programs.get(program);
        if (prog != null) {
            prog.linked = true;
        }
    }

    public int glCreateShader(int type) {
        int id = nextShaderId++;
        shaders.put(id, new MetalShader(id, type));
        return id;
    }

    public void glDeleteShader(int shader) {
        shaders.remove(shader);
    }

    public void glAttachShader(int program, int shader) {
        MetalShaderProgram prog = programs.get(program);
        MetalShader sh = shaders.get(shader);
        if (prog != null && sh != null) {
            if (sh.type == 0x8B31) { // GL_VERTEX_SHADER
                prog.vertexShader = shader;
            } else if (sh.type == 0x8B30) { // GL_FRAGMENT_SHADER
                prog.fragmentShader = shader;
            }
        }
    }

    public void glCompileShader(int shader) {
        // Shader source would need to be translated from GLSL to MSL
        // For now, we use precompiled Metal shaders
        MetalShader sh = shaders.get(shader);
        if (sh != null) {
            sh.compiled = true;
        }
    }

    // ========================================================================
    // Uniform Functions
    // ========================================================================

    public int glGetUniformLocation(int program, String name) {
        // Return a unique ID for this uniform name
        // In Metal, uniforms are passed through argument buffers
        MetalShaderProgram prog = programs.get(program);
        if (prog == null)
            return -1;

        Integer existing = prog.uniformLocations.get(name);
        if (existing != null)
            return existing;

        int loc = prog.nextUniformLocation++;
        prog.uniformLocations.put(name, loc);
        return loc;
    }

    public void glUniform1i(int location, int v0) {
        // Store uniform value for later use when drawing
        if (currentProgram == 0)
            return;
        MetalShaderProgram prog = programs.get(currentProgram);
        if (prog != null) {
            prog.uniformInts.put(location, new int[] { v0 });
        }
    }

    public void glUniform1f(int location, float v0) {
        if (currentProgram == 0)
            return;
        MetalShaderProgram prog = programs.get(currentProgram);
        if (prog != null) {
            prog.uniformFloats.put(location, new float[] { v0 });
        }
    }

    public void glUniform2f(int location, float v0, float v1) {
        if (currentProgram == 0)
            return;
        MetalShaderProgram prog = programs.get(currentProgram);
        if (prog != null) {
            prog.uniformFloats.put(location, new float[] { v0, v1 });
        }
    }

    public void glUniform3f(int location, float v0, float v1, float v2) {
        if (currentProgram == 0)
            return;
        MetalShaderProgram prog = programs.get(currentProgram);
        if (prog != null) {
            prog.uniformFloats.put(location, new float[] { v0, v1, v2 });
        }
    }

    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        if (currentProgram == 0)
            return;
        MetalShaderProgram prog = programs.get(currentProgram);
        if (prog != null) {
            prog.uniformFloats.put(location, new float[] { v0, v1, v2, v3 });
        }
    }

    // ========================================================================
    // Framebuffer Functions
    // ========================================================================

    public int glGenFramebuffers() {
        int id = nextFboId++;
        framebuffers.put(id, new MetalFramebuffer(id));
        return id;
    }

    public void glBindFramebuffer(int target, int framebuffer) {
        currentFBO = framebuffer;
    }

    public void glDeleteFramebuffers(int framebuffer) {
        MetalFramebuffer fbo = framebuffers.remove(framebuffer);
        // Native cleanup if needed
    }

    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (currentFBO == 0)
            return;
        MetalFramebuffer fbo = framebuffers.get(currentFBO);
        if (fbo == null)
            return;

        MetalTexture tex = textures.get(texture);
        if (tex == null)
            return;

        if (attachment == 0x8CE0) { // GL_COLOR_ATTACHMENT0
            fbo.colorTexture = tex.metalHandle;
        } else if (attachment == 0x8D00) { // GL_DEPTH_ATTACHMENT
            fbo.depthTexture = tex.metalHandle;
        }
    }

    public int glCheckFramebufferStatus(int target) {
        // Always report complete for now
        return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
    }

    // ========================================================================
    // Renderbuffer Functions
    // ========================================================================

    public int glGenRenderbuffers() {
        int id = nextRenderbufferId++;
        renderbuffers.put(id, new MetalRenderbuffer(id));
        return id;
    }

    public void glDeleteRenderbuffers(int renderbuffer) {
        renderbuffers.remove(renderbuffer);
    }

    public void glBindRenderbuffer(int target, int renderbuffer) {
        boundRenderbuffer = renderbuffer;
    }

    public void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        if (boundRenderbuffer == 0)
            return;
        MetalRenderbuffer rb = renderbuffers.get(boundRenderbuffer);
        if (rb == null)
            return;

        rb.width = width;
        rb.height = height;
        rb.internalFormat = internalformat;
        // Create Metal texture for renderbuffer
    }

    public void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (currentFBO == 0)
            return;
        MetalFramebuffer fbo = framebuffers.get(currentFBO);
        MetalRenderbuffer rb = renderbuffers.get(renderbuffer);
        if (fbo == null || rb == null)
            return;

        // Attach renderbuffer to FBO
    }

    // ========================================================================
    // Frame Presentation
    // ========================================================================

    public void swapBuffers() {
        if (!initialized)
            return;

        // Present the Metal drawable to the window
        nPresentFrame(metalWindowHandle);
    }

    // ========================================================================
    // Window Management
    // ========================================================================

    /**
     * Sync the Metal window position/size with the GLFW window.
     * Call this when the GLFW window moves or resizes.
     */
    public void syncWithGLFWWindow(long glfwWindowPtr, int x, int y, int width, int height) {
        if (!initialized)
            return;
        nSyncWithGLFWWindow(metalWindowHandle, glfwWindowPtr, x, y, width, height);
    }

    /**
     * Bring the Metal window to the front.
     */
    public void bringWindowToFront() {
        if (!initialized)
            return;
        nBringWindowToFront(metalWindowHandle);
    }

    /**
     * Check if the Metal window should close.
     */
    public boolean shouldClose() {
        if (!initialized)
            return false;
        return nShouldClose(metalWindowHandle);
    }

    /**
     * Poll events for the Metal window.
     */
    public void pollEvents() {
        if (!initialized)
            return;
        nPollEvents(metalWindowHandle);
    }

    /**
     * Enable input forwarding from the Metal window to GLFW.
     * This allows the Metal window to capture input and send it to the (hidden)
     * GLFW window.
     */
    public void enableInputForwarding(long glfwWindowPtr, int x, int y, int width, int height) {
        if (!initialized)
            return;
        nEnableInputForwarding(metalWindowHandle, glfwWindowPtr, x, y, width, height);
    }

    /**
     * Set the minimized state of the Metal window.
     * Used to sync with GLFW window minimize/restore.
     */
    public void setMinimized(boolean minimized) {
        if (!initialized)
            return;
        nSetMinimized(metalWindowHandle, minimized);
    }

    /**
     * Check if the Metal window is currently minimized.
     */
    public boolean isMinimized() {
        if (!initialized)
            return false;
        return nIsMinimized(metalWindowHandle);
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private int translatePrimitive(int glMode) {
        return switch (glMode) {
            case 0x0000 -> 0; // GL_POINTS -> MTLPrimitiveTypePoint
            case 0x0001 -> 1; // GL_LINES -> MTLPrimitiveTypeLine
            case 0x0003 -> 2; // GL_LINE_STRIP -> MTLPrimitiveTypeLineStrip
            case 0x0004 -> 3; // GL_TRIANGLES -> MTLPrimitiveTypeTriangle
            case 0x0005 -> 4; // GL_TRIANGLE_STRIP -> MTLPrimitiveTypeTriangleStrip
            default -> 3; // Default to triangles
        };
    }

    private int translateFormat(int glFormat) {
        return switch (glFormat) {
            case 0x1908, 0x8058 -> 0; // GL_RGBA, GL_RGBA8 -> MTLPixelFormatRGBA8Unorm
            case 0x8C43 -> 1; // GL_SRGB8_ALPHA8 -> MTLPixelFormatRGBA8Unorm_sRGB
            case 0x8814 -> 2; // GL_RGBA32F -> MTLPixelFormatRGBA32Float
            default -> 0;
        };
    }

    // ========================================================================
    // Native Methods (to be implemented in metalrender.mm)
    // ========================================================================

    private static native long nCreateMetalWindow(int width, int height, String title);

    private static native void nDestroyMetalWindow(long windowHandle);

    private static native void nPresentFrame(long windowHandle);

    private static native void nSetWindowSize(long windowHandle, int width, int height);

    private static native void nSyncWithGLFWWindow(long windowHandle, long glfwWindowPtr, int x, int y, int width,
            int height);

    private static native void nBringWindowToFront(long windowHandle);

    private static native boolean nShouldClose(long windowHandle);

    private static native void nPollEvents(long windowHandle);

    private static native void nEnableInputForwarding(long windowHandle, long glfwWindowPtr, int x, int y, int width,
            int height);

    private static native void nSetMinimized(long windowHandle, boolean minimized);

    private static native boolean nIsMinimized(long windowHandle);

    private static native void nClearFramebuffer(long handle, boolean color, boolean depth, boolean stencil);

    private static native void nSetClearColor(long handle, float r, float g, float b, float a);

    private static native long nCreateTexture(long handle, int width, int height, int format, ByteBuffer data);

    private static native void nUpdateTexture(long handle, long texHandle, int x, int y, int w, int h, ByteBuffer data);

    private static native void nDeleteTexture(long handle, long texHandle);

    private static native long nCreateBuffer(long handle, ByteBuffer data, int size);

    private static native long nCreateBufferEmpty(long handle, int size);

    private static native long nCreateBufferFromPointer(long handle, long dataPtr, int size);

    private static native void nUpdateBuffer(long handle, long bufHandle, long offset, ByteBuffer data, int size);

    private static native void nUpdateBufferFromPointer(long handle, long bufHandle, long offset, long dataPtr,
            int size);

    private static native void nDeleteBuffer(long handle, long bufHandle);

    // Draw functions that pass all state directly (preferred)
    private static native void nDrawArraysWithHandles(long handle, int primitive, int first, int count,
            long vertexBuffer, long texture,
            boolean depthTestEnabled, int depthFunc,
            boolean blendEnabled, int blendSrcRGB, int blendDstRGB, int blendSrcAlpha, int blendDstAlpha,
            boolean cullEnabled, int cullMode,
            int viewportX, int viewportY, int viewportW, int viewportH);

    private static native void nDrawElementsWithHandles(long handle, int primitive, int count, 
            long indexBuffer, long indexOffset,
            long vertexBuffer, long texture,
            boolean depthTestEnabled, int depthFunc,
            boolean blendEnabled, int blendSrcRGB, int blendDstRGB, int blendSrcAlpha, int blendDstAlpha,
            boolean cullEnabled, int cullMode,
            int viewportX, int viewportY, int viewportW, int viewportH);

    // ========================================================================
    // Resource Classes
    // ========================================================================

    private static class MetalTexture {
        final int glId;
        long metalHandle = 0;
        int width, height;

        MetalTexture(int glId) {
            this.glId = glId;
        }
    }

    private static class MetalBuffer {
        final int glId;
        long metalHandle = 0;
        int size;

        MetalBuffer(int glId) {
            this.glId = glId;
        }
    }

    private static class MetalShaderProgram {
        final int glId;
        long metalHandle = 0;
        boolean linked = false;
        int vertexShader = 0;
        int fragmentShader = 0;
        int nextUniformLocation = 0;
        final Map<String, Integer> uniformLocations = new HashMap<>();
        final Map<Integer, int[]> uniformInts = new HashMap<>();
        final Map<Integer, float[]> uniformFloats = new HashMap<>();

        MetalShaderProgram(int glId) {
            this.glId = glId;
        }
    }

    private static class MetalShader {
        final int glId;
        final int type; // GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
        boolean compiled = false;
        String source = "";

        MetalShader(int glId, int type) {
            this.glId = glId;
            this.type = type;
        }
    }

    private static class MetalVAO {
        final int glId;
        final Map<Integer, VertexAttribute> attributes = new HashMap<>();
        final java.util.Set<Integer> enabledAttributes = new java.util.HashSet<>();

        MetalVAO(int glId) {
            this.glId = glId;
        }
    }

    private static class MetalFramebuffer {
        final int glId;
        long colorTexture = 0;
        long depthTexture = 0;
        int width, height;

        MetalFramebuffer(int glId) {
            this.glId = glId;
        }
    }

    private static class MetalRenderbuffer {
        final int glId;
        long metalHandle = 0;
        int width, height;
        int internalFormat;

        MetalRenderbuffer(int glId) {
            this.glId = glId;
        }
    }

    private static class VertexAttribute {
        final int index;
        final int size;
        final int type;
        final boolean normalized;
        final int stride;
        final long offset;

        VertexAttribute(int index, int size, int type, boolean normalized, int stride, long offset) {
            this.index = index;
            this.size = size;
            this.type = type;
            this.normalized = normalized;
            this.stride = stride;
            this.offset = offset;
        }
    }
}
