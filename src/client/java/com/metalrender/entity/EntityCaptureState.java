package com.metalrender.entity;

public class EntityCaptureState {

    private static boolean enabled = false;
    private static boolean guiMode = false;
    private static boolean particleCaptureEnabled = false;
    private static boolean weatherCaptureEnabled = false;
    private static int frameCount = 0;

    
    public static void enable() {
        enabled = true;
        guiMode = false;
        frameCount++;
        if (frameCount <= 5 || frameCount % 300 == 0) {
            System.out.println("[EntityCaptureState] enable() called - guiMode=false");
        }
    }

    
    public static void disable() {
        enabled = false;
        guiMode = false;
        if (frameCount <= 5 || frameCount % 300 == 0) {
            System.out.println("[EntityCaptureState] disable() called");
        }
    }

    
    public static void setEnabled(boolean value) {
        if (frameCount <= 5 || frameCount % 300 == 0) {
            System.out.println("[EntityCaptureState] setEnabled(" + value + ") called - guiMode=" + value);
        }
        enabled = value;
        guiMode = value; 
    }

    
    public static boolean isEnabled() {
        return enabled;
    }

    
    public static boolean isGuiMode() {
        return guiMode;
    }

    
    public static void enableParticleCapture() {
        particleCaptureEnabled = true;
        enabled = true;
    }

    
    public static void disableParticleCapture() {
        particleCaptureEnabled = false;
        if (!guiMode) {
        }
    }

    
    public static boolean isParticleCaptureEnabled() {
        return particleCaptureEnabled;
    }

    
    public static void enableWeatherCapture() {
        weatherCaptureEnabled = true;
        enabled = true;
    }

    
    public static void disableWeatherCapture() {
        weatherCaptureEnabled = false;
    }

    
    public static boolean isWeatherCaptureEnabled() {
        return weatherCaptureEnabled;
    }

    
    public static int getFrameCount() {
        return frameCount;
    }
}
