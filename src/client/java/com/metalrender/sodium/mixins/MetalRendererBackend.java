package com.metalrender.sodium;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.joml.Matrix4f;
import com.metalrender.nativebridge.MetalBackend;
import com.metalrender.nativebridge.NativeMemory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

import java.nio.ByteBuffer;

@Environment(EnvType.CLIENT)
public final class MetalRendererBackend {
    static {
        try {
            System.loadLibrary("metalrender");
        } catch (Throwable ignored) {}
    }

    private final MinecraftClient client;
    private long handle = 0L;
    private boolean initialized = false;
    private long startNanos = 0L;

    public MetalRendererBackend(MinecraftClient client) {
        this.client = client;
    }

    public boolean initIfNeeded() {
        if (initialized) return true;
        try {
            Window w = client.getWindow();
            long ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(ctx);
            boolean srgb = true;
            handle = MetalBackend.init(nsWindow, srgb);
            if (handle == 0L) {
                return false;
            }
            startNanos = System.nanoTime();
            uploadDemoMesh();
            initialized = true;
            return true;
        } catch (Throwable t) {
            initialized = false;
            return false;
        }
    }

    private void uploadDemoMesh() {
        float s = 1.0f;
        float[] tris = new float[] {
                -s, -s, 0f, 1f, 0f, 0f, 1f,
                s, -s, 0f, 0f, 1f, 0f, 1f,
                0f,  s, 0f, 0f, 0f, 1f, 1f
        };
        int stride = 7 * 4;
        ByteBuffer buf = NativeMemory.alloc(tris.length * 4);
        for (float v : tris) buf.putFloat(v);
        buf.rewind();
        MetalBackend.uploadStaticMesh(handle, buf, tris.length / 7, stride);
    }

    public void resizeIfNeeded() {
        if (!initialized) return;
        Window w = client.getWindow();
        MetalBackend.resize(handle, w.getFramebufferWidth(), w.getFramebufferHeight());
    }

    public void sendCamera(float fovDegrees) {
        if (!initialized) return;
        Window w = client.getWindow();
        float width = w.getFramebufferWidth();
        float height = w.getFramebufferHeight();
        float aspect = height == 0 ? 1f : width / height;
        float f = (float)(1.0 / Math.tan(Math.toRadians(fovDegrees) * 0.5));
        float zNear = 0.05f;
        float zFar = 1000f;
        float[] proj = new float[] {
                f/aspect,0,0,0,
                0,f,0,0,
                0,0,(zFar+zNear)/(zNear-zFar),-1,
                0,0,(2*zFar*zNear)/(zNear-zFar),0
        };
        float[] view = new float[] {
                1,0,0,0,
                0,1,0,0,
                0,0,1,0,
                0,0,-3,1
        };
        float[] m = new float[16];
        for (int r=0;r<4;r++) for (int c=0;c<4;c++) {
            m[r*4+c]=view[r*4+0]*proj[0*4+c]+view[r*4+1]*proj[1*4+c]+view[r*4+2]*proj[2*4+c]+view[r*4+3]*proj[3*4+c];
        }
        MetalBackend.setCamera(handle, m);
    }

    public void onSetupTerrain(float fovDegrees) {
        if (!initIfNeeded()) return;
        resizeIfNeeded();
        sendCamera(fovDegrees);
        float t = (System.nanoTime() - startNanos) / 1_000_000_000f;
        MetalBackend.render(handle, t);
    }

    public boolean drawChunkLayerSodiumOverride(int layerId) {
        if (!initialized) return false;
        MetalBackend.render(handle, (System.nanoTime() - startNanos) / 1_000_000_000f);
        return true;
    }

    public void destroy() {
        if (!initialized) return;
        MetalBackend.destroy(handle);
        handle = 0L;
        initialized = false;
    }
}