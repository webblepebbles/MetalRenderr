package com.metalrender.config;

public final class MetalRenderConfig {
   private static volatile boolean mirrorUploads = false;
   private static volatile boolean swapOpaque = false;
   private static volatile boolean swapCutout = false;
   private static volatile boolean swapTranslucent = false;
   private static volatile boolean aggressiveFrustumCulling = true;
   private static volatile boolean occlusionCulling = false;
   private static volatile boolean dynamicQuality = true;
   private static volatile float resolutionScale = 1.0F;
   private static volatile float dqMinScale = 0.7F;
   private static volatile float dqMaxScale = 1.0F;
   private static volatile float dqScaleStep = 0.05F;
   private static volatile double dqTargetFrameMs = 3.0D;
   private static volatile int dqMinViewDistance = 8;
   private static volatile int dqMaxViewDistance = 24;
   private static volatile int dqViewDistanceStep = 2;

   private MetalRenderConfig() {
   }

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

   public static int dqMinViewDistance() {
      return dqMinViewDistance;
   }

   public static int dqMaxViewDistance() {
      return dqMaxViewDistance;
   }

   public static int dqViewDistanceStep() {
      return dqViewDistanceStep;
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
      resolutionScale = clamp(v, 0.5F, 1.5F);
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

   public static void setDqMinViewDistance(int v) {
      dqMinViewDistance = Math.max(2, v);
   }

   public static void setDqMaxViewDistance(int v) {
      dqMaxViewDistance = Math.max(dqMinViewDistance, v);
   }

   public static void setDqViewDistanceStep(int v) {
      dqViewDistanceStep = Math.max(1, v);
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
      setDqMinViewDistance((int)getFloat("metalrender.dynamic.distance.min", (float)dqMinViewDistance));
      setDqMaxViewDistance((int)getFloat("metalrender.dynamic.distance.max", (float)dqMaxViewDistance));
      setDqViewDistanceStep((int)getFloat("metalrender.dynamic.distance.step", (float)dqViewDistanceStep));
   }

   private static boolean getBool(String key, boolean def) {
      String v = System.getProperty(key);
      if (v == null) {
         return def;
      } else {
         return "1".equals(v) || Boolean.parseBoolean(v);
      }
   }

   private static float getFloat(String key, float def) {
      String v = System.getProperty(key);
      if (v == null) {
         return def;
      } else {
         try {
            return Float.parseFloat(v);
         } catch (NumberFormatException var4) {
            return def;
         }
      }
   }

   private static double getDouble(String key, double def) {
      String v = System.getProperty(key);
      if (v == null) {
         return def;
      } else {
         try {
            return Double.parseDouble(v);
         } catch (NumberFormatException var5) {
            return def;
         }
      }
   }

   private static float clamp(float v, float lo, float hi) {
      return v < lo ? lo : (v > hi ? hi : v);
   }
}
