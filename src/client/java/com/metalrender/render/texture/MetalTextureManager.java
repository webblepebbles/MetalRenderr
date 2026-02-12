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

public class MetalTextureManager {

    private static final MetalTextureManager INSTANCE = new MetalTextureManager();

    
    private final Map<String, Long> textureCache = new ConcurrentHashMap<>();

    
    private final Map<String, TextureInfo> textureInfo = new ConcurrentHashMap<>();

    
    private long deviceHandle = 0;

    
    private volatile boolean initialized = false;

    
    private static final Identifier BLOCKS_ATLAS = Identifier.of("minecraft", "textures/atlas/blocks.png");
    private static final Identifier GUI_ATLAS = Identifier.of("minecraft", "textures/atlas/gui.png");
    private static final Identifier PARTICLES_ATLAS = Identifier.of("minecraft", "textures/atlas/particles.png");

    
    private long blocksAtlasHandle = 0;
    private long guiAtlasHandle = 0;
    private long particlesAtlasHandle = 0;

    private MetalTextureManager() {
    }

    public static MetalTextureManager getInstance() {
        return INSTANCE;
    }

    
    public void initialize(long device) {
        this.deviceHandle = device;
        this.initialized = true;
        MetalLogger.info("[MetalTextureManager] Initialized with device: {}", device);
    }

    public boolean isInitialized() {
        return initialized && deviceHandle != 0;
    }

    
    public long getOrCreateTexture(Identifier textureId) {
        if (!initialized || deviceHandle == 0 || textureId == null) {
            return 0;
        }

        String key = textureId.toString();
        Long cached = textureCache.get(key);
        if (cached != null) {
            return cached;
        }
        return loadAndUploadTexture(textureId, key);
    }

    
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

    
    public TextureInfo getTextureInfo(String textureId) {
        return textureInfo.get(textureId);
    }

    
    private synchronized long loadAndUploadTexture(Identifier textureId, String key) {
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
            AbstractTexture texture = textureManager.getTexture(textureId);
            if (texture == null) {
                MetalLogger.warn("[MetalTextureManager] Texture not found: {}", textureId);
                return 0;
            }
            TextureData data = readGLTexture(textureId);
            if (data == null || data.pixels == null) {
                return 0;
            }
            textureInfo.put(key, new TextureInfo(data.width, data.height));
            long handle = NativeBridge.nUploadEntityTexture(deviceHandle, data.pixels, data.width, data.height);

            if (handle != 0) {
                textureCache.put(key, handle);
                MetalLogger.info("[MetalTextureManager] Uploaded texture {} ({}x{})",
                        textureId, data.width, data.height);
            } else {
                MetalLogger.warn("[MetalTextureManager] Failed to upload texture: {}", textureId);
            }
            MemoryUtil.memFree(data.pixels);

            return handle;

        } catch (Exception e) {
            MetalLogger.error("[MetalTextureManager] Failed to load texture {}: {}",
                    textureId, e.getMessage());
            return 0;
        }
    }

    
    private TextureData readGLTexture(Identifier textureId) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            TextureManager textureManager = mc.getTextureManager();
            AbstractTexture texture = textureManager.getTexture(textureId);

            if (texture == null) {
                return null;
            }
            int glId = getGlTextureId(texture);
            if (glId <= 0) {
                MetalLogger.warn("[MetalTextureManager] Could not get GL ID for: {}", textureId);
                return null;
            }
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

                if (width <= 0 || height <= 0) {
                    MetalLogger.warn("[MetalTextureManager] Invalid texture dimensions for {}: {}x{}",
                            textureId, width, height);
                    return null;
                }
                int dataSize = width * height * 4;
                ByteBuffer pixels = MemoryUtil.memAlloc(dataSize);
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
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            }

        } catch (Exception e) {
            MetalLogger.error("[MetalTextureManager] Error reading GL texture {}: {}",
                    textureId, e.getMessage());
            return null;
        }
    }

    
    private int getGlTextureId(AbstractTexture texture) {
        try {
            java.lang.reflect.Field[] fields = texture.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(texture);
                if (value != null && value.getClass().getSimpleName().contains("GlTexture")) {
                    java.lang.reflect.Method getIdMethod = value.getClass().getMethod("getGlId");
                    if (getIdMethod != null) {
                        return (int) getIdMethod.invoke(value);
                    }
                }
            }
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

    
    public long getBlocksAtlasHandle() {
        return blocksAtlasHandle;
    }

    
    public void setBlocksAtlasHandle(long handle) {
        this.blocksAtlasHandle = handle;
    }

    
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

    
    public boolean ensureImported(Identifier textureId) {
        if (textureId == null || !initialized || deviceHandle == 0) {
            return false;
        }

        String key = textureId.toString();
        Long cached = textureCache.get(key);
        if (cached != null && cached != 0) {
            return true; 
        }
        long handle = getOrCreateTexture(textureId);
        return handle != 0;
    }

    
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

    
    public int getCachedTextureCount() {
        return textureCache.size();
    }

    
    public void destroy() {
        clearAll();
        initialized = false;
        deviceHandle = 0;
    }

    
    public static class TextureInfo {
        public final int width;
        public final int height;

        public TextureInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    
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
