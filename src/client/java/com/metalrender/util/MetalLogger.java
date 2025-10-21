package com.metalrender.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class MetalLogger {
  private static final Logger LOGGER = LogUtils.getLogger();

  private static String format(String msg, Object... args) {
    return args != null && args.length != 0 ? String.format(msg, args) : msg;
  }

  public static void info(String msg, Object... args) {
    LOGGER.info("[MetalRender] {}", format(msg, args));
  }

  public static void debug(String msg, Object... args) {
    LOGGER.debug("[MetalRender] {}", format(msg, args));
  }

  public static void warn(String msg, Object... args) {
    LOGGER.warn("[MetalRender] {}", format(msg, args));
  }

  public static void error(String msg, Object... args) {
    LOGGER.error("[MetalRender] {}", format(msg, args));
  }
}
