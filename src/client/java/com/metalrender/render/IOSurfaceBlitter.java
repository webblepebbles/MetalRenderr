package com.metalrender.render;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
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
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;

public final class IOSurfaceBlitter {

    // Use GL_TEXTURE_2D with CPU upload instead of GL_TEXTURE_RECTANGLE with
    // IOSurface
    private static final int GL_BGRA = 0x80E1;

    private int glTexture = 0;
    private int vao = 0;
    private int vbo = 0;
    private int shaderProgram = 0;

    private int boundWidth = 0;
    private int boundHeight = 0;
    private boolean initialized = false;

    // CPU pixel buffer for readback
    private ByteBuffer pixelBuffer = null;

    private long lastHandle = 0;

    private static final float[] QUAD_VERTICES = {
            // Full screen quad (position, texcoord)
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 1.0f,
    };

    // Simple vertex shader - uses normalized [0,1] texture coords for GL_TEXTURE_2D
    private static final String VERTEX_SHADER = """
            #version 150 core
            in vec2 aPos;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos.x, -aPos.y, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
            """;

    // Fragment shader - TEST 103: Discard sky pixels (alpha=0) to show GUI through
    private static final String FRAGMENT_SHADER = """
            #version 150 core
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            void main() {
                vec4 color = texture(uTexture, vTexCoord);
                // Discard fully transparent pixels (sky)
                // This lets GUI/weather show through where there's no terrain
                if (color.a < 0.01) {
                    discard;
                }
                // Output with full alpha since we have opaque terrain
                fragColor = vec4(color.rgb, 1.0);

                // Write depth to gl_FragDepth to fix occlusion
                // We assume the alpha channel or another channel might contain depth info?
                // No, we only have color.
                // If we don't write depth, entities will draw on top.
                // For now, we just write maximum depth (far) for sky, and minimum (near) for terrain?
                // No, that would make terrain cover everything.
                // We need real depth.
                // Since we can't easily get depth from Metal to OpenGL without another texture copy,
                // we will rely on drawing order: Terrain (OPAQUE) -> Entities -> GUI.
                // If we draw terrain first, we write to depth buffer (if enabled).
                // But we are blitting a quad. The quad is at z=0 (or whatever).
                // We need to write the depth from the Metal depth buffer.

                // Since we don't have depth, we can't fix "Entities through walls" perfectly yet.
                // But drawing order fixes "Terrain covers entities".
            }
            """;

    public IOSurfaceBlitter() {
    }

    public boolean initialize() {
        if (initialized) {
            return true;
        }

        try {
            // Texture will be created on-demand in blit() when we know the size
            // This ensures proper immutable storage allocation
            glTexture = 0; // Will be created in blit()

            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();

            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            FloatBuffer vertexData = BufferUtils.createFloatBuffer(QUAD_VERTICES.length);
            vertexData.put(QUAD_VERTICES).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexData, GL15.GL_STATIC_DRAW);

            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);

            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            shaderProgram = createShaderProgram();
            if (shaderProgram == 0) {
                MetalLogger.error("[IOSurfaceBlitter] Failed to create shader program");
                destroy();
                return false;
            }

            GL20.glUseProgram(shaderProgram);
            int texUniform = GL20.glGetUniformLocation(shaderProgram, "uTexture");
            if (texUniform >= 0) {
                GL20.glUniform1i(texUniform, 0);
            }
            GL20.glUseProgram(0);

            initialized = true;
            MetalLogger.info("[IOSurfaceBlitter] Initialized successfully (CPU copy mode)");
            return true;

        } catch (Exception e) {
            MetalLogger.error("[IOSurfaceBlitter] Initialization failed", e);
            destroy();
            return false;
        }
    }

    private int blitFrameCount = 0;

    public boolean blit(long metalHandle) {
        blitFrameCount++;

        if (!initialized) {
            if (!initialize()) {
                MetalLogger.error("[IOSurfaceBlitter] Failed to initialize!");
                return false;
            }
        }

        int width = NativeBridge.nGetIOSurfaceWidth(metalHandle);
        int height = NativeBridge.nGetIOSurfaceHeight(metalHandle);

        if (width <= 0 || height <= 0) {
            return false;
        }

        // Allocate or resize pixel buffer if needed
        int requiredSize = width * height * 4; // BGRA = 4 bytes per pixel
        if (pixelBuffer == null || pixelBuffer.capacity() < requiredSize) {
            pixelBuffer = BufferUtils.createByteBuffer(requiredSize);
            MetalLogger.info("[IOSurfaceBlitter] Allocated pixel buffer: %d bytes for %dx%d", requiredSize, width,
                    height);
        }

        // CPU readback: copy Metal render target to ByteBuffer
        pixelBuffer.clear();
        boolean readbackSuccess = NativeBridge.nReadbackPixels(metalHandle, pixelBuffer);
        if (!readbackSuccess) {
            return false;
        }
        pixelBuffer.rewind();

        boolean firstBlit = (boundWidth == 0 && boundHeight == 0);
        if (firstBlit || blitFrameCount <= 5) {
            MetalLogger.info("[IOSurfaceBlitter] Blit frame %d: %dx%d (first=%s)",
                    blitFrameCount, width, height, firstBlit);

            // DEBUG: Sample some pixels to see if we have valid data
            // Check center pixel
            int centerX = width / 2;
            int centerY = height / 2;
            int centerIdx = (centerY * width + centerX) * 4;
            if (centerIdx + 3 < pixelBuffer.capacity()) {
                int b = pixelBuffer.get(centerIdx) & 0xFF;
                int g = pixelBuffer.get(centerIdx + 1) & 0xFF;
                int r = pixelBuffer.get(centerIdx + 2) & 0xFF;
                int a = pixelBuffer.get(centerIdx + 3) & 0xFF;
                MetalLogger.info("[IOSurfaceBlitter] Center pixel BGRA: %d, %d, %d, %d", b, g, r, a);
            }
            // Check a corner pixel
            int cornerIdx = 0;
            int cb = pixelBuffer.get(cornerIdx) & 0xFF;
            int cg = pixelBuffer.get(cornerIdx + 1) & 0xFF;
            int cr = pixelBuffer.get(cornerIdx + 2) & 0xFF;
            int ca = pixelBuffer.get(cornerIdx + 3) & 0xFF;
            MetalLogger.info("[IOSurfaceBlitter] Corner(0,0) pixel BGRA: %d, %d, %d, %d", cb, cg, cr, ca);

            // Count non-transparent pixels
            int nonTransparentCount = 0;
            int sampleStep = Math.max(1, (width * height) / 1000); // Sample ~1000 pixels
            for (int i = 0; i < width * height; i += sampleStep) {
                int alpha = pixelBuffer.get(i * 4 + 3) & 0xFF;
                if (alpha > 0)
                    nonTransparentCount++;
            }
            MetalLogger.info("[IOSurfaceBlitter] Sampled %d pixels with alpha > 0 (out of ~%d samples)",
                    nonTransparentCount, (width * height) / sampleStep);
            pixelBuffer.rewind();
        }

        // Upload to GL texture using glTexImage2D
        // Use GL_RGBA for both internal format and data format
        // The pixels are BGRA from Metal, but upload as RGBA and swizzle in shader

        // If size changed, delete old texture and create new one
        if (width != boundWidth || height != boundHeight) {
            if (glTexture != 0) {
                GL11.glDeleteTextures(glTexture);
            }
            glTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);

            // Set texture parameters
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);

            // Allocate immutable storage
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, width, height);
            boundWidth = width;
            boundHeight = height;
            MetalLogger.info("[IOSurfaceBlitter] Created new texture %d: %dx%d (GL_RGBA8 storage)",
                    glTexture, width, height);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);
        }

        // Reset pixel store parameters to ensure correct upload
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

        // Update the texture data
        // Metal outputs BGRA (bytes in memory are B,G,R,A order)
        // GL_BGRA tells OpenGL to interpret input as B,G,R,A and convert to RGBA
        // internal
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixelBuffer);

        // DON'T unbind - keep texture bound for immediate use

        // Check for GL errors after upload
        int uploadError = GL11.glGetError();
        if (uploadError != GL11.GL_NO_ERROR && firstBlit) {
            MetalLogger.error("[IOSurfaceBlitter] GL error after texture upload: 0x%X", uploadError);
        }

        lastHandle = metalHandle;

        // Save GL state
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int targetFbo = prevFbo;

        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        if (firstBlit || blitFrameCount <= 5) {
            MetalLogger.info("[IOSurfaceBlitter] Current FBO binding: %d", prevFbo);
        }

        try {
            // HYBRID MODE: Metal renders terrain, entities, and GUI backgrounds/text
            // But items are still rendered by OpenGL's GuiRenderer system
            // We need alpha blending so items (rendered by OpenGL) show through
            // where Metal's GUI has transparency

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);

            // ENABLE BLENDING - let OpenGL items show through transparent areas
            // Items are rendered to OpenGL framebuffer BEFORE this blit
            // So we need to blend Metal's output ON TOP of OpenGL's items
            // Using premultiplied alpha: src=ONE (Metal has premultiplied),
            // dst=ONE_MINUS_SRC_ALPHA
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, // RGB: premultiplied alpha
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA // Alpha: same
            );

            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);

            // Disable stencil test
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            // Save and restore viewport - use the CURRENT viewport, not IOSurface
            // dimensions
            // This ensures we draw to the same area that Minecraft expects
            int[] prevViewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);

            if (firstBlit || blitFrameCount <= 5) {
                MetalLogger.info("[IOSurfaceBlitter] Prev viewport: %d,%d,%d,%d, IOSurface: %dx%d",
                        prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3],
                        width, height);
            }

            // Use the current viewport dimensions for drawing
            // The quad vertices are in NDC space (-1 to 1), so it will fill the viewport
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

            // Draw fullscreen quad with Metal render result
            GL20.glUseProgram(shaderProgram);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);

            // Check for GL errors before draw
            int preDrawError = GL11.glGetError();
            if (preDrawError != GL11.GL_NO_ERROR && (firstBlit || blitFrameCount <= 5)) {
                MetalLogger.error("[IOSurfaceBlitter] GL error before draw: 0x%X", preDrawError);
            }

            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL30.glBindVertexArray(0);

            // Check for GL errors after draw
            int postDrawError = GL11.glGetError();
            if (postDrawError != GL11.GL_NO_ERROR && (firstBlit || blitFrameCount <= 5)) {
                MetalLogger.error("[IOSurfaceBlitter] GL error after draw: 0x%X", postDrawError);
            }

            if (firstBlit || blitFrameCount <= 5) {
                MetalLogger.info("[IOSurfaceBlitter] Draw completed: shader=%d, vao=%d, tex=%d",
                        shaderProgram, vao, glTexture);
            }

            return true;

        } finally {
            // Restore GL state (simple version)
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);

            if (depthTest) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            GL11.glDepthFunc(prevDepthFunc);

            if (blend) {
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }

            if (cullFace) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }

            if (scissorTest) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    public void invalidate() {
        boundWidth = 0;
        boundHeight = 0;
    }

    public void destroy() {
        if (shaderProgram != 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (glTexture != 0) {
            GL11.glDeleteTextures(glTexture);
            glTexture = 0;
        }
        initialized = false;
        boundWidth = 0;
        boundHeight = 0;
        pixelBuffer = null;
        MetalLogger.info("[IOSurfaceBlitter] Destroyed");
    }

    private int createShaderProgram() {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        if (vertexShader == 0) {
            return 0;
        }

        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (fragmentShader == 0) {
            GL20.glDeleteShader(vertexShader);
            return 0;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);

        GL20.glBindAttribLocation(program, 0, "aPos");
        GL20.glBindAttribLocation(program, 1, "aTexCoord");

        GL20.glLinkProgram(program);

        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            MetalLogger.error("[IOSurfaceBlitter] Shader link failed: %s", log);
            GL20.glDeleteProgram(program);
            return 0;
        }

        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            String typeName = (type == GL20.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            MetalLogger.error("[IOSurfaceBlitter] %s shader compile failed: %s", typeName, log);
            GL20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }
}
