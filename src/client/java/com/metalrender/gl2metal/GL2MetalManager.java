package com.metalrender.gl2metal;

import com.metalrender.util.MetalLogger;

public class GL2MetalManager {

    private static boolean initialized = false;
    private static boolean enabled = false;
    private static int frameCount = 0;
    private static int windowWidth = 1920;
    private static int windowHeight = 1080;

    
    public static boolean initialize(int width, int height) {
        if (initialized) {
            return true;
        }
        if (!GL2MetalConfig.GL2METAL_ENABLED) {
            MetalLogger.info("[GL2MetalManager] GL2Metal not enabled (use -Dmetalrender.gl2metal=true)");
            return false;
        }

        windowWidth = width;
        windowHeight = height;

        MetalLogger.info("[GL2MetalManager] Initializing with size {}x{}", width, height);
        MetalLogger.info("[GL2MetalManager] Full interception mode: {}", GL2MetalConfig.FULL_INTERCEPTION);
        
        if (GL2MetalConfig.DEBUG_LOGGING) {
            MetalLogger.info(GL2MetalConfig.getConfigSummary());
        }
        GL2MetalTranslator translator = GL2MetalTranslator.getInstance();
        if (!translator.initialize(width, height)) {
            MetalLogger.error("[GL2MetalManager] Failed to initialize GL2MetalTranslator");
            return false;
        }

        initialized = true;
        MetalLogger.info("[GL2MetalManager] Initialized successfully");

        return true;
    }

    
    public static void enable() {
        if (!initialized) {
            MetalLogger.warn("[GL2MetalManager] Cannot enable - not initialized");
            return;
        }
        enabled = true;
        MetalLogger.info("[GL2MetalManager] GL2Metal interception mode ENABLED");
        MetalLogger.info("[GL2MetalManager] Interception categories: drawCalls={}, state={}, buffers={}, textures={}, shaders={}",
                GL2MetalConfig.INTERCEPT_DRAW_CALLS, GL2MetalConfig.INTERCEPT_STATE,
                GL2MetalConfig.INTERCEPT_BUFFERS, GL2MetalConfig.INTERCEPT_TEXTURES,
                GL2MetalConfig.INTERCEPT_SHADERS);
    }
    public static void disable() {
        enabled = false;
        MetalLogger.info("[GL2MetalManager] GL2Metal mode DISABLED");
    }

    public static boolean isEnabled() {
        return enabled && GL2MetalConfig.GL2METAL_ENABLED;
    }

    
    public static boolean isFullInterceptionEnabled() {
        return enabled && GL2MetalConfig.FULL_INTERCEPTION;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    
    public static boolean shouldIntercept(GL2MetalConfig.InterceptionCategory category) {
        return enabled && GL2MetalConfig.isInterceptionEnabled(category);
    }

    public static boolean shouldInterceptState() {
        return enabled && GL2MetalConfig.INTERCEPT_STATE;
    }

    public static boolean shouldInterceptDrawCalls() {
        return enabled && GL2MetalConfig.INTERCEPT_DRAW_CALLS;
    }

    public static boolean shouldInterceptBuffers() {
        return enabled && GL2MetalConfig.INTERCEPT_BUFFERS;
    }

    public static boolean shouldInterceptTextures() {
        return enabled && GL2MetalConfig.INTERCEPT_TEXTURES;
    }

    public static boolean shouldInterceptShaders() {
        return enabled && GL2MetalConfig.INTERCEPT_SHADERS;
    }

    public static boolean shouldInterceptFBOs() {
        return enabled && GL2MetalConfig.INTERCEPT_FBOS;
    }

    public static boolean shouldInterceptVAOs() {
        return enabled && GL2MetalConfig.INTERCEPT_VAOS;
    }

    
    public static boolean isDebugLogging() {
        return GL2MetalConfig.DEBUG_LOGGING;
    }

    
    public static void setDebugLogging(boolean debugLogging) {
        GL2MetalConfig.DEBUG_LOGGING = debugLogging;
        GL2MetalTranslator.getInstance().setDebugLogging(debugLogging);
    }

    
    public static void updateWindowSize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0)
            return;
        if (newWidth == windowWidth && newHeight == windowHeight)
            return;

        windowWidth = newWidth;
        windowHeight = newHeight;

        MetalLogger.info("[GL2MetalManager] Window resized to {}x{}", newWidth, newHeight);
    }

    
    public static void onFrameEnd() {
        if (!enabled)
            return;

        frameCount++;
        GL2MetalTranslator.getInstance().swapBuffers();

        if (frameCount <= 5 || frameCount % 300 == 0) {
            MetalLogger.info("[GL2MetalManager] Frame {} completed", frameCount);
        }
    }

    public static int getWindowWidth() {
        return windowWidth;
    }

    public static int getWindowHeight() {
        return windowHeight;
    }

    
    public static int getFrameCount() {
        return frameCount;
    }

    
    public static void cleanup() {
        initialized = false;
        enabled = false;
        MetalLogger.info("[GL2MetalManager] Cleaned up");
    }
}
