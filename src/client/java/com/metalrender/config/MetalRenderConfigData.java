package com.metalrender.config;

/**
 * Mutable data container representing MetalRender's configurable options.
 */
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

  public int dynamicQualityUpdateFrequency = 3;
  public double targetFrameMs = 5.67;
}
