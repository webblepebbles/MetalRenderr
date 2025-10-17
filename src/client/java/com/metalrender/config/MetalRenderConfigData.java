package com.metalrender.config;

/**
 * Mutable data container representing MetalRender's configurable options.
 */
public final class MetalRenderConfigData {
  public boolean metalRenderEnabled = true;
  public boolean dynamicQuality = true;
  public boolean distanceLodEnabled = true;
  public boolean occlusionCulling = false;
  public boolean aggressiveFrustumCulling = true;
  public boolean temporalAAEnabled = true;
  public boolean meshShadersEnabled = true;
  public boolean mirrorUploads = false;

  public double dqTargetFrameMs = 14.0D;
  public float dqMinScale = 0.85F;
  public float dqMaxScale = 1.0F;
  public float dqScaleStep = 0.05F;
  public float temporalUpscaleTarget = 0.85F;
  public float temporalBlendFactor = 0.12F;
  public float resolutionScale = 1.0F;

  public int lodDistanceThreshold = 15;
  public int lodFarDistance = 28;
  public float lodDistantScale = 0.20F;

  // Phase 1 optimization config
  public int dynamicQualityUpdateFrequency =
      3;                              // Batch DQ updates every N frames
  public double targetFrameMs = 6.67; // Frame pacing target (6.67ms = 150 FPS)
}
