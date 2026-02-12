package com.metalrender.config;

public final class MetalRenderConfigData {
  public boolean metalRenderEnabled = true;
  public boolean dynamicQuality = true;
  public boolean distanceLodEnabled = true;
  public boolean occlusionCulling = true;
  public boolean aggressiveFrustumCulling = true;
  public boolean temporalAAEnabled = true;
  public boolean meshShadersEnabled = true;
  public boolean mirrorUploads = false;

  public double dqTargetFrameMs = 6.67D;
  public float dqMinScale = 0.85F;
  public float dqMaxScale = 1.0F;
  public float dqScaleStep = 0.05F;
  public float temporalUpscaleTarget = 0.85F;
  public float temporalBlendFactor = 0.12F;
  public float resolutionScale = 1.0F;

  public int lodDistanceThreshold = 8;
  public int lodFarDistance = 16;
  public float lodDistantScale = 0.10F;
  public int lodLevel0Distance = 64;
  public int lodLevel1Distance = 128;
  public int lodLevel2Distance = 256;
  public int lodLevel3Distance = 384;
  public int lodLevel4Distance = 512;
  public int lodLevel5Distance = 4096;
  public float lodLevel1Scale = 0.50F;
  public float lodLevel2Scale = 0.25F;
  public float lodLevel3Scale = 0.125F;
  public float lodLevel4Scale = 0.0625F;
  public float lodLevel5Scale = 0.03125F;
  public int blockCullingLevel = 0;
  public int entityRenderDistanceChunks = 16;
  public boolean multiICBEnabled = true;
  public boolean gpuLodSelectionEnabled = true;
  public boolean hiZOcclusionEnabled = true;
  public int extremeRenderDistance = 128;
  public boolean tripleBufferingEnabled = true;
  public boolean argumentBuffersEnabled = true;
  public boolean rasterOrderGroupsEnabled = true;
  public boolean extendedLodEnabled = true;
  public boolean extendedLodIngestEnabled = true;
  public boolean extendedLodDepthPrimeEnabled = true;
  public boolean extendedLodStochasticAlpha = true;
  public int extendedLodIngestThreads = 2;
  public int extendedLodCacheSize = 2048;
  public boolean metalFXSpatialEnabled = false;
  public float metalFXSpatialScale = 0.667f;

  public int dynamicQualityUpdateFrequency = 3;
  public double targetFrameMs = 5.67;
}
