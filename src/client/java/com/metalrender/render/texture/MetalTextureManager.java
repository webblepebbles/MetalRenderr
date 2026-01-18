package com.metalrender.render.texture;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified texture manager for Metal rendering.
 * 
 * Manages texture loading and caching for all render types:
 * - Entity textures
 * - Item textures
 * - GUI textures
 * - Particle textures
 * - Block textures (handled via atlas)
 */
public class MetalTextureManager {

    private static final MetalTextureManager INSTANCE = new MetalTextureManager();

    /** Texture cache: Identifier -> Metal texture handle */
    private final Map<String, Long> textureCache = new ConcurrentHashMap<>();

    /** Texture dimensions cache */
    private final Map<String, TextureInfo> textureInfo = new ConcurrentHashMap<>();

    /** Native device handle */
    private long deviceHandle = 0;

    /** Whether texture manager is initialized */
    private volatile boolean initialized = false;

    /** Known texture atlases */
    private static final Identifier BLOCKS_ATLAS = Identifier.of("minecraft", "textures/atlas/blocks.png");
    private static final Identifier GUI_ATLAS = Identifier.of("minecraft", "textures/atlas/gui.png");
    private static final Identifier PARTICLES_ATLAS = Identifier.of("minecraft", "textures/atlas/particles.png");

    /** Atlas handles */
    private long blocksAtlasHandle = 0;
    private long guiAtlasHandle = 0;
    private long particlesAtlasHandle = 0;

    private MetalTextureManager() {
    }

    public static MetalTextureManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize with Metal device handle.
     */
    public void initialize(long device) {
        this.deviceHandle = device;
        this.initialized = true;
        MetalLogger.info("[MetalTextureManager] Initialized with device: {}", device);
    }

    public boolean isInitialized() {
        return initialized && deviceHandle != 0;
    }

    /**
     * Get or create a Metal texture for the given identifier.
     * Returns 0 if texture cannot be loaded.
     */
    public long getOrCreateTexture(Identifier textureId) {
        if (!initialized || deviceHandle == 0 || textureId == null) {
            return 0;
        }

        String key = textureId.toString();

        // Check cache first
        Long cached = textureCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Try to load and upload
        return loadAndUploadTexture(textureId, key);
    }

    /**
     * Get or create texture from string identifier.
     */
    public long getOrCreateTexture(String textureId) {
        if (textureId == null || textureId.isEmpty()) {
            return 0;
        }

        Identifier id = Identifier.tryParse(textureId);
        if (id == null) {
            return 0;
        }

        return getOrCreateTexture(id);
    }

    /**
     * Get texture info (dimensions).
     */
    public TextureInfo getTextureInfo(String textureId) {
        return textureInfo.get(textureId);
    }

    /**
     * Load texture from GL and upload to Metal.
     */
    private synchronized long loadAndUploadTexture(Identifier textureId, String key) {
        // Double-check cache
        Long cached = textureCache.get(key);
        if (cached != null) {
            return cached;
        }

        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null)
                return 0;

            TextureManager textureManager = mc.getTextureManager();
            if (textureManager == null)
                return 0;

            // Get the GL texture
            AbstractTexture texture = textureManager.getTexture(textureId);
            if (texture == null) {
                MetalLogger.warn("[MetalTextureManager] Texture not found: {}", textureId);
                return 0;
            }

            // Read texture from GL
            TextureData data = readGLTexture(textureId);
            if (data == null || data.pixels == null) {
                return 0;
            }

            // Store texture info
            textureInfo.put(key, new TextureInfo(data.width, data.height));

            // Upload to Metal
            long handle = NativeBridge.nUploadEntityTexture(deviceHandle, data.pixels, data.width, data.height);

            if (handle != 0) {
                textureCache.put(key, handle);
                MetalLogger.info("[MetalTextureManager] Uploaded texture {} ({}x{})",
                        textureId, data.width, data.height);
            } else {
                MetalLogger.warn("[MetalTextureManager] Failed to upload texture: {}", textureId);
            }

            // Free the pixel buffer
            MemoryUtil.memFree(data.pixels);

            return handle;

        } catch (Exception e) {
            MetalLogger.error("[MetalTextureManager] Failed to load texture {}: {}",
                    textureId, e.getMessage());
            return 0;
        }
    }

    /**
     * Read texture data from OpenGL.
     */
    private TextureData readGLTexture(Identifier textureId) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            TextureManager textureManager = mc.getTextureManager();
            AbstractTexture texture = textureManager.getTexture(textureId);

            if (texture == null) {
                return null;
            }

            // Get GL texture ID
            // In MC 1.21, we need to access the GlTexture through the AbstractTexture
            int glId = getGlTextureId(texture);
            if (glId <= 0) {
                MetalLogger.warn("[MetalTextureManager] Could not get GL ID for: {}", textureId);
                return null;
            }

            // Save current binding
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            try {
                // Bind texture
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);

                // Get dimensions
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

                if (width <= 0 || height <= 0) {
                    MetalLogger.warn("[MetalTextureManager] Invalid texture dimensions for {}: {}x{}",
                            textureId, width, height);
                    return null;
                }

                // Allocate buffer
                int dataSize = width * height * 4;
                ByteBuffer pixels = MemoryUtil.memAlloc(dataSize);

                // Read pixels (BGRA for Metal)
                GL12.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);

                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) {
                    MetalLogger.warn("[MetalTextureManager] GL error reading texture {}: {}",
                            textureId, error);
                    MemoryUtil.memFree(pixels);
                    return null;
                }

                return new TextureData(pixels, width, height);

            } finally {
                // Restore previous binding
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            }

        } catch (Exception e) {
            MetalLogger.error("[MetalTextureManager] Error reading GL texture {}: {}",
                    textureId, e.getMessage());
            return null;
        }
    }

    /**
     * Get GL texture ID from AbstractTexture.
     * This handles MC 1.21's new texture API.
     */
    private int getGlTextureId(AbstractTexture texture) {
        try {
            // Try reflection to get the GL ID
            // In MC 1.21, AbstractTexture has a GpuTexture field
            java.lang.reflect.Field[] fields = texture.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(texture);
                if (value != null && value.getClass().getSimpleName().contains("GlTexture")) {
                    // Found GlTexture, get its ID
                    java.lang.reflect.Method getIdMethod = value.getClass().getMethod("getGlId");
                    if (getIdMethod != null) {
                        return (int) getIdMethod.invoke(value);
                    }
                }
            }

            // Fallback: try common method names
            try {
                java.lang.reflect.Method method = texture.getClass().getMethod("getGlId");
                return (int) method.invoke(texture);
            } catch (NoSuchMethodException ignored) {
            }

        } catch (Exception e) {
            MetalLogger.warn("[MetalTextureManager] Could not get GL ID: {}", e.getMessage());
        }

        return -1;
    }

    /**
     * Get the blocks atlas handle.
     */
    public long getBlocksAtlasHandle() {
        return blocksAtlasHandle;
    }

    /**
     * Set the blocks atlas handle (set by atlas capture).
     */
    public void setBlocksAtlasHandle(long handle) {
        this.blocksAtlasHandle = handle;
    }

    /**
     * Invalidate a texture (force reload).
     */
    public void invalidateTexture(Identifier textureId) {
        String key = textureId.toString();
        Long handle = textureCache.remove(key);
        textureInfo.remove(key);

        if (handle != null && handle != 0 && deviceHandle != 0) {
            try {
                NativeBridge.nDestroyEntityTexture(deviceHandle, handle);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
    }

    /**
     * Clear all cached textures.
     */
    public void clearAll() {
        for (Long handle : textureCache.values()) {
            if (handle != null && handle != 0 && deviceHandle != 0) {
                try {
                    NativeBridge.nDestroyEntityTexture(deviceHandle, handle);
                } catch (UnsatisfiedLinkError ignored) {
                }
            }
        }

        textureCache.clear();
        textureInfo.clear();
        blocksAtlasHandle = 0;
        guiAtlasHandle = 0;
        particlesAtlasHandle = 0;

        MetalLogger.info("[MetalTextureManager] Cleared all cached textures");
    }

    /**
     * Get cache statistics.
     */
    public int getCachedTextureCount() {
        return textureCache.size();
    }

    /**
     * Clean up resources.
     */
    public void destroy() {
        clearAll();
        initialized = false;
        deviceHandle = 0;
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

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

    /**
     * Raw texture data.
     */
    private static class TextureData {
        public final ByteBuffer pixels;
        public final int width;
        public final int height;

        public TextureData(ByteBuffer pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }
}
