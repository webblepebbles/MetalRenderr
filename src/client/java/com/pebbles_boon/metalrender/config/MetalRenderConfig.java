package com.pebbles_boon.metalrender.config;

public final class MetalRenderConfig {
  public boolean enableMetalRendering = true;
  public int mesherMode = 0;

  public boolean enableSimpleLighting = true;
  public boolean enableDebugOverlay = false;

  public int zone1Radius = 16;
  public int zone2Radius = 64;
  public float lodTransitionDistance = 0.8f;
  public int biomeTransitionDetail = 2;
  public boolean enableZone2Lod = true;
  public int leafCullingMode = 1;

  public int targetFrameRate = 60;
  public int maxMemoryMB = 2048;
  public boolean enableTripleBuffering = true;
  public boolean enableMemoryPressureFallback = true;

  public boolean enableMeshShaders = false;
  public boolean enableArgumentBuffers = false;
  public boolean enableProgrammableBlending = false;
  public boolean enableIndirectCommandBuffers = false;
  public boolean enableMemorylessTargets = false;

  private static volatile int lod1Distance = 4;
  private static volatile int lod2Distance = 8;
  private static volatile int lod3Distance = 12;
  private static volatile int lod4Distance = 16;
  private static volatile boolean lodEnabled = true;

  private static volatile boolean mirrorUploads = false;
  private static volatile boolean swapOpaque = false;
  private static volatile boolean swapCutout = false;
  private static volatile boolean swapTranslucent = false;
  private static volatile boolean aggressiveFrustumCulling = true;
  private static volatile boolean occlusionCulling = false;
  private static volatile boolean dynamicQuality = true;
  private static volatile boolean emergencyMode = false;
  private static volatile float resolutionScale = 1.0f;
  private static volatile float dqMinScale = 0.20f;
  private static volatile float dqMaxScale = 1.5f;
  private static volatile float dqScaleStep = 0.08f;
  private static volatile double dqTargetFrameMs = 5.0;
  private static volatile int dqMinViewDistance = 2;
  private static volatile int dqMaxViewDistance = 32;
  private static volatile int dqViewDistanceStep = 1;
  private static volatile int dqMinSimulationDistance = 5;
  private static volatile int dqMaxSimulationDistance = 12;
  private static volatile int dqSimulationDistanceStep = 1;

  public static MetalRenderConfig load() {
    MetalRenderConfig cfg = new MetalRenderConfig();
    cfg.enableMetalRendering = getBool("metalrender.enabled", true);
    cfg.mesherMode = (int)getFloat("metalrender.mesherMode", 0);
    loadFromSystemProperties();
    return cfg;
  }

  private MetalRenderConfig() {}

  public void save() {
    System.setProperty("metalrender.enabled",
                       String.valueOf(enableMetalRendering));
    System.setProperty("metalrender.mesherMode", String.valueOf(mesherMode));
  }

  public static boolean mirrorUploads() { return mirrorUploads; }

  public static boolean swapOpaque() { return swapOpaque; }

  public static boolean swapCutout() { return swapCutout; }

  public static boolean swapTranslucent() { return swapTranslucent; }

  public static boolean aggressiveFrustumCulling() {
    return aggressiveFrustumCulling;
  }

  public static boolean occlusionCulling() { return occlusionCulling; }

  public static boolean dynamicQuality() { return dynamicQuality; }

  public static float resolutionScale() { return resolutionScale; }

  public static float dqMinScale() { return dqMinScale; }

  public static float dqMaxScale() { return dqMaxScale; }

  public static float dqScaleStep() { return dqScaleStep; }

  public static double dqTargetFrameMs() { return dqTargetFrameMs; }

  public static int dqMinViewDistance() { return dqMinViewDistance; }

  public static int dqMaxViewDistance() { return dqMaxViewDistance; }

  public static int dqViewDistanceStep() { return dqViewDistanceStep; }

  public static boolean emergencyMode() { return emergencyMode; }

  public static int dqMinSimulationDistance() {
    return dqMinSimulationDistance;
  }

  public static int dqMaxSimulationDistance() {
    return dqMaxSimulationDistance;
  }

  public static int dqSimulationDistanceStep() {
    return dqSimulationDistanceStep;
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
    resolutionScale = clamp(v, 0.20f, 1.5f);
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

  public static void setEmergencyMode(boolean v) { emergencyMode = v; }

  public static void setDqMinSimulationDistance(int v) {
    dqMinSimulationDistance = Math.max(5, v);
  }

  public static void setDqMaxSimulationDistance(int v) {
    dqMaxSimulationDistance = Math.max(dqMinSimulationDistance, v);
  }

  public static void setDqSimulationDistanceStep(int v) {
    dqSimulationDistanceStep = Math.max(1, v);
  }

  public static boolean lodEnabled() { return lodEnabled; }

  public static void setLodEnabled(boolean v) { lodEnabled = v; }

  public static int lod1Distance() { return lod1Distance; }

  public static void setLod1Distance(int v) { lod1Distance = Math.max(1, v); }

  public static int lod2Distance() { return lod2Distance; }

  public static void setLod2Distance(int v) {
    lod2Distance = Math.max(lod1Distance + 1, v);
  }

  public static int lod3Distance() { return lod3Distance; }

  public static void setLod3Distance(int v) {
    lod3Distance = Math.max(lod2Distance + 1, v);
  }

  public static int lod4Distance() { return lod4Distance; }

  public static void setLod4Distance(int v) {
    lod4Distance = Math.max(lod3Distance + 1, v);
  }

  public static int getLodLevel(int chunkDistance) {
    if (!lodEnabled)
      return 0;
    if (chunkDistance >= lod4Distance)
      return 4;
    if (chunkDistance >= lod3Distance)
      return 3;
    if (chunkDistance >= lod2Distance)
      return 2;
    if (chunkDistance >= lod1Distance)
      return 1;
    return 0;
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
    setDqMinViewDistance(
        (int)getFloat("metalrender.dynamic.distance.min", dqMinViewDistance));
    setDqMaxViewDistance(
        (int)getFloat("metalrender.dynamic.distance.max", dqMaxViewDistance));
    setDqViewDistanceStep(
        (int)getFloat("metalrender.dynamic.distance.step", dqViewDistanceStep));
    setDqMinSimulationDistance((int)getFloat(
        "metalrender.dynamic.simulation.min", dqMinSimulationDistance));
    setDqMaxSimulationDistance((int)getFloat(
        "metalrender.dynamic.simulation.max", dqMaxSimulationDistance));
    setDqSimulationDistanceStep((int)getFloat(
        "metalrender.dynamic.simulation.step", dqSimulationDistanceStep));
    emergencyMode = getBool("metalrender.emergency", emergencyMode);
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
