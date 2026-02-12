package com.metalrender.util;

public class VanillaRenderState {

    private static volatile boolean ioSurfaceBlitting = false;

    public static void setIOSurfaceBlitting(boolean blitting) {
        ioSurfaceBlitting = blitting;
    }

    public static boolean isIOSurfaceBlitting() {
        return ioSurfaceBlitting;
    }

    
    public static void drainGLErrors() {
        while (org.lwjgl.opengl.GL11.glGetError() != org.lwjgl.opengl.GL11.GL_NO_ERROR) {
            
        }
    }

    public static boolean shouldAllowVanillaDraws() {
        return true; 
    }
}
