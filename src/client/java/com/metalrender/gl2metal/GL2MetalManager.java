package com.metalrender.gl2metal;

import com.metalrender.util.MetalLogger;

/**
 * Manages the GL2Metal translation mode.
 * 
 * When enabled, intercepts OpenGL calls and translates them to Metal
 * equivalents.
 * All rendering is done through Metal and displayed on a native Metal window.
 */
public class GL2MetalManager {

    private static boolean initialized = false;
    private static boolean enabled = false;
    private static int frameCount = 0;
    private static int windowWidth = 1920;
    private static int windowHeight = 1080;
    private static boolean debugLogging = false;

    /**
     * Initialize the GL2Metal system and create the Metal window.
     * Call this once when the game is ready (after GLFW window created).
     */
    public static boolean initialize(int width, int height) {
        if (initialized) {
            return true;
        }

        windowWidth = width;
        windowHeight = height;

        MetalLogger.info("[GL2MetalManager] Initializing with size {}x{}", width, height);

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
    }

    /**
     * Disable GL2Metal mode.
     */
    public static void disable() {
        enabled = false;
        MetalLogger.info("[GL2MetalManager] GL2Metal mode DISABLED");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isInitialized() {
        return initialized;
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
     * Set debug logging on/off.
     */
    public static void setDebugLogging(boolean enabled) {
        debugLogging = enabled;
        // Pass to translator for native-level debug logging
        GL2MetalTranslator.getInstance().setDebugLogging(enabled);
    }

    public static boolean isDebugLogging() {
        return debugLogging;
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
