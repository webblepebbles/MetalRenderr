package com.metalrender.util;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public class MetalLogger {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void info(String msg) {
        LOGGER.info("[MetalRender] " + msg);
    }
    public static void warn(String msg) {
        LOGGER.warn("[MetalRender] " + msg);
    }
    public static void error(String msg) {
        LOGGER.error("[MetalRender] " + msg);
    }
}
