package com.metalrender.gl2metal;

public class GL2MetalConfig {

    
    public static boolean GL2METAL_ENABLED = Boolean.getBoolean("metalrender.gl2metal");

    
    public static boolean FULL_INTERCEPTION = GL2METAL_ENABLED && 
            !Boolean.getBoolean("metalrender.gl2metal.fullInterception.disabled");

    
    public static boolean INTERCEPT_DRAW_CALLS = Boolean.getBoolean("metalrender.gl2metal.drawcalls");

    
    public static boolean INTERCEPT_STATE = Boolean.getBoolean("metalrender.gl2metal.state");

    
    public static boolean INTERCEPT_BUFFERS = Boolean.getBoolean("metalrender.gl2metal.buffers");

    
    public static boolean INTERCEPT_TEXTURES = Boolean.getBoolean("metalrender.gl2metal.textures");

    
    public static boolean INTERCEPT_SHADERS = Boolean.getBoolean("metalrender.gl2metal.shaders");

    
    public static boolean INTERCEPT_FBOS = Boolean.getBoolean("metalrender.gl2metal.fbos");

    
    public static boolean INTERCEPT_VAOS = Boolean.getBoolean("metalrender.gl2metal.vaos");

    
    public static boolean DEBUG_LOGGING = Boolean.getBoolean("metalrender.gl2metal.debug");

    
    public static boolean LOG_ALL_CALLS = Boolean.getBoolean("metalrender.gl2metal.logAllCalls");

    
    public static boolean PROFILING = Boolean.getBoolean("metalrender.gl2metal.profile");

    
    public static boolean VALIDATE_STATE = Boolean.getBoolean("metalrender.gl2metal.validate");

    
    public static boolean WARN_UNHANDLED_CALLS = Boolean.getBoolean("metalrender.gl2metal.warnUnhandled");

    
    public static boolean FALLBACK_ON_ERROR = !Boolean.getBoolean("metalrender.gl2metal.noFallback");

    
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

    
    public static boolean isAnyInterceptionEnabled() {
        return INTERCEPT_DRAW_CALLS || INTERCEPT_STATE || INTERCEPT_BUFFERS ||
               INTERCEPT_TEXTURES || INTERCEPT_SHADERS || INTERCEPT_FBOS || INTERCEPT_VAOS;
    }

    
    public static String getConfigSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[GL2MetalConfig]\n");
        sb.append("  GL2METAL_ENABLED: ").append(GL2METAL_ENABLED).append("\n");
        sb.append("  FULL_INTERCEPTION: ").append(FULL_INTERCEPTION).append("\n");
        sb.append("  ANY_INTERCEPTION_ENABLED: ").append(isAnyInterceptionEnabled()).append("\n");
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
