package com.metalrender;

import com.metalrender.nativebridge.MetalHardwareChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Environment(EnvType.CLIENT)
public class MetalRenderClient implements ClientModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static boolean enabled = true;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[MetalRender] Initializing client");
        init();
    }

    public static void init() {
        if (!MetalHardwareChecker.isCompatible()) {
            disable();
            MetalHardwareChecker.showIncompatibleScreen();
            LOGGER.warn("[MetalRender] Disabled at startup due to incompatible hardware");
        } else {
            enable();
            LOGGER.info("[MetalRender] Enabled");
        }
    }

    public static void disable() {
        enabled = false;
        LOGGER.warn("[MetalRender] Disabled due to incompatible hardware");
    }

    public static void enable() {
        enabled = true;
        LOGGER.info("[MetalRender] Enabled");
    }

    public static boolean isEnabled() {
        return enabled;
    }
}