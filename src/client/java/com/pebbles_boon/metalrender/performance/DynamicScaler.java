package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.lang.reflect.Method;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;

public final class DynamicScaler {
  private static final int EMERGENCY_THRESHOLD_FRAMES = 30;

  private static final long STAT_LOG_INTERVAL_MS = 1500;

  private double emaMs = 0.0;
  private int lastDistance = -1;
  private int lastSimulation = -1;
  private int slowFrameStreak = 0;
  private int fastFrameStreak = 0;
  private int criticalFrameCount = 0;
  private boolean announcedEnable = false;
  private boolean emergencyTriggered = false;
  private boolean vsyncUnlocked = false;
  private boolean uncapWarned = false;
  private long lastLogTimeMs = 0L;
  private long lastUncapCheckMs = 0L;

  public void onFrameEnd(double frameMs) {
    if (frameMs <= 0.0)
      return;

    double target = Math.max(1.0, MetalRenderConfig.dqTargetFrameMs());

    if (!MetalRenderConfig.dynamicQuality()) {
      handleAutoEnable(frameMs, target);
      return;
    }

    slowFrameStreak = 0;

    if (emaMs <= 0.0)
      emaMs = frameMs;
    else
      emaMs = emaMs * 0.7 + frameMs * 0.3;

    double ratio = emaMs / target;

    if (ratio > 2.5) {
      criticalFrameCount++;
      if (criticalFrameCount > EMERGENCY_THRESHOLD_FRAMES &&
          !emergencyTriggered) {
        enableEmergencyMode();
      }
    } else if (ratio < 1.5) {
      criticalFrameCount = Math.max(0, criticalFrameCount - 2);
      if (criticalFrameCount == 0 && emergencyTriggered) {
        disableEmergencyMode();
      }
    }

    MinecraftClient client = MinecraftClient.getInstance();
    ensureUncapped(client);
    float currScale = MetalRenderConfig.resolutionScale();
    int currDist = client.options.getViewDistance().getValue();
    int currSimDist = client.options.getSimulationDistance().getValue();

    if (lastDistance < 0)
      lastDistance = currDist;
    if (lastSimulation < 0)
      lastSimulation = currSimDist;

    float minScale = MetalRenderConfig.dqMinScale();
    float maxScale = MetalRenderConfig.dqMaxScale();
    float scaleStep = MetalRenderConfig.dqScaleStep();

    boolean scaleChanged = false;
    if (ratio > 1.02) {
      float scaleDrop = scaleStep;
      if (ratio > 1.2)
        scaleDrop *= 2f;
      if (ratio > 1.5)
        scaleDrop *= 4f;
      if (ratio > 2.0)
        scaleDrop *= 6f;
      float newScale = Math.max(minScale, currScale - scaleDrop);
      scaleChanged = Math.abs(newScale - currScale) > 1e-3f;
      currScale = newScale;
      fastFrameStreak = 0;
    } else if (ratio < 0.95) {
      float scaleRaise = scaleStep * 0.5f;
      if (ratio < 0.7)
        scaleRaise *= 2f;
      float newScale = Math.min(maxScale, currScale + scaleRaise);
      scaleChanged = Math.abs(newScale - currScale) > 1e-3f;
      currScale = newScale;
      fastFrameStreak++;
    } else {
      fastFrameStreak = Math.max(0, fastFrameStreak - 1);
    }

    int minDistance = Math.max(2, MetalRenderConfig.dqMinViewDistance());
    int maxDistance =
        Math.max(minDistance, MetalRenderConfig.dqMaxViewDistance());

    if (currDist != maxDistance) {
      currDist = maxDistance;
      client.options.getViewDistance().setValue(currDist);
      lastDistance = currDist;
    }

    int minSimulationDistance =
        Math.max(5, MetalRenderConfig.dqMinSimulationDistance());
    int maxSimulationDistance = Math.max(
        minSimulationDistance, MetalRenderConfig.dqMaxSimulationDistance());
    int simulationStep =
        Math.max(1, MetalRenderConfig.dqSimulationDistanceStep());

    boolean simulationChanged = false;
    if (ratio > 1.1 && currScale <= minScale + 0.05f) {
      int steps = ratio > 2.0 ? 3 : (ratio > 1.5 ? 2 : 1);
      int newSimDist =
          Math.max(minSimulationDistance, currSimDist - steps * simulationStep);
      simulationChanged = newSimDist != currSimDist;
      currSimDist = newSimDist;
    } else if (ratio < 0.8 && currScale >= maxScale - 0.05f &&
               fastFrameStreak > 3) {
      int newSimDist =
          Math.min(maxSimulationDistance, currSimDist + simulationStep);
      simulationChanged = newSimDist != currSimDist;
      currSimDist = newSimDist;
      fastFrameStreak = 0;
    }

    if (scaleChanged) {
      MetalRenderConfig.setResolutionScale(currScale);
      MetalLogger.debug("[DQ] Adjusted resolution scale to %.2f (ratio=%.2f)",
                        currScale, ratio);
    }

    if (simulationChanged && currSimDist != lastSimulation) {
      client.options.getSimulationDistance().setValue(currSimDist);
      if (client.getServer() != null &&
          client.getServer().getPlayerManager() != null) {
        client.getServer().getPlayerManager().setSimulationDistance(
            currSimDist);
      }
      lastSimulation = currSimDist;
      MetalLogger.debug(
          "[DQ] Adjusted simulation distance to %d (ratio=%.2f, scale=%.2f)",
          currSimDist, ratio, currScale);
    }

    logFrameStats(currScale, currDist, currSimDist, ratio);
  }

  private void ensureUncapped(MinecraftClient client) {
    if (client == null || client.getWindow() == null)
      return;

    long now = System.currentTimeMillis();
    if (now - lastUncapCheckMs < 1000L)
      return;

    lastUncapCheckMs = now;

    try {
      client.getWindow().setVsync(false);
    } catch (Throwable t) {
      if (!uncapWarned) {
        MetalLogger.warn("[DQ] Unable to disable VSync directly: {}",
                         t.toString());
      }
      uncapWarned = true;
    }

    boolean updatedLimits = false;
    if (client.options != null) {
      if (setSimpleOption(client.options,
                          new String[] {"getEnableVsync", "getEnableVSync"},
                          Boolean.FALSE)) {
        updatedLimits = true;
      }
      if (setSimpleOption(client.options,
                          new String[] {"getFramerateLimit",
                                        "getFrameRateLimit", "getMaxFps"},
                          Integer.valueOf(260))) {
        updatedLimits = true;
      }
    }

    if (updatedLimits && !vsyncUnlocked) {
      vsyncUnlocked = true;
      MetalLogger.info("[DQ] Forced VSync off and raised FPS cap to 260 for "
                       + "high-performance scaling");
    }
  }

  private void logFrameStats(float scale, int viewDistance,
                             int simulationDistance, double ratio) {
    long now = System.currentTimeMillis();
    if (now - lastLogTimeMs < STAT_LOG_INTERVAL_MS)
      return;

    lastLogTimeMs = now;
    double fps = PerformanceController.getLogger().getCurrentFPS();
    MetalLogger.info("[DQ][Stats] FPS=%.1f | Scale=%.2f | Ratio=%.2f | "
                         + "View=%d | Sim=%d | Emergency=%s | Critical=%d",
                     fps, scale, ratio, viewDistance, simulationDistance,
                     emergencyTriggered ? "on" : "off", criticalFrameCount);
  }

  @SuppressWarnings("unchecked")
  private boolean setSimpleOption(Object options, String[] methodNames,
                                  Object value) {
    for (String name : methodNames) {
      try {
        Method method = options.getClass().getMethod(name);
        Object opt = method.invoke(options);
        if (opt instanceof SimpleOption<?> simpleOption) {
          ((SimpleOption<Object>)simpleOption).setValue(value);
          return true;
        }
      } catch (ReflectiveOperationException |
               IllegalArgumentException ignored) {
      }
    }
    return false;
  }

  private void enableEmergencyMode() {
    emergencyTriggered = true;
    MetalRenderConfig.setEmergencyMode(true);
    MetalRenderConfig.setOcclusionCulling(true);

    MinecraftClient client = MinecraftClient.getInstance();
    if (client.options != null) {
      client.options.getEntityShadows().setValue(false);
      client.options.getEntityDistanceScaling().setValue(0.5);
    }

    MetalLogger.info(
        "[DQ] EMERGENCY MODE ENABLED - disabling expensive features");
  }

  private void disableEmergencyMode() {
    emergencyTriggered = false;
    MetalRenderConfig.setEmergencyMode(false);

    MinecraftClient client = MinecraftClient.getInstance();
    if (client.options != null) {

      client.options.getEntityShadows().setValue(true);
      client.options.getEntityDistanceScaling().setValue(1.0);
    }

    MetalLogger.info("[DQ] Emergency mode disabled - restoring features");
  }

  private void handleAutoEnable(double frameMs, double target) {
    if (frameMs > target * 1.2) {
      slowFrameStreak++;
      if (slowFrameStreak >= 6) {
        MetalRenderConfig.setDynamicQuality(true);
        if (!announcedEnable) {
          MetalLogger.info(
              "[DQ] Enabled dynamic quality after sustained %.1f ms frames",
              frameMs);
          announcedEnable = true;
        }
        slowFrameStreak = 0;
      }
    } else {
      slowFrameStreak = Math.max(0, slowFrameStreak - 1);
    }
  }
}
