package com.metalrender.entity;

/**
 * Manages the entity capture state for Metal rendering.
 * This is a separate class (not a mixin) to avoid mixin static method
 * restrictions.
 */
public class EntityCaptureState {

    private static boolean enabled = false;
    private static boolean guiMode = false;
    private static int frameCount = 0;

    /**
     * Enable entity vertex capture.
     * Called at the start of entity rendering.
     */
    public static void enable() {
        enabled = true;
        guiMode = false;
        frameCount++;
        if (frameCount <= 5 || frameCount % 300 == 0) {
            System.out.println("[EntityCaptureState] enable() called - guiMode=false");
        }
    }

    /**
     * Disable entity vertex capture.
     * Called at the end of entity rendering.
     */
    public static void disable() {
        enabled = false;
        guiMode = false;
        if (frameCount <= 5 || frameCount % 300 == 0) {
            System.out.println("[EntityCaptureState] disable() called");
        }
    }

    /**
     * Set enabled state directly.
     * When enabled during GUI rendering, captured text vertices go to GUI pass.
     */
    public static void setEnabled(boolean value) {
        if (frameCount <= 5 || frameCount % 300 == 0) {
            System.out.println("[EntityCaptureState] setEnabled(" + value + ") called - guiMode=" + value);
        }
        enabled = value;
        guiMode = value; // When set via this method, assume GUI mode
    }

    /**
     * Check if entity capture is currently enabled.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if we're capturing for GUI (text) vs world entities.
     */
    public static boolean isGuiMode() {
        return guiMode;
    }

    /**
     * Get the current frame count (for logging).
     */
    public static int getFrameCount() {
        return frameCount;
    }
}
