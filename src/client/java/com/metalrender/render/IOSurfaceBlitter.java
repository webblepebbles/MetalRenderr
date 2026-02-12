package com.metalrender.render;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;

public final class IOSurfaceBlitter {

    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    private static final int GL_BGRA = 0x80E1;
    private int glTextureRect = 0; 
    private int ioSurfaceFbo = 0; 
    private int intermediateFbo = 0; 
    private int intermediateTexture = 0; 
    private int vao = 0;
    private int vbo = 0;
    private int shaderProgram = 0;
    private int glTexture = 0;
    private ByteBuffer pixelBuffer = null;
    private int boundWidth = 0;
    private int boundHeight = 0;
    private boolean initialized = false;
    private int blitFrameCount = 0;
    private boolean ioSurfaceFailed = false;
    private int consecutiveFastPathFailures = 0;
    private static final int MAX_FAST_PATH_FAILURES = 3;
    private int lastIOSurfaceWidth = 0;
    private int lastIOSurfaceHeight = 0;

    private static final String VERTEX_SHADER = """
            #version 150 core
            in vec2 aPos;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos.x, aPos.y, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 150 core
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            void main() {
                vec4 color = texture(uTexture, vTexCoord);
                if (color.a < 0.01) discard;
                fragColor = vec4(color.rgb, 1.0);
            }
            """;

    private static final float[] QUAD_VERTICES = {
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 1.0f,
    };

    public IOSurfaceBlitter() {
    }

    
    public boolean blit(long metalHandle) {
        return blit(metalHandle, false);
    }

    
    public boolean blit(long metalHandle, boolean skipWait) {
        blitFrameCount++;

        if (metalHandle == 0) {
            return false;
        }

        if (!initialized && !initialize()) {
            return false;
        }

        int width = NativeBridge.nGetIOSurfaceWidth(metalHandle);
        int height = NativeBridge.nGetIOSurfaceHeight(metalHandle);

        if (width <= 0 || height <= 0) {
            return false;
        }
        if (lastIOSurfaceWidth > 0 && lastIOSurfaceHeight > 0 &&
                (width != lastIOSurfaceWidth || height != lastIOSurfaceHeight)) {
            if (blitFrameCount <= 20 || blitFrameCount % 300 == 0) {
                MetalLogger.info("[IOSurfaceBlitter] IOSurface resized: %dx%d -> %dx%d",
                        lastIOSurfaceWidth, lastIOSurfaceHeight, width, height);
            }
            invalidateTextures();
        }
        lastIOSurfaceWidth = width;
        lastIOSurfaceHeight = height;
        return blitGPUComposite(metalHandle, width, height, skipWait);
    }

    public void destroy() {
        deleteShaderProgram();
        deleteQuadGeometry();
        deleteTextures();
        if (ioSurfaceFbo != 0) {
            GL30.glDeleteFramebuffers(ioSurfaceFbo);
            ioSurfaceFbo = 0;
        }
        if (intermediateFbo != 0) {
            GL30.glDeleteFramebuffers(intermediateFbo);
            intermediateFbo = 0;
        }
        if (intermediateTexture != 0) {
            GL11.glDeleteTextures(intermediateTexture);
            intermediateTexture = 0;
        }
        initialized = false;
        boundWidth = 0;
        boundHeight = 0;
        pixelBuffer = null;
        resetFastPathState();
        MetalLogger.info("[IOSurfaceBlitter] Destroyed");
    }

    private boolean initialize() {
        if (initialized)
            return true;
        try {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();

            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            FloatBuffer buf = BufferUtils.createFloatBuffer(QUAD_VERTICES.length);
            buf.put(QUAD_VERTICES).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            initialized = true;
            MetalLogger.info("[IOSurfaceBlitter] Initialized");
            return true;
        } catch (Exception e) {
            MetalLogger.error("[IOSurfaceBlitter] Init failed: %s", e.getMessage());
            destroy();
            return false;
        }
    }

    
    private boolean blitGPUComposite(long metalHandle, int width, int height, boolean skipWait) {
        try {
            if (!skipWait) {
                NativeBridge.nWaitForRender(metalHandle);
            }
            if (glTextureRect == 0) {
                glTextureRect = GL11.glGenTextures();
            }

            GL11.glFlush();
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                 }

            if (!NativeBridge.nBindIOSurfaceToTexture(metalHandle, glTextureRect)) {
                consecutiveFastPathFailures++;
                if (blitFrameCount <= 10) {
                    MetalLogger.warn("[IOSurfaceBlitter] nBindIOSurfaceToTexture failed");
                }
                if (consecutiveFastPathFailures >= MAX_FAST_PATH_FAILURES) {
                    return blitSlowPath(metalHandle, width, height);
                }
                return false;
            }
            consecutiveFastPathFailures = 0;

            GL11.glFlush();
            GL11.glFinish();
            GL11.glBindTexture(GL_TEXTURE_RECTANGLE, glTextureRect);
            if (!blitToIntermediateTexture(width, height)) {
                while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                     }
                return false;
            }
            return drawFullscreenQuad(width, height);

        } catch (Exception e) {
            if (blitFrameCount <= 10) {
                MetalLogger.error("[IOSurfaceBlitter] GPU composite exception: %s", e.getMessage());
            }
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                 }
            return false;
        }
    }

    
    private boolean blitToIntermediateTexture(int width, int height) {
        com.metalrender.util.VanillaRenderState.setIOSurfaceBlitting(true);
        try {
            return blitToIntermediateImpl(width, height);
        } finally {
            com.metalrender.util.VanillaRenderState.setIOSurfaceBlitting(false);
        }
    }

    private boolean blitToIntermediateImpl(int width, int height) {
        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        float[] prevClearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, prevClearColor);

        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
             }

        try {
            if (ioSurfaceFbo == 0) {
                ioSurfaceFbo = GL30.glGenFramebuffers();
            }
            ensureIntermediateTexture(width, height);

            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ioSurfaceFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_RECTANGLE, glTextureRect, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                MetalLogger.error("[IOSurfaceBlitter] Read FBO incomplete: 0x%X", status);
                return false;
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, intermediateFbo);

            status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                MetalLogger.error("[IOSurfaceBlitter] Intermediate FBO incomplete: 0x%X", status);
                return false;
            }
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL30.glBlitFramebuffer(
                    0, height, width, 0, 
                    0, 0, width, height, 
                    GL11.GL_COLOR_BUFFER_BIT,
                    GL11.GL_NEAREST); 

            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR && blitFrameCount <= 10) {
                MetalLogger.error("[IOSurfaceBlitter] glBlitFramebuffer error: 0x%X", err);
            }

            return true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ioSurfaceFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_RECTANGLE, 0, 0);

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            if (scissor)
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glClearColor(prevClearColor[0], prevClearColor[1], prevClearColor[2], prevClearColor[3]);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                 }
        }
    }

    
    private void ensureIntermediateTexture(int width, int height) {
        if (intermediateTexture != 0 && boundWidth == width && boundHeight == height) {
            return; 
        }
        if (intermediateTexture != 0) {
            GL11.glDeleteTextures(intermediateTexture);
        }
        if (intermediateFbo == 0) {
            intermediateFbo = GL30.glGenFramebuffers();
        }
        intermediateTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, intermediateTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, width, height);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, intermediateFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, intermediateTexture, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MetalLogger.error("[IOSurfaceBlitter] Intermediate FBO setup incomplete: 0x%X", status);
        }
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        boundWidth = width;
        boundHeight = height;

        MetalLogger.info("[IOSurfaceBlitter] Created intermediate texture: %dx%d", width, height);
    }

    private boolean blitSlowPath(long metalHandle, int width, int height) {
        int requiredSize = width * height * 4;
        if (pixelBuffer == null || pixelBuffer.capacity() < requiredSize) {
            pixelBuffer = BufferUtils.createByteBuffer(requiredSize);
        }

        pixelBuffer.clear();
        if (!NativeBridge.nReadbackPixels(metalHandle, pixelBuffer)) {
            return false;
        }
        pixelBuffer.rewind();

        uploadToTexture(width, height);
        return drawFullscreenQuad(width, height);
    }

    private void uploadToTexture(int width, int height) {
        if (width != boundWidth || height != boundHeight || glTexture == 0) {
            if (glTexture != 0)
                GL11.glDeleteTextures(glTexture);
            glTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, width, height);
            boundWidth = width;
            boundHeight = height;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);
        }

        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixelBuffer);
    }

    private boolean drawFullscreenQuad(int width, int height) {
        if (shaderProgram == 0) {
            shaderProgram = createShaderProgram();
            if (shaderProgram == 0)
                return false;
            GL20.glUseProgram(shaderProgram);
            int loc = GL20.glGetUniformLocation(shaderProgram, "uTexture");
            if (loc >= 0)
                GL20.glUniform1i(loc, 0);
            GL20.glUseProgram(0);
        }
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthMsk = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int bSrcRGB = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int bDstRGB = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int bSrcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int bDstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
             }

        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            GL20.glUseProgram(shaderProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, intermediateTexture);

            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL30.glBindVertexArray(0);

            return true;
        } finally {
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL13.glActiveTexture(prevActiveTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL11.glDepthMask(depthMsk);
            if (depth)
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            else
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (blend)
                GL11.glEnable(GL11.GL_BLEND);
            else
                GL11.glDisable(GL11.GL_BLEND);
            if (cull)
                GL11.glEnable(GL11.GL_CULL_FACE);
            else
                GL11.glDisable(GL11.GL_CULL_FACE);
            if (scissor)
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL14.glBlendFuncSeparate(bSrcRGB, bDstRGB, bSrcA, bDstA);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                 }
        }
    }

    private int createShaderProgram() {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        if (vs == 0)
            return 0;
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (fs == 0) {
            GL20.glDeleteShader(vs);
            return 0;
        }

        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glBindAttribLocation(prog, 0, "aPos");
        GL20.glBindAttribLocation(prog, 1, "aTexCoord");
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            MetalLogger.error("[IOSurfaceBlitter] Shader link failed: %s", GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private int compileShader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            MetalLogger.error("[IOSurfaceBlitter] %s shader compile failed: %s",
                    type == GL20.GL_VERTEX_SHADER ? "vertex" : "fragment",
                    GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private void invalidateTextures() {
        if (glTextureRect != 0) {
            GL11.glDeleteTextures(glTextureRect);
            glTextureRect = 0;
        }
        if (glTexture != 0) {
            GL11.glDeleteTextures(glTexture);
            glTexture = 0;
        }
        if (intermediateTexture != 0) {
            GL11.glDeleteTextures(intermediateTexture);
            intermediateTexture = 0;
        }
        boundWidth = 0;
        boundHeight = 0;
        resetFastPathState();
    }

    private void resetFastPathState() {
        ioSurfaceFailed = false;
        consecutiveFastPathFailures = 0;
        lastIOSurfaceWidth = 0;
        lastIOSurfaceHeight = 0;
    }

    private void deleteShaderProgram() {
        if (shaderProgram != 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
    }

    private void deleteQuadGeometry() {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
    }

    private void deleteTextures() {
        if (glTexture != 0) {
            GL11.glDeleteTextures(glTexture);
            glTexture = 0;
        }
        if (glTextureRect != 0) {
            GL11.glDeleteTextures(glTextureRect);
            glTextureRect = 0;
        }
        if (intermediateTexture != 0) {
            GL11.glDeleteTextures(intermediateTexture);
            intermediateTexture = 0;
        }
    }

    
    public void invalidate() {
        boundWidth = 0;
        boundHeight = 0;
    }
}
