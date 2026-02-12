package com.metalrender.util;

import com.metalrender.config.MetalRenderConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class MetalLogger {
  private static final Logger LOGGER = LogUtils.getLogger();

  
  public static final boolean DEBUG_TRACE = false;

  public static boolean isDebugEnabled() {
    return MetalRenderConfig.isDebugEnabled() || LOGGER.isDebugEnabled();
  }

  
  public static boolean isTraceEnabled() {
    return DEBUG_TRACE;
  }

  
  private static String format(String msg, Object... args) {
    if (args == null || args.length == 0) {
      return msg;
    }
    String converted = msg;
    for (int i = 0; i < args.length; i++) {
      if (converted.contains("{:")) {
        int start = converted.indexOf("{:");
        int end = converted.indexOf("}", start);
        if (end > start) {
          String spec = converted.substring(start + 1, end); 
          converted = converted.substring(0, start) + "%" + spec.substring(1) + converted.substring(end + 1);
        }
      } else if (converted.contains("{}")) {
        converted = converted.replaceFirst("\\{\\}", "%s");
      }
    }
    try {
      return String.format(converted, args);
    } catch (Exception e) {
      StringBuilder sb = new StringBuilder(msg);
      for (Object arg : args) {
        sb.append(" ").append(arg);
      }
      return sb.toString();
    }
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
