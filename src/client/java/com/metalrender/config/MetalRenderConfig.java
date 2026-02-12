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
  private static volatile int lodLevel0Distance = 64;
  private static volatile int lodLevel1Distance = 128;
  private static volatile int lodLevel2Distance = 256;
  private static volatile int lodLevel3Distance = 384;
  private static volatile int lodLevel4Distance = 512;
  private static volatile int lodLevel5Distance = 4096;
  private static volatile float lodLevel1Scale = 0.50F;
  private static volatile float lodLevel2Scale = 0.25F;
  private static volatile float lodLevel3Scale = 0.125F;
  private static volatile float lodLevel4Scale = 0.0625F;
  private static volatile float lodLevel5Scale = 0.03125F;
  private static volatile int entityRenderDistanceChunks = 16;
  private static volatile boolean multiICBEnabled = true;
  private static volatile boolean gpuLodSelectionEnabled = true;
  private static volatile boolean hiZOcclusionEnabled = true;
  private static volatile int extremeRenderDistance = 128;
  private static volatile boolean tripleBufferingEnabled = true;
  private static volatile boolean argumentBuffersEnabled = true;
  private static volatile boolean rasterOrderGroupsEnabled = true;
  private static volatile boolean extendedLodEnabled = true;
  private static volatile boolean extendedLodIngestEnabled = true;
  private static volatile boolean extendedLodDepthPrimeEnabled = true;
  private static volatile boolean extendedLodStochasticAlpha = true;
  private static volatile int extendedLodIngestThreads = 2;
  private static volatile int extendedLodCacheSize = 2048;
  private static volatile boolean metalFXSpatialEnabled = false;
  private static volatile float metalFXSpatialScale = 0.667f;
  private static volatile int blockCullingLevel = 0;

  private static volatile boolean sodiumlessEnabled = false;
  private static volatile boolean debugEnabled = false;
  private static volatile boolean blockCullingEnabled = true;
  private static volatile float blockCullingDecorativeDistance = 48.0F;
  private static volatile float blockCullingFunctionalDistance = 96.0F;
  private static volatile float blockCullingStructuralDistance = 192.0F;

  private static volatile MetalFXQuality metalFXQuality = MetalFXQuality.OFF;
  private static volatile boolean metalFXEnabled = false;
  private static volatile boolean directPresentEnabled = false;
  private static volatile boolean vertexValidationEnabled = true;
  private static volatile boolean unifiedStateEnabled = true;
  private static volatile boolean autoRenderDistanceEnabled = false;
  private static volatile float autoRenderDistanceTargetFps = 60.0f;
  private static volatile boolean dynamicLightingEnabled = true;
  private static volatile float fogEndDistance = 2048.0f;

  public enum Preset {
    LOW, MEDIUM, HIGH, ULTRA
  }

  private MetalRenderConfig() {
  }

  public static boolean metalRenderEnabled() {
    return metalRenderEnabled;
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

  public static boolean meshShadersEnabled() {
    return meshShadersEnabled;
  }

  public static boolean temporalAAEnabled() {
    return temporalAAEnabled;
  }

  public static boolean distanceLodEnabled() {
    return distanceLodEnabled;
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

  public static float temporalUpscaleTarget() {
    return temporalUpscaleTarget;
  }

  public static float temporalBlendFactor() {
    return temporalBlendFactor;
  }

  public static int lodDistanceThreshold() {
    return lodDistanceThreshold;
  }

  public static int lodFarDistance() {
    return lodFarDistance;
  }

  public static float lodDistantScale() {
    return lodDistantScale;
  }

  public static boolean sodiumlessEnabled() {
    return sodiumlessEnabled;
  }

  public static boolean isDebugEnabled() {
    return debugEnabled;
  }

  public static boolean blockCullingEnabled() {
    return blockCullingEnabled;
  }

  public static float blockCullingDecorativeDistance() {
    return blockCullingDecorativeDistance;
  }

  public static float blockCullingFunctionalDistance() {
    return blockCullingFunctionalDistance;
  }

  public static float blockCullingStructuralDistance() {
    return blockCullingStructuralDistance;
  }

  public static MetalFXQuality metalFXQuality() {
    return metalFXQuality;
  }

  public static boolean metalFXEnabled() {
    return metalFXEnabled;
  }

  public static boolean directPresentEnabled() {
    return directPresentEnabled;
  }

  public static boolean vertexValidationEnabled() {
    return vertexValidationEnabled;
  }

  public static boolean unifiedStateEnabled() {
    return unifiedStateEnabled;
  }

  public static void setMetalFXQuality(MetalFXQuality v) {
    metalFXQuality = v != null ? v : MetalFXQuality.OFF;
  }

  public static void setMetalFXEnabled(boolean v) {
    metalFXEnabled = v;
  }

  public static void setDirectPresentEnabled(boolean v) {
    directPresentEnabled = v;
  }

  public static void setVertexValidationEnabled(boolean v) {
    vertexValidationEnabled = v;
  }

  public static void setUnifiedStateEnabled(boolean v) {
    unifiedStateEnabled = v;
  }

  public static boolean autoRenderDistanceEnabled() {
    return autoRenderDistanceEnabled;
  }

  public static void setAutoRenderDistanceEnabled(boolean v) {
    autoRenderDistanceEnabled = v;
  }

  public static float autoRenderDistanceTargetFps() {
    return autoRenderDistanceTargetFps;
  }

  public static void setAutoRenderDistanceTargetFps(float v) {
    autoRenderDistanceTargetFps = Math.max(30.0f, v);
  }

  public static boolean dynamicLightingEnabled() {
    return dynamicLightingEnabled;
  }

  public static void setDynamicLightingEnabled(boolean v) {
    dynamicLightingEnabled = v;
  }

  public static float fogEndDistance() {
    return fogEndDistance;
  }

  public static void setFogEndDistance(float v) {
    fogEndDistance = Math.max(100.0f, Math.min(4096.0f, v));
  }

  public static void applyPreset(Preset preset) {
    switch (preset) {
      case LOW:
        resolutionScale = 0.75f;
        temporalAAEnabled = false;
        distanceLodEnabled = true;
        aggressiveFrustumCulling = true;
        occlusionCulling = false;
        blockCullingEnabled = true;
        setBlockCullingLevel(2);
        meshShadersEnabled = true;
        dynamicQuality = true;
        autoRenderDistanceEnabled = true;
        autoRenderDistanceTargetFps = 60.0f;
        multiICBEnabled = true;
        gpuLodSelectionEnabled = true;
        hiZOcclusionEnabled = true;
        entityRenderDistanceChunks = 8;
        lodLevel0Distance = 32;
        lodLevel1Distance = 64;
        lodLevel2Distance = 128;
        lodLevel3Distance = 192;
        lodLevel4Distance = 256;
        lodLevel5Distance = 1024;
        break;
      case MEDIUM:
        resolutionScale = 1.0f;
        temporalAAEnabled = false;
        distanceLodEnabled = true;
        aggressiveFrustumCulling = true;
        occlusionCulling = true;
        blockCullingEnabled = true;
        setBlockCullingLevel(1);
        meshShadersEnabled = true;
        dynamicQuality = true;
        autoRenderDistanceEnabled = false;
        multiICBEnabled = true;
        gpuLodSelectionEnabled = true;
        hiZOcclusionEnabled = true;
        entityRenderDistanceChunks = 12;
        lodLevel0Distance = 48;
        lodLevel1Distance = 96;
        lodLevel2Distance = 192;
        lodLevel3Distance = 320;
        lodLevel4Distance = 448;
        lodLevel5Distance = 2048;
        break;
      case HIGH:
        resolutionScale = 1.0f;
        temporalAAEnabled = true;
        distanceLodEnabled = true;
        aggressiveFrustumCulling = true;
        occlusionCulling = true;
        blockCullingEnabled = true;
        setBlockCullingLevel(0);
        meshShadersEnabled = true;
        dynamicQuality = false;
        autoRenderDistanceEnabled = false;
        multiICBEnabled = true;
        gpuLodSelectionEnabled = true;
        hiZOcclusionEnabled = true;
        entityRenderDistanceChunks = 16;
        lodLevel0Distance = 64;
        lodLevel1Distance = 128;
        lodLevel2Distance = 256;
        lodLevel3Distance = 384;
        lodLevel4Distance = 512;
        lodLevel5Distance = 3072;
        break;
      case ULTRA:
        resolutionScale = 1.0f;
        temporalAAEnabled = true;
        distanceLodEnabled = true;
        aggressiveFrustumCulling = true;
        occlusionCulling = true;
        blockCullingEnabled = true;
        setBlockCullingLevel(0);
        meshShadersEnabled = true;
        dynamicQuality = false;
        autoRenderDistanceEnabled = false;
        multiICBEnabled = true;
        gpuLodSelectionEnabled = true;
        hiZOcclusionEnabled = true;
        entityRenderDistanceChunks = 16;
        extremeRenderDistance = 128;
        metalFXSpatialEnabled = true;
        tripleBufferingEnabled = true;
        argumentBuffersEnabled = true;
        lodLevel0Distance = 96;
        lodLevel1Distance = 192;
        lodLevel2Distance = 384;
        lodLevel3Distance = 512;
        lodLevel4Distance = 640;
        lodLevel5Distance = 4096;
        break;
    }
  }

  public static void setsodiumlessEnabled(boolean v) {
    sodiumlessEnabled = v;
  }

  public static void setMetalRenderEnabled(boolean v) {
    metalRenderEnabled = v;
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

  public static void setMeshShadersEnabled(boolean v) {
    meshShadersEnabled = v;
  }

  public static void setTemporalAAEnabled(boolean v) {
    temporalAAEnabled = v;
  }

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

  public static int lodLevel0Distance() {
    return lodLevel0Distance;
  }

  public static int lodLevel1Distance() {
    return lodLevel1Distance;
  }

  public static int lodLevel2Distance() {
    return lodLevel2Distance;
  }

  public static int lodLevel3Distance() {
    return lodLevel3Distance;
  }

  public static int lodLevel4Distance() {
    return lodLevel4Distance;
  }

  public static float lodLevel1Scale() {
    return lodLevel1Scale;
  }

  public static float lodLevel2Scale() {
    return lodLevel2Scale;
  }

  public static float lodLevel3Scale() {
    return lodLevel3Scale;
  }

  public static float lodLevel4Scale() {
    return lodLevel4Scale;
  }

  public static void setLodLevel0Distance(int v) {
    lodLevel0Distance = Math.max(16, v);
  }

  public static void setLodLevel1Distance(int v) {
    lodLevel1Distance = Math.max(lodLevel0Distance, v);
  }

  public static void setLodLevel2Distance(int v) {
    lodLevel2Distance = Math.max(lodLevel1Distance, v);
  }

  public static void setLodLevel3Distance(int v) {
    lodLevel3Distance = Math.max(lodLevel2Distance, v);
  }

  public static void setLodLevel4Distance(int v) {
    lodLevel4Distance = Math.max(lodLevel3Distance, v);
  }

  public static void setLodLevel1Scale(float v) {
    lodLevel1Scale = clamp(v, 0.01F, 1.0F);
  }

  public static void setLodLevel2Scale(float v) {
    lodLevel2Scale = clamp(v, 0.01F, 1.0F);
  }

  public static void setLodLevel3Scale(float v) {
    lodLevel3Scale = clamp(v, 0.01F, 1.0F);
  }

  public static void setLodLevel4Scale(float v) {
    lodLevel4Scale = clamp(v, 0.01F, 1.0F);
  }

  public static int blockCullingLevel() {
    return blockCullingLevel;
  }

  public static void setBlockCullingLevel(int v) {
    blockCullingLevel = Math.max(0, Math.min(2, v));
    switch (blockCullingLevel) {
      case 0:
        blockCullingDecorativeDistance = 48.0f;
        blockCullingFunctionalDistance = 96.0f;
        blockCullingStructuralDistance = 192.0f;
        break;
      case 1:
        blockCullingDecorativeDistance = 32.0f;
        blockCullingFunctionalDistance = 64.0f;
        blockCullingStructuralDistance = 128.0f;
        break;
      case 2:
        blockCullingDecorativeDistance = 24.0f;
        blockCullingFunctionalDistance = 48.0f;
        blockCullingStructuralDistance = 96.0f;
        break;
    }
  }

  public static int getLodLevelForDistance(float distanceBlocks) {
    if (distanceBlocks <= lodLevel0Distance)
      return 0;
    if (distanceBlocks <= lodLevel1Distance)
      return 1;
    if (distanceBlocks <= lodLevel2Distance)
      return 2;
    if (distanceBlocks <= lodLevel3Distance)
      return 3;
    if (distanceBlocks <= lodLevel4Distance)
      return 4;
    return 5;
  }

  public static int getLodLevelForDistanceSq(float distSq) {
    if (distSq <= (float) lodLevel0Distance * lodLevel0Distance)
      return 0;
    if (distSq <= (float) lodLevel1Distance * lodLevel1Distance)
      return 1;
    if (distSq <= (float) lodLevel2Distance * lodLevel2Distance)
      return 2;
    if (distSq <= (float) lodLevel3Distance * lodLevel3Distance)
      return 3;
    if (distSq <= (float) lodLevel4Distance * lodLevel4Distance)
      return 4;
    return 5;
  }

  public static float getLodScaleForLevel(int level) {
    return switch (level) {
      case 0 -> 1.0f;
      case 1 -> lodLevel1Scale;
      case 2 -> lodLevel2Scale;
      case 3 -> lodLevel3Scale;
      case 4 -> lodLevel4Scale;
      case 5 -> lodLevel5Scale;
      default -> lodLevel5Scale;
    };
  }

  public static boolean shouldRenderEntitiesAtDistance(int distanceChunks) {
    return distanceChunks <= entityRenderDistanceChunks;
  }

  public static int entityRenderDistanceChunks() {
    return entityRenderDistanceChunks;
  }

  public static void setEntityRenderDistanceChunks(int v) {
    entityRenderDistanceChunks = Math.max(4, Math.min(64, v));
  }

  public static boolean multiICBEnabled() {
    return multiICBEnabled;
  }

  public static void setMultiICBEnabled(boolean v) {
    multiICBEnabled = v;
  }

  public static boolean gpuLodSelectionEnabled() {
    return gpuLodSelectionEnabled;
  }

  public static void setGpuLodSelectionEnabled(boolean v) {
    gpuLodSelectionEnabled = v;
  }

  public static boolean hiZOcclusionEnabled() {
    return hiZOcclusionEnabled;
  }

  public static void setHiZOcclusionEnabled(boolean v) {
    hiZOcclusionEnabled = v;
  }

  public static int extremeRenderDistance() {
    return extremeRenderDistance;
  }

  public static void setExtremeRenderDistance(int v) {
    extremeRenderDistance = Math.max(2, Math.min(256, v));
  }

  public static boolean tripleBufferingEnabled() {
    return tripleBufferingEnabled;
  }

  public static void setTripleBufferingEnabled(boolean v) {
    tripleBufferingEnabled = v;
  }

  public static boolean argumentBuffersEnabled() {
    return argumentBuffersEnabled;
  }

  public static void setArgumentBuffersEnabled(boolean v) {
    argumentBuffersEnabled = v;
  }

  public static boolean rasterOrderGroupsEnabled() {
    return rasterOrderGroupsEnabled;
  }

  public static void setRasterOrderGroupsEnabled(boolean v) {
    rasterOrderGroupsEnabled = v;
  }

  public static boolean extendedLodEnabled() {
    return extendedLodEnabled;
  }

  public static void setExtendedLodEnabled(boolean v) {
    extendedLodEnabled = v;
  }

  public static boolean extendedLodIngestEnabled() {
    return extendedLodIngestEnabled;
  }

  public static void setExtendedLodIngestEnabled(boolean v) {
    extendedLodIngestEnabled = v;
  }

  public static boolean extendedLodDepthPrimeEnabled() {
    return extendedLodDepthPrimeEnabled;
  }

  public static void setExtendedLodDepthPrimeEnabled(boolean v) {
    extendedLodDepthPrimeEnabled = v;
  }

  public static boolean extendedLodStochasticAlpha() {
    return extendedLodStochasticAlpha;
  }

  public static void setExtendedLodStochasticAlpha(boolean v) {
    extendedLodStochasticAlpha = v;
  }

  public static int extendedLodIngestThreads() {
    return extendedLodIngestThreads;
  }

  public static void setExtendedLodIngestThreads(int v) {
    extendedLodIngestThreads = Math.max(1, Math.min(4, v));
  }

  public static int extendedLodCacheSize() {
    return extendedLodCacheSize;
  }

  public static void setExtendedLodCacheSize(int v) {
    extendedLodCacheSize = Math.max(256, Math.min(8192, v));
  }

  public static boolean metalFXSpatialEnabled() {
    return metalFXSpatialEnabled;
  }

  public static void setMetalFXSpatialEnabled(boolean v) {
    metalFXSpatialEnabled = v;
  }

  public static float metalFXSpatialScale() {
    return metalFXSpatialScale;
  }

  public static void setMetalFXSpatialScale(float v) {
    metalFXSpatialScale = Math.max(0.333f, Math.min(1.0f, v));
  }

  public static int lodLevel5Distance() {
    return lodLevel5Distance;
  }

  public static void setLodLevel5Distance(int v) {
    lodLevel5Distance = Math.max(lodLevel4Distance, v);
  }

  public static float lodLevel5Scale() {
    return lodLevel5Scale;
  }

  public static void setLodLevel5Scale(float v) {
    lodLevel5Scale = clamp(v, 0.001F, 1.0F);
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
    setDqMinViewDistance((int) getFloat("metalrender.dynamic.distance.min",
        (float) dqMinViewDistance));
    setDqMaxViewDistance((int) getFloat("metalrender.dynamic.distance.max",
        (float) dqMaxViewDistance));
    setDqViewDistanceStep((int) getFloat("metalrender.dynamic.distance.step",
        (float) dqViewDistanceStep));
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
    data.lodLevel0Distance = lodLevel0Distance;
    data.lodLevel1Distance = lodLevel1Distance;
    data.lodLevel2Distance = lodLevel2Distance;
    data.lodLevel3Distance = lodLevel3Distance;
    data.lodLevel4Distance = lodLevel4Distance;
    data.lodLevel5Distance = lodLevel5Distance;
    data.lodLevel1Scale = lodLevel1Scale;
    data.lodLevel2Scale = lodLevel2Scale;
    data.lodLevel3Scale = lodLevel3Scale;
    data.lodLevel4Scale = lodLevel4Scale;
    data.lodLevel5Scale = lodLevel5Scale;
    data.blockCullingLevel = blockCullingLevel;
    data.entityRenderDistanceChunks = entityRenderDistanceChunks;
    data.multiICBEnabled = multiICBEnabled;
    data.gpuLodSelectionEnabled = gpuLodSelectionEnabled;
    data.hiZOcclusionEnabled = hiZOcclusionEnabled;
    data.extremeRenderDistance = extremeRenderDistance;
    data.tripleBufferingEnabled = tripleBufferingEnabled;
    data.argumentBuffersEnabled = argumentBuffersEnabled;
    data.rasterOrderGroupsEnabled = rasterOrderGroupsEnabled;
    data.metalFXSpatialEnabled = metalFXSpatialEnabled;
    data.metalFXSpatialScale = metalFXSpatialScale;
    data.extendedLodEnabled = extendedLodEnabled;
    data.extendedLodIngestEnabled = extendedLodIngestEnabled;
    data.extendedLodDepthPrimeEnabled = extendedLodDepthPrimeEnabled;
    data.extendedLodStochasticAlpha = extendedLodStochasticAlpha;
    data.extendedLodIngestThreads = extendedLodIngestThreads;
    data.extendedLodCacheSize = extendedLodCacheSize;
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
    lodLevel0Distance = data.lodLevel0Distance;
    lodLevel1Distance = data.lodLevel1Distance;
    lodLevel2Distance = data.lodLevel2Distance;
    lodLevel3Distance = data.lodLevel3Distance;
    lodLevel4Distance = data.lodLevel4Distance;
    lodLevel5Distance = data.lodLevel5Distance;
    lodLevel1Scale = data.lodLevel1Scale;
    lodLevel2Scale = data.lodLevel2Scale;
    lodLevel3Scale = data.lodLevel3Scale;
    lodLevel4Scale = data.lodLevel4Scale;
    lodLevel5Scale = data.lodLevel5Scale;
    setBlockCullingLevel(data.blockCullingLevel);
    entityRenderDistanceChunks = data.entityRenderDistanceChunks;
    multiICBEnabled = data.multiICBEnabled;
    gpuLodSelectionEnabled = data.gpuLodSelectionEnabled;
    hiZOcclusionEnabled = data.hiZOcclusionEnabled;
    extremeRenderDistance = data.extremeRenderDistance;
    tripleBufferingEnabled = data.tripleBufferingEnabled;
    argumentBuffersEnabled = data.argumentBuffersEnabled;
    rasterOrderGroupsEnabled = data.rasterOrderGroupsEnabled;
    metalFXSpatialEnabled = data.metalFXSpatialEnabled;
    metalFXSpatialScale = data.metalFXSpatialScale;
    extendedLodEnabled = data.extendedLodEnabled;
    extendedLodIngestEnabled = data.extendedLodIngestEnabled;
    extendedLodDepthPrimeEnabled = data.extendedLodDepthPrimeEnabled;
    extendedLodStochasticAlpha = data.extendedLodStochasticAlpha;
    extendedLodIngestThreads = data.extendedLodIngestThreads;
    extendedLodCacheSize = data.extendedLodCacheSize;
  }
}
