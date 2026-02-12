package com.metalrender.entity;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.sodium.mixins.accessor.AbstractTextureAccessor;
import com.metalrender.sodium.mixins.accessor.GlTextureAccessor;
import com.metalrender.util.MetalLogger;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityTextureCache {

    private static final EntityTextureCache INSTANCE = new EntityTextureCache();

    
    private final Map<String, Long> textureHandles = new ConcurrentHashMap<>();

    
    private final Map<String, TextureInfo> textureInfo = new ConcurrentHashMap<>();

    
    private long deviceHandle = 0;

    private int texturesLoaded = 0;

    
    private long whiteTextureHandle = 0;

    private EntityTextureCache() {
    }

    public static EntityTextureCache getInstance() {
        return INSTANCE;
    }

    
    public void initialize(long device) {
        this.deviceHandle = device;
        MetalLogger.info("[EntityTextureCache] Initialized with device handle: {}", device);
        createWhiteTexture();
    }

    
    public long getWhiteTextureHandle() {
        if (whiteTextureHandle == 0 && deviceHandle != 0) {
            createWhiteTexture();
        }
        return whiteTextureHandle;
    }

    
    private void createWhiteTexture() {
        if (deviceHandle == 0 || whiteTextureHandle != 0) {
            return;
        }

        try {
            ByteBuffer whitePixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            whitePixel.put((byte) 255); 
            whitePixel.put((byte) 255); 
            whitePixel.put((byte) 255); 
            whitePixel.put((byte) 255); 
            whitePixel.flip();

            whiteTextureHandle = NativeBridge.nUploadEntityTexture(deviceHandle, whitePixel, 1, 1);
            MetalLogger.info("[EntityTextureCache] Created white fallback texture: handle={}", whiteTextureHandle);
        } catch (Exception e) {
            System.err.println("[EntityTextureCache] Failed to create white texture: " + e.getMessage());
        }
    }

    
    public long getOrCreateTexture(String textureIdStr) {
        if (deviceHandle == 0 || textureIdStr == null) {
            return 0;
        }
        Long cached = textureHandles.get(textureIdStr);
        if (cached != null) {
            return cached;
        }
        Identifier textureId = Identifier.tryParse(textureIdStr);
        if (textureId == null) {
            textureHandles.put(textureIdStr, 0L);
            return 0;
        }
        return loadAndUploadTexture(textureId, textureIdStr);
    }

    
    public TextureInfo getTextureInfo(String textureIdStr) {
        return textureInfo.get(textureIdStr);
    }

    
    private synchronized long loadAndUploadTexture(Identifier textureId, String key) {
        Long cached = textureHandles.get(key);
        if (cached != null) {
            return cached;
        }

        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            TextureManager textureManager = mc.getTextureManager();
            AbstractTexture texture = textureManager.getTexture(textureId);
            if (texture == null) {
                System.out.println("[EntityTextureCache] No texture found for: " + key);
                textureHandles.put(key, 0L);
                return 0;
            }

            System.out.println(
                    "[EntityTextureCache] Loading texture: " + key + " type=" + texture.getClass().getSimpleName());
            int glTextureId = 0;
            if (texture instanceof AbstractTextureAccessor texAccessor) {
                GpuTexture gpuTex = texAccessor.metalrender$getGlTexture();
                if (gpuTex instanceof GlTextureAccessor glTexAccessor) {
                    glTextureId = glTexAccessor.metalrender$getGlId();
                }
            }

            if (glTextureId == 0) {
                System.out.println("[EntityTextureCache] GL texture ID is 0 for: " + key);
                textureHandles.put(key, 0L);
                return 0;
            }

            System.out.println("[EntityTextureCache] Got GL texture ID: " + glTextureId + " for: " + key);
            ByteBuffer pixelData = readGLTextureById(glTextureId, key);
            if (pixelData == null) {
                System.out.println("[EntityTextureCache] Failed to read GL texture data for: " + key);
                textureHandles.put(key, 0L);
                return 0;
            }

            TextureInfo info = textureInfo.get(key);
            if (info == null) {
                System.out.println("[EntityTextureCache] No texture info for: " + key);
                textureHandles.put(key, 0L);
                return 0;
            }
            long handle = NativeBridge.nUploadEntityTexture(deviceHandle, pixelData, info.width, info.height);

            System.out.println("[EntityTextureCache] Uploaded texture to Metal: " + key + " handle=" + handle + " size="
                    + info.width + "x" + info.height);

            texturesLoaded++;

            textureHandles.put(key, handle);
            return handle;

        } catch (Exception e) {
            textureHandles.put(key, 0L);
            return 0;
        }
    }

    
    private ByteBuffer readGLTextureById(int glTextureId, String key) {
        try {
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

            if (width <= 0 || height <= 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                return null;
            }
            textureInfo.put(key, new TextureInfo(width, height));
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);

            buffer.rewind();
            return buffer;

        } catch (Exception e) {
            return null;
        }
    }

    
    public void clear() {
        textureHandles.clear();
        textureInfo.clear();
        texturesLoaded = 0;
        MetalLogger.info("[EntityTextureCache] Cleared all cached textures");
    }

    
    public int getCachedTextureCount() {
        return textureHandles.size();
    }

    
    public static class TextureInfo {
        public final int width;
        public final int height;

        public TextureInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
