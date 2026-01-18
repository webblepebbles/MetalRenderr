package com.metalrender.gl2metal;

/**
 * Configuration for GL2Metal interception system.
 * 
 * GL2Metal intercepts OpenGL calls at the LWJGL level and translates them
 * directly to Metal, bypassing Apple's GL compatibility layer entirely.
 * 
 * This is different from MetalRender which uses a custom rendering pipeline.
 * Both systems can be toggled independently.
 */
public class GL2MetalConfig {

    // ========================================================================
    // System Enable Flags
    // ========================================================================

    /**
     * Master switch for GL2Metal system.
     * When true, GL calls are intercepted and routed to Metal.
     * Can be set via -Dmetalrender.gl2metal=true
     */
    public static boolean GL2METAL_ENABLED = Boolean.getBoolean("metalrender.gl2metal");

    /**
     * When true, use full GL interception mode (intercept all GL calls).
     * When false, use framebuffer capture mode (let GL render, then blit).
     * Default: true when GL2METAL_ENABLED (full interception is the goal)
     * Can be disabled via -Dmetalrender.gl2metal.fullInterception=false
     */
    public static boolean FULL_INTERCEPTION = GL2METAL_ENABLED && 
            !Boolean.getBoolean("metalrender.gl2metal.fullInterception.disabled");

    // ========================================================================
    // Per-Category Interception Toggles
    // ========================================================================

    /**
     * Intercept draw calls (glDrawArrays, glDrawElements, etc.)
     * This is the core of the system - must be enabled for Metal rendering.
     * Default: DISABLED until GL→Metal translation is complete
     */
    public static boolean INTERCEPT_DRAW_CALLS = Boolean.getBoolean("metalrender.gl2metal.drawcalls");

    /**
     * Intercept state calls (glEnable, glDisable, glBlendFunc, etc.)
     * Default: DISABLED until GL→Metal translation is complete
     */
    public static boolean INTERCEPT_STATE = Boolean.getBoolean("metalrender.gl2metal.state");

    /**
     * Intercept buffer calls (glGenBuffers, glBufferData, etc.)
     * Default: DISABLED - causes buffer mapping errors
     */
    public static boolean INTERCEPT_BUFFERS = Boolean.getBoolean("metalrender.gl2metal.buffers");

    /**
     * Intercept texture calls (glGenTextures, glTexImage2D, etc.)
     * Default: DISABLED until GL→Metal translation is complete
     */
    public static boolean INTERCEPT_TEXTURES = Boolean.getBoolean("metalrender.gl2metal.textures");

    /**
     * Intercept shader calls (glCreateProgram, glCompileShader, etc.)
     * Default: DISABLED until GL→Metal translation is complete
     */
    public static boolean INTERCEPT_SHADERS = Boolean.getBoolean("metalrender.gl2metal.shaders");

    /**
     * Intercept framebuffer calls (glGenFramebuffers, glBindFramebuffer, etc.)
     * Default: DISABLED until GL→Metal translation is complete
     */
    public static boolean INTERCEPT_FBOS = Boolean.getBoolean("metalrender.gl2metal.fbos");

    /**
     * Intercept VAO calls (glGenVertexArrays, glBindVertexArray, etc.)
     * Default: DISABLED until GL→Metal translation is complete
     */
    public static boolean INTERCEPT_VAOS = Boolean.getBoolean("metalrender.gl2metal.vaos");

    // ========================================================================
    // Debug & Profiling
    // ========================================================================

    /**
     * Enable verbose debug logging for GL2Metal.
     */
    public static boolean DEBUG_LOGGING = Boolean.getBoolean("metalrender.gl2metal.debug");

    /**
     * Log every intercepted GL call (very verbose, impacts performance).
     */
    public static boolean LOG_ALL_CALLS = Boolean.getBoolean("metalrender.gl2metal.logAllCalls");

    /**
     * Enable performance profiling.
     */
    public static boolean PROFILING = Boolean.getBoolean("metalrender.gl2metal.profile");

    /**
     * Validate Metal state after each operation (slow but helps debugging).
     */
    public static boolean VALIDATE_STATE = Boolean.getBoolean("metalrender.gl2metal.validate");

    // ========================================================================
    // Fallback & Compatibility
    // ========================================================================

    /**
     * If an unhandled GL call is encountered, log a warning.
     * When false, silently pass to GL.
     */
    public static boolean WARN_UNHANDLED_CALLS = Boolean.getBoolean("metalrender.gl2metal.warnUnhandled");

    /**
     * Automatically fall back to GL on error instead of crashing.
     */
    public static boolean FALLBACK_ON_ERROR = !Boolean.getBoolean("metalrender.gl2metal.noFallback");

    // ========================================================================
    // Runtime Configuration Methods
    // ========================================================================

    /**
     * Check if a specific interception category is enabled.
     */
    public static boolean isInterceptionEnabled(InterceptionCategory category) {
        if (!GL2METAL_ENABLED || !FULL_INTERCEPTION) {
            return false;
        }

        return switch (category) {
            case DRAW_CALLS -> INTERCEPT_DRAW_CALLS;
            case STATE -> INTERCEPT_STATE;
            case BUFFERS -> INTERCEPT_BUFFERS;
            case TEXTURES -> INTERCEPT_TEXTURES;
            case SHADERS -> INTERCEPT_SHADERS;
            case FBOS -> INTERCEPT_FBOS;
            case VAOS -> INTERCEPT_VAOS;
        };
    }

    /**
     * Enable or disable a category at runtime.
     */
    public static void setInterceptionEnabled(InterceptionCategory category, boolean enabled) {
        switch (category) {
            case DRAW_CALLS -> INTERCEPT_DRAW_CALLS = enabled;
            case STATE -> INTERCEPT_STATE = enabled;
            case BUFFERS -> INTERCEPT_BUFFERS = enabled;
            case TEXTURES -> INTERCEPT_TEXTURES = enabled;
            case SHADERS -> INTERCEPT_SHADERS = enabled;
            case FBOS -> INTERCEPT_FBOS = enabled;
            case VAOS -> INTERCEPT_VAOS = enabled;
        }
    }

    /**
     * Reload configuration from system properties.
     */
    public static void reloadFromSystemProperties() {
        GL2METAL_ENABLED = Boolean.getBoolean("metalrender.gl2metal");
        FULL_INTERCEPTION = GL2METAL_ENABLED && !Boolean.getBoolean("metalrender.gl2metal.fullInterception.disabled");
        INTERCEPT_DRAW_CALLS = !Boolean.getBoolean("metalrender.gl2metal.no.drawcalls");
        INTERCEPT_STATE = !Boolean.getBoolean("metalrender.gl2metal.no.state");
        INTERCEPT_BUFFERS = !Boolean.getBoolean("metalrender.gl2metal.no.buffers");
        INTERCEPT_TEXTURES = !Boolean.getBoolean("metalrender.gl2metal.no.textures");
        INTERCEPT_SHADERS = !Boolean.getBoolean("metalrender.gl2metal.no.shaders");
        INTERCEPT_FBOS = !Boolean.getBoolean("metalrender.gl2metal.no.fbos");
        INTERCEPT_VAOS = !Boolean.getBoolean("metalrender.gl2metal.no.vaos");
        DEBUG_LOGGING = Boolean.getBoolean("metalrender.gl2metal.debug");
        LOG_ALL_CALLS = Boolean.getBoolean("metalrender.gl2metal.logAllCalls");
        PROFILING = Boolean.getBoolean("metalrender.gl2metal.profile");
        VALIDATE_STATE = Boolean.getBoolean("metalrender.gl2metal.validate");
        WARN_UNHANDLED_CALLS = Boolean.getBoolean("metalrender.gl2metal.warnUnhandled");
        FALLBACK_ON_ERROR = !Boolean.getBoolean("metalrender.gl2metal.noFallback");
    }

    /**
     * Print current configuration state.
     */
    public static String getConfigSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[GL2MetalConfig]\n");
        sb.append("  GL2METAL_ENABLED: ").append(GL2METAL_ENABLED).append("\n");
        sb.append("  FULL_INTERCEPTION: ").append(FULL_INTERCEPTION).append("\n");
        sb.append("  Interception Categories:\n");
        sb.append("    DRAW_CALLS: ").append(INTERCEPT_DRAW_CALLS).append("\n");
        sb.append("    STATE: ").append(INTERCEPT_STATE).append("\n");
        sb.append("    BUFFERS: ").append(INTERCEPT_BUFFERS).append("\n");
        sb.append("    TEXTURES: ").append(INTERCEPT_TEXTURES).append("\n");
        sb.append("    SHADERS: ").append(INTERCEPT_SHADERS).append("\n");
        sb.append("    FBOS: ").append(INTERCEPT_FBOS).append("\n");
        sb.append("    VAOS: ").append(INTERCEPT_VAOS).append("\n");
        sb.append("  Debug:\n");
        sb.append("    DEBUG_LOGGING: ").append(DEBUG_LOGGING).append("\n");
        sb.append("    LOG_ALL_CALLS: ").append(LOG_ALL_CALLS).append("\n");
        sb.append("    PROFILING: ").append(PROFILING).append("\n");
        return sb.toString();
    }

    /**
     * Interception categories for granular control.
     */
    public enum InterceptionCategory {
        DRAW_CALLS,
        STATE,
        BUFFERS,
        TEXTURES,
        SHADERS,
        FBOS,
        VAOS
    }
}
