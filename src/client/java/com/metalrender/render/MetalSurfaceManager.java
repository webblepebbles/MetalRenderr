package com.metalrender.render;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFWNativeCocoa;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MetalSurfaceManager {
  private static final AtomicBoolean attachmentAttempted = new AtomicBoolean(false);
  private static final AtomicBoolean attachmentSucceeded = new AtomicBoolean(false);
  private static final AtomicReference<Long> boundWindow = new AtomicReference<>(0L);
  private static boolean gameLoaded = false;

  private MetalSurfaceManager() {
  }

  
  public static boolean ensureSurface(long nativeHandle) {
    return false;
  }

  
  public static void markGameLoaded() {
    gameLoaded = true;
    MetalLogger.info("[MetalSurface] Game loaded - CAMetalLayer attachment now enabled");
  }

  
  private static boolean attemptAttachment(long nativeHandle, long cocoaWindow) {
    MetalLogger.info("[MetalSurface] attemptAttachment: nativeHandle=%d, cocoaWindow=%d", nativeHandle,
        cocoaWindow);
    if (!isMacOs() || nativeHandle == 0L || cocoaWindow == 0L) {
      return false;
    }

    try {
      boolean ok = NativeBridge.nAttachSurface(nativeHandle, cocoaWindow);
      MetalLogger.info("[MetalSurface] nAttachSurface returned: %s", ok);
      if (!ok) {
        MetalLogger.error("[MetalSurface] nAttachSurface returned false");
        return false;
      }

      boundWindow.set(cocoaWindow);
      MetalLogger.info("[MetalSurface] CAMetalLayer attached to window");
      return true;
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.error("[MetalSurface] Native library missing nAttachSurface", e);
      return false;
    } catch (Exception e) {
      MetalLogger.error("[MetalSurface] Unexpected error during attachment", e);
      return false;
    }
  }

  public static void resize(long nativeHandle, int width, int height, float scale) {
    if (!attachmentSucceeded.get() || nativeHandle == 0L) {
      return;
    }
    try {
      NativeBridge.nResize(nativeHandle, width, height, scale);
    } catch (Exception e) {
      MetalLogger.error("[MetalSurface] Resize failed", e);
    }
  }

  public static void detach(long nativeHandle) {
    if (!attachmentSucceeded.get()) {
      return;
    }
    try {
      NativeBridge.nDetachSurface(nativeHandle);
    } catch (UnsatisfiedLinkError ignored) {
    } catch (Exception e) {
      MetalLogger.error("[MetalSurface] Detach error", e);
    } finally {
      attachmentAttempted.set(false);
      attachmentSucceeded.set(false);
      boundWindow.set(0L);
      MetalLogger.info("[MetalSurface] CAMetalLayer detached");
    }
  }

  private static boolean isMacOs() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("mac");
  }
}
