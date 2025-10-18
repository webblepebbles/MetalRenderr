package com.metalrender.nativebridge;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class MetalHardwareChecker {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static volatile Boolean compatible = null;
  private static volatile boolean checkScheduled = false;

  public static boolean isCompatible() {
    scheduleCheck();
    return compatible == null || compatible;
  }

  public static void showIncompatibleScreen() {}

  private static void scheduleCheck() {
    if (compatible == null && !checkScheduled) {
      checkScheduled = true;

      try {
        compatible = true;
      } catch (IllegalArgumentException | UnsatisfiedLinkError var1) {
        compatible = true;
        LOGGER.warn("[MetalRender] Could not schedule GL capability check, "
                        + "allowing fallback",
                    var1);
      }
    }
  }
}
