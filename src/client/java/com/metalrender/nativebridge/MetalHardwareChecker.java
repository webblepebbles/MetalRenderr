package com.metalrender.nativebridge;

import com.mojang.logging.LogUtils;
// Removed unused imports after refactor
import org.slf4j.Logger;

public final class MetalHardwareChecker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Boolean compatible = null;
    private static volatile boolean checkScheduled = false;

    public static boolean isCompatible() {
        scheduleCheck();
        return compatible == null || compatible;
    }

    // Disabled: showIncompatibleScreen() to avoid referencing Screen classes at init
    public static void showIncompatibleScreen() {
        // No-op: avoid referencing net.minecraft.client.gui.screen.Screen at entrypoint
    }

    private static void scheduleCheck() {
        if (compatible != null || checkScheduled)
            return;
        checkScheduled = true;
        try {
            // Avoid referencing MinecraftClient or Screen classes at entrypoint
            compatible = true; // Assume compatible for now
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            compatible = true;
            LOGGER.warn("[MetalRender] Could not schedule GL capability check, allowing fallback", e);
        }
    }

    // Removed: IncompatibleHardwareScreen (no longer used)
}