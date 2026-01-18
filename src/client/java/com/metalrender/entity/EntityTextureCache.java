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

/**
 * Caches entity textures as Metal textures for entity rendering.
 * 
 * Unlike the terrain atlas (which is a single large texture), entities use
 * individual textures per entity type. We need to:
 * 1. Read the GL texture data when first encountered
 * 2. Upload to Metal as an MTLTexture
 * 3. Cache by Identifier for reuse
 */
public class EntityTextureCache {

    private static final EntityTextureCache INSTANCE = new EntityTextureCache();

    /** Map from texture identifier string to native Metal texture handle */
    private final Map<String, Long> textureHandles = new ConcurrentHashMap<>();

    /** Map from texture identifier to dimensions */
    private final Map<String, TextureInfo> textureInfo = new ConcurrentHashMap<>();

    /** Native device handle */
    private long deviceHandle = 0;

    private int texturesLoaded = 0;

    /** Cached white 1x1 texture handle for solid color rendering */
    private long whiteTextureHandle = 0;

    private EntityTextureCache() {
    }

    public static EntityTextureCache getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize with the Metal device handle.
     */
    public void initialize(long device) {
        this.deviceHandle = device;
        MetalLogger.info("[EntityTextureCache] Initialized with device handle: {}", device);

        // Pre-create the white fallback texture
        createWhiteTexture();
    }

    /**
     * Get the white 1x1 texture handle for solid color rendering.
     * This is used when no texture is bound (e.g., for solid fills).
     */
    public long getWhiteTextureHandle() {
        if (whiteTextureHandle == 0 && deviceHandle != 0) {
            createWhiteTexture();
        }
        return whiteTextureHandle;
    }

    /**
     * Create a 1x1 white texture for solid color rendering.
     */
    private void createWhiteTexture() {
        if (deviceHandle == 0 || whiteTextureHandle != 0) {
            return;
        }

        try {
            // Create 1x1 white RGBA pixel data (BGRA format for Metal)
            ByteBuffer whitePixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            whitePixel.put((byte) 255); // B
            whitePixel.put((byte) 255); // G
            whitePixel.put((byte) 255); // R
            whitePixel.put((byte) 255); // A
            whitePixel.flip();

            whiteTextureHandle = NativeBridge.nUploadEntityTexture(deviceHandle, whitePixel, 1, 1);
            MetalLogger.info("[EntityTextureCache] Created white fallback texture: handle={}", whiteTextureHandle);
        } catch (Exception e) {
            System.err.println("[EntityTextureCache] Failed to create white texture: " + e.getMessage());
        }
    }

    /**
     * Get or create a Metal texture handle for the given texture identifier string.
     * Returns 0 if the texture couldn't be loaded.
     */
    public long getOrCreateTexture(String textureIdStr) {
        if (deviceHandle == 0 || textureIdStr == null) {
            return 0;
        }

        // Check cache first
        Long cached = textureHandles.get(textureIdStr);
        if (cached != null) {
            return cached;
        }

        // Try to parse as Identifier
        Identifier textureId = Identifier.tryParse(textureIdStr);
        if (textureId == null) {
            textureHandles.put(textureIdStr, 0L);
            return 0;
        }

        // Need to load from GL and upload to Metal
        return loadAndUploadTexture(textureId, textureIdStr);
    }

    /**
     * Get texture info (dimensions) for a texture.
     */
    public TextureInfo getTextureInfo(String textureIdStr) {
        return textureInfo.get(textureIdStr);
    }

    /**
     * Load a texture from GL and upload to Metal.
     */
    private synchronized long loadAndUploadTexture(Identifier textureId, String key) {
        // Double-check after acquiring lock
        Long cached = textureHandles.get(key);
        if (cached != null) {
            return cached;
        }

        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            TextureManager textureManager = mc.getTextureManager();

            // Get the GL texture - this also ensures it's loaded
            AbstractTexture texture = textureManager.getTexture(textureId);
            if (texture == null) {
                System.out.println("[EntityTextureCache] No texture found for: " + key);
                textureHandles.put(key, 0L);
                return 0;
            }

            System.out.println(
                    "[EntityTextureCache] Loading texture: " + key + " type=" + texture.getClass().getSimpleName());

            // Get GL texture ID through accessor chain
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

            // Read the texture data from GL
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

            // Upload to Metal
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

    /**
     * Read texture data from a GL texture by ID.
     */
    private ByteBuffer readGLTextureById(int glTextureId, String key) {
        try {
            // Save current binding
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            // Bind the texture
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);

            // Get dimensions
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

            if (width <= 0 || height <= 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                return null;
            }

            // Store texture info
            textureInfo.put(key, new TextureInfo(width, height));

            // Allocate buffer for BGRA data
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

            // Read texture data in BGRA format for Metal
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);

            // Restore previous binding
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);

            buffer.rewind();
            return buffer;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clear all cached textures (e.g., on resource reload).
     */
    public void clear() {
        textureHandles.clear();
        textureInfo.clear();
        texturesLoaded = 0;
        MetalLogger.info("[EntityTextureCache] Cleared all cached textures");
    }

    /**
     * Get cache statistics.
     */
    public int getCachedTextureCount() {
        return textureHandles.size();
    }

    /**
     * Texture dimension info.
     */
    public static class TextureInfo {
        public final int width;
        public final int height;

        public TextureInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
