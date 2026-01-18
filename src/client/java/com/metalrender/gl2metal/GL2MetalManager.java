package com.metalrender.gl2metal;

import com.metalrender.util.MetalLogger;

/**
 * Manages the GL2Metal translation mode.
 * 
 * GL2Metal intercepts OpenGL calls at the LWJGL level and translates them
 * directly to Metal, bypassing Apple's OpenGL compatibility layer.
 * 
 * This is different from MetalRender which uses a custom rendering pipeline.
 * GL2Metal maintains full compatibility with vanilla/modded rendering code
 * since mods still think they're using OpenGL.
 * 
 * Configuration:
 * - Enable via: -Dmetalrender.gl2metal=true
 * - Enable full interception: -Dmetalrender.gl2metal.fullInterception=true
 * - Debug logging: -Dmetalrender.gl2metal.debug=true
 * 
 * @see GL2MetalConfig for all configuration options
 */
public class GL2MetalManager {

    private static boolean initialized = false;
    private static boolean enabled = false;
    private static int frameCount = 0;
    private static int windowWidth = 1920;
    private static int windowHeight = 1080;

    /**
     * Initialize the GL2Metal system and create the Metal window.
     * Call this once when the game is ready (after GLFW window created).
     */
    public static boolean initialize(int width, int height) {
        if (initialized) {
            return true;
        }

        // Check if GL2Metal is enabled via config
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

        // Create the Metal translation layer and window
        GL2MetalTranslator translator = GL2MetalTranslator.getInstance();
        if (!translator.initialize(width, height)) {
            MetalLogger.error("[GL2MetalManager] Failed to initialize GL2MetalTranslator");
            return false;
        }

        initialized = true;
        MetalLogger.info("[GL2MetalManager] Initialized successfully");

        return true;
    }

    /**
     * Enable GL2Metal mode. When enabled:
     * - OpenGL calls are intercepted and translated to Metal
     * - Rendering happens through Metal pipeline
     * - GLFW window continues to receive input
     */
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

    /**
     * Disable GL2Metal mode.
     */
    public static void disable() {
        enabled = false;
        MetalLogger.info("[GL2MetalManager] GL2Metal mode DISABLED");
    }

    public static boolean isEnabled() {
        return enabled && GL2MetalConfig.GL2METAL_ENABLED;
    }

    /**
     * Check if full GL interception is enabled.
     * When true, intercepts individual GL calls.
     * When false, uses framebuffer capture approach.
     */
    public static boolean isFullInterceptionEnabled() {
        return enabled && GL2MetalConfig.FULL_INTERCEPTION;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if a specific interception category is active.
     */
    public static boolean shouldIntercept(GL2MetalConfig.InterceptionCategory category) {
        return enabled && GL2MetalConfig.isInterceptionEnabled(category);
    }

    // ========================================================================
    // Category-specific interception checks (mixin-safe - no enum parameters)
    // These methods avoid passing enum parameters which can cause class loading
    // issues during mixin application.
    // ========================================================================

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

    /**
     * Check if debug logging is enabled.
     */
    public static boolean isDebugLogging() {
        return GL2MetalConfig.DEBUG_LOGGING;
    }

    /**
     * Set debug logging at runtime.
     */
    public static void setDebugLogging(boolean debugLogging) {
        GL2MetalConfig.DEBUG_LOGGING = debugLogging;
        GL2MetalTranslator.getInstance().setDebugLogging(debugLogging);
    }

    /**
     * Update the window size.
     */
    public static void updateWindowSize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0)
            return;
        if (newWidth == windowWidth && newHeight == windowHeight)
            return;

        windowWidth = newWidth;
        windowHeight = newHeight;

        MetalLogger.info("[GL2MetalManager] Window resized to {}x{}", newWidth, newHeight);
    }

    /**
     * Called at the end of each frame to present the Metal drawable.
     */
    public static void onFrameEnd() {
        if (!enabled)
            return;

        frameCount++;

        // Present the Metal frame
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

    /**
     * Get current frame count.
     */
    public static int getFrameCount() {
        return frameCount;
    }

    /**
     * Cleanup resources when shutting down.
     */
    public static void cleanup() {
        initialized = false;
        enabled = false;
        MetalLogger.info("[GL2MetalManager] Cleaned up");
    }
}
