package com.metalrender.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class MetalLogger {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void info(String msg, Object... args) {
        LOGGER.info("[MetalRender] " + msg, args);
    }
    public static void warn(String msg, Object... args) {
        LOGGER.warn("[MetalRender] " + msg, args);
    }
    public static void error(String msg, Object... args) {
        LOGGER.error("[MetalRender] " + msg, args);
    }
}