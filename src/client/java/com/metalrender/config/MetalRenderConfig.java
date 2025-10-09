package com.metalrender.config;

public final class MetalRenderConfig {
    private static volatile boolean mirrorUploads = false;
    private static volatile boolean swapOpaque = false;
    private static volatile boolean swapCutout = false;
    private static volatile boolean swapTranslucent = false;
    private static volatile boolean aggressiveFrustumCulling = true;
    private static volatile boolean occlusionCulling = false;
    // Dynamic quality and resolution scale
    private static volatile boolean dynamicQuality = false;
    private static volatile float resolutionScale = 1.0f; // 1.0 = native
    private static volatile float dqMinScale = 0.7f;
    private static volatile float dqMaxScale = 1.0f;
    private static volatile float dqScaleStep = 0.05f;
    private static volatile double dqTargetFrameMs = 8.0; // target ~120 FPS by default

    private MetalRenderConfig() {}

    public static boolean mirrorUploads() {
        return mirrorUploads;
    }
    public static boolean swapOpaque() {
        return swapOpaque;
    }
    public static boolean swapCutout() {
        return swapCutout;
    }
    public static boolean swapTranslucent() {
        return swapTranslucent;
    }
    public static boolean aggressiveFrustumCulling() {
        return aggressiveFrustumCulling;
    }
    public static boolean occlusionCulling() {
        return occlusionCulling;
    }
    public static boolean dynamicQuality() {
        return dynamicQuality;
    }
    public static float resolutionScale() {
        return resolutionScale;
    }
    public static float dqMinScale() {
        return dqMinScale;
    }
    public static float dqMaxScale() {
        return dqMaxScale;
    }
    public static float dqScaleStep() {
        return dqScaleStep;
    }
    public static double dqTargetFrameMs() {
        return dqTargetFrameMs;
    }

    public static void setMirrorUploads(boolean v) {
        mirrorUploads = v;
    }
    public static void setSwapOpaque(boolean v) {
        swapOpaque = v;
    }
    public static void setSwapCutout(boolean v) {
        swapCutout = v;
    }
    public static void setSwapTranslucent(boolean v) {
        swapTranslucent = v;
    }
    public static void setAggressiveFrustumCulling(boolean v) {
        aggressiveFrustumCulling = v;
    }
    public static void setOcclusionCulling(boolean v) {
        occlusionCulling = v;
    }
    public static void setDynamicQuality(boolean v) {
        dynamicQuality = v;
    }
    public static void setResolutionScale(float v) {
        resolutionScale = clamp(v, 0.5f, 1.5f);
    }
    public static void setDqMinScale(float v) {
        dqMinScale = v;
    }
    public static void setDqMaxScale(float v) {
        dqMaxScale = v;
    }
    public static void setDqScaleStep(float v) {
        dqScaleStep = v;
    }
    public static void setDqTargetFrameMs(double v) {
        dqTargetFrameMs = v;
    }

    public static void loadFromSystemProperties() {
        mirrorUploads = getBool("metalrender.mirror", mirrorUploads);
        swapOpaque = getBool("metalrender.swap.opaque", swapOpaque);
        swapCutout = getBool("metalrender.swap.cutout", swapCutout);
        swapTranslucent = getBool("metalrender.swap.translucent", swapTranslucent);
        aggressiveFrustumCulling = getBool("metalrender.culling.frustum", aggressiveFrustumCulling);
        occlusionCulling = getBool("metalrender.culling.occlusion", occlusionCulling);
        dynamicQuality = getBool("metalrender.dynamic.enable", dynamicQuality);
        resolutionScale = getFloat("metalrender.render.resolutionScale", resolutionScale);
        dqMinScale = getFloat("metalrender.dynamic.scale.min", dqMinScale);
        dqMaxScale = getFloat("metalrender.dynamic.scale.max", dqMaxScale);
        dqScaleStep = getFloat("metalrender.dynamic.scale.step", dqScaleStep);
        dqTargetFrameMs = getDouble("metalrender.dynamic.targetMs", dqTargetFrameMs);
    }

    private static boolean getBool(String key, boolean def) {
        String v = System.getProperty(key);
        if (v == null)
            return def;
        return "1".equals(v) || Boolean.parseBoolean(v);
    }
    private static float getFloat(String key, float def) {
        String v = System.getProperty(key);
        if (v == null)
            return def;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException ex) {
            return def;
        }
    }
    private static double getDouble(String key, double def) {
        String v = System.getProperty(key);
        if (v == null)
            return def;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return def;
        }
    }
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
