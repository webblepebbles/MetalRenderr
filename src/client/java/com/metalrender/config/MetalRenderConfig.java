package com.metalrender.config;

public final class MetalRenderConfig {
  private static volatile boolean metalRenderEnabled = true;
  private static volatile boolean mirrorUploads = false;
  private static volatile boolean swapOpaque = false;
  private static volatile boolean swapCutout = false;
  private static volatile boolean swapTranslucent = false;
  private static volatile boolean aggressiveFrustumCulling = true;
  private static volatile boolean occlusionCulling = true;
  private static volatile boolean dynamicQuality = true;
  private static volatile boolean meshShadersEnabled = true;
  private static volatile boolean temporalAAEnabled = true;
  private static volatile boolean distanceLodEnabled = true;
  private static volatile float resolutionScale = 1.0F;
  private static volatile float dqMinScale = 0.7F;
  private static volatile float dqMaxScale = 1.0F;
  private static volatile float dqScaleStep = 0.05F;
  private static volatile double dqTargetFrameMs = 6.67D;
  private static volatile int dqMinViewDistance = 8;
  private static volatile int dqMaxViewDistance = 24;
  private static volatile int dqViewDistanceStep = 2;
  private static volatile float temporalUpscaleTarget = 0.85F;
  private static volatile float temporalBlendFactor = 0.12F;
  private static volatile int lodDistanceThreshold = 8;
  private static volatile int lodFarDistance = 16;
  private static volatile float lodDistantScale = 0.10F;

  private MetalRenderConfig() {}

  public static boolean metalRenderEnabled() { return metalRenderEnabled; }

  public static boolean mirrorUploads() { return mirrorUploads; }

  public static boolean swapOpaque() { return swapOpaque; }

  public static boolean swapCutout() { return swapCutout; }

  public static boolean swapTranslucent() { return swapTranslucent; }

  public static boolean aggressiveFrustumCulling() {
    return aggressiveFrustumCulling;
  }

  public static boolean occlusionCulling() { return occlusionCulling; }

  public static boolean dynamicQuality() { return dynamicQuality; }

  public static boolean meshShadersEnabled() { return meshShadersEnabled; }

  public static boolean temporalAAEnabled() { return temporalAAEnabled; }

  public static boolean distanceLodEnabled() { return distanceLodEnabled; }

  public static float resolutionScale() { return resolutionScale; }

  public static float dqMinScale() { return dqMinScale; }

  public static float dqMaxScale() { return dqMaxScale; }

  public static float dqScaleStep() { return dqScaleStep; }

  public static double dqTargetFrameMs() { return dqTargetFrameMs; }

  public static int dqMinViewDistance() { return dqMinViewDistance; }

  public static int dqMaxViewDistance() { return dqMaxViewDistance; }

  public static int dqViewDistanceStep() { return dqViewDistanceStep; }

  public static float temporalUpscaleTarget() { return temporalUpscaleTarget; }

  public static float temporalBlendFactor() { return temporalBlendFactor; }

  public static int lodDistanceThreshold() { return lodDistanceThreshold; }

  public static int lodFarDistance() { return lodFarDistance; }

  public static float lodDistantScale() { return lodDistantScale; }

  public static void setMetalRenderEnabled(boolean v) {
    metalRenderEnabled = v;
  }

  public static void setMirrorUploads(boolean v) { mirrorUploads = v; }

  public static void setSwapOpaque(boolean v) { swapOpaque = v; }

  public static void setSwapCutout(boolean v) { swapCutout = v; }

  public static void setSwapTranslucent(boolean v) { swapTranslucent = v; }

  public static void setAggressiveFrustumCulling(boolean v) {
    aggressiveFrustumCulling = v;
  }

  public static void setOcclusionCulling(boolean v) { occlusionCulling = v; }

  public static void setDynamicQuality(boolean v) { dynamicQuality = v; }

  public static void setResolutionScale(float v) {
    resolutionScale = clamp(v, 0.5F, 1.5F);
  }

  public static void setDqMinScale(float v) { dqMinScale = v; }

  public static void setDqMaxScale(float v) { dqMaxScale = v; }

  public static void setDqScaleStep(float v) { dqScaleStep = v; }

  public static void setDqTargetFrameMs(double v) { dqTargetFrameMs = v; }

  public static void setDqMinViewDistance(int v) {
    dqMinViewDistance = Math.max(2, v);
  }

  public static void setDqMaxViewDistance(int v) {
    dqMaxViewDistance = Math.max(dqMinViewDistance, v);
  }

  public static void setDqViewDistanceStep(int v) {
    dqViewDistanceStep = Math.max(1, v);
  }

  public static void setMeshShadersEnabled(boolean v) {
    meshShadersEnabled = v;
  }

  public static void setTemporalAAEnabled(boolean v) { temporalAAEnabled = v; }

  public static void setDistanceLodEnabled(boolean v) {
    distanceLodEnabled = v;
  }

  public static void setTemporalUpscaleTarget(float v) {
    temporalUpscaleTarget = clamp(v, 0.5F, 1.0F);
  }

  public static void setTemporalBlendFactor(float v) {
    temporalBlendFactor = clamp(v, 0.01F, 0.5F);
  }

  public static void setLodDistanceThreshold(int v) {
    lodDistanceThreshold = Math.max(5, v);
  }

  public static void setLodFarDistance(int v) {
    lodFarDistance = Math.max(lodDistanceThreshold, v);
  }

  public static void setLodDistantScale(float v) {
    lodDistantScale = clamp(v, 0.05F, 1.0F);
  }

  public static void loadFromSystemProperties() {
    mirrorUploads = getBool("metalrender.mirror", mirrorUploads);
    swapOpaque = getBool("metalrender.swap.opaque", swapOpaque);
    swapCutout = getBool("metalrender.swap.cutout", swapCutout);
    swapTranslucent = getBool("metalrender.swap.translucent", swapTranslucent);
    aggressiveFrustumCulling =
        getBool("metalrender.culling.frustum", aggressiveFrustumCulling);
    occlusionCulling =
        getBool("metalrender.culling.occlusion", occlusionCulling);
    dynamicQuality = getBool("metalrender.dynamic.enable", dynamicQuality);
    resolutionScale =
        getFloat("metalrender.render.resolutionScale", resolutionScale);
    dqMinScale = getFloat("metalrender.dynamic.scale.min", dqMinScale);
    dqMaxScale = getFloat("metalrender.dynamic.scale.max", dqMaxScale);
    dqScaleStep = getFloat("metalrender.dynamic.scale.step", dqScaleStep);
    dqTargetFrameMs =
        getDouble("metalrender.dynamic.targetMs", dqTargetFrameMs);
    setDqMinViewDistance((int)getFloat("metalrender.dynamic.distance.min",
                                       (float)dqMinViewDistance));
    setDqMaxViewDistance((int)getFloat("metalrender.dynamic.distance.max",
                                       (float)dqMaxViewDistance));
    setDqViewDistanceStep((int)getFloat("metalrender.dynamic.distance.step",
                                        (float)dqViewDistanceStep));
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

  public static MetalRenderConfigData capture() {
    MetalRenderConfigData data = new MetalRenderConfigData();
    data.metalRenderEnabled = metalRenderEnabled;
    data.mirrorUploads = mirrorUploads;
    data.aggressiveFrustumCulling = aggressiveFrustumCulling;
    data.occlusionCulling = occlusionCulling;
    data.dynamicQuality = dynamicQuality;
    data.meshShadersEnabled = meshShadersEnabled;
    data.temporalAAEnabled = temporalAAEnabled;
    data.distanceLodEnabled = distanceLodEnabled;
    data.resolutionScale = resolutionScale;
    data.dqMinScale = dqMinScale;
    data.dqMaxScale = dqMaxScale;
    data.dqScaleStep = dqScaleStep;
    data.dqTargetFrameMs = dqTargetFrameMs;
    data.temporalUpscaleTarget = temporalUpscaleTarget;
    data.temporalBlendFactor = temporalBlendFactor;
    data.lodDistanceThreshold = lodDistanceThreshold;
    data.lodFarDistance = lodFarDistance;
    data.lodDistantScale = lodDistantScale;
    return data;
  }

  public static void apply(MetalRenderConfigData data) {
    if (data == null) {
      return;
    }
    metalRenderEnabled = data.metalRenderEnabled;
    mirrorUploads = data.mirrorUploads;
    aggressiveFrustumCulling = data.aggressiveFrustumCulling;
    occlusionCulling = data.occlusionCulling;
    dynamicQuality = data.dynamicQuality;
    meshShadersEnabled = data.meshShadersEnabled;
    temporalAAEnabled = data.temporalAAEnabled;
    distanceLodEnabled = data.distanceLodEnabled;
    resolutionScale = data.resolutionScale;
    dqMinScale = data.dqMinScale;
    dqMaxScale = data.dqMaxScale;
    dqScaleStep = data.dqScaleStep;
    dqTargetFrameMs = data.dqTargetFrameMs;
    temporalUpscaleTarget = data.temporalUpscaleTarget;
    temporalBlendFactor = data.temporalBlendFactor;
    lodDistanceThreshold = data.lodDistanceThreshold;
    lodFarDistance = data.lodFarDistance;
    lodDistantScale = data.lodDistantScale;
  }
}
