package com.metalrender.util;

import com.metalrender.config.MetalRenderConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class MetalLogger {
  private static final Logger LOGGER = LogUtils.getLogger();

  /**
   * Master switch for all debug/trace logging.
   * Set to false for production to maximize FPS.
   * This is a compile-time constant so all debug prints get optimized away.
   */
  public static final boolean DEBUG_TRACE = false;

  public static boolean isDebugEnabled() {
    return MetalRenderConfig.isDebugEnabled() || LOGGER.isDebugEnabled();
  }

  /**
   * Quick check for trace-level debugging that runs every frame.
   * Returns false by default for maximum performance.
   */
  public static boolean isTraceEnabled() {
    return DEBUG_TRACE;
  }

  /**
   * Format message with SLF4J-style {} placeholders.
   * Converts {} to %s for String.format compatibility.
   */
  private static String format(String msg, Object... args) {
    if (args == null || args.length == 0) {
      return msg;
    }
    // Convert SLF4J-style {} placeholders to printf-style %s
    // Also handle {:.2f} style format specifiers
    String converted = msg;
    for (int i = 0; i < args.length; i++) {
      // Replace first {} or {:...} with appropriate format specifier
      if (converted.contains("{:")) {
        // Handle format specifier like {:.2f}
        int start = converted.indexOf("{:");
        int end = converted.indexOf("}", start);
        if (end > start) {
          String spec = converted.substring(start + 1, end); // e.g. ":.2f"
          converted = converted.substring(0, start) + "%" + spec.substring(1) + converted.substring(end + 1);
        }
      } else if (converted.contains("{}")) {
        converted = converted.replaceFirst("\\{\\}", "%s");
      }
    }
    try {
      return String.format(converted, args);
    } catch (Exception e) {
      // Fallback: just return the message with args appended
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
