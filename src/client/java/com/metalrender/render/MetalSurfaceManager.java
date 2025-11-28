package com.metalrender.render;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFWNativeCocoa;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import java.util.concurrent.TimeoutException;

public final class MetalSurfaceManager {
  private static final AtomicBoolean attachmentAttempted = new AtomicBoolean(false);
  private static final AtomicBoolean attachmentSucceeded = new AtomicBoolean(false);
  private static final AtomicReference<Long> boundWindow = new AtomicReference<>(0L);
  private static volatile CompletableFuture<Boolean> attachmentFuture;
  private static volatile long pendingNativeHandle = 0L;
  private static volatile long pendingCocoaWindow = 0L;
  private static final AtomicLong attachmentAttemptTimestamp = new AtomicLong(0);

  private MetalSurfaceManager() {
  }

  public static boolean ensureSurface(long nativeHandle) {
    MetalLogger.info(
        "[MetalSurface] ensureSurface called: nativeHandle=%d, attachmentSucceeded=%s, attachmentAttempted=%s",
        nativeHandle, attachmentSucceeded.get(), attachmentAttempted.get());

    // Always poll to check if attachment completed
    pollAttachment();

    if (nativeHandle == 0L || !isMacOs()) {
      return false;
    }

    // If already attached, return true
    if (attachmentSucceeded.get()) {
      return true;
    }

    // If attachment is in progress, return false (will complete later via
    // pollAttachment)
    if (attachmentAttempted.get()) {
      return false;
    }

    // Start new attachment attempt
    long cocoaWindow = resolveCocoaWindow();
    if (cocoaWindow == 0L) {
      return false;
    }

    if (!attachmentAttempted.compareAndSet(false, true)) {
      return false;
    }
    MetalLogger.info("[MetalSurface] Starting async CAMetalLayer attach attempt");
    attachmentAttemptTimestamp.set(System.nanoTime());
    pendingNativeHandle = nativeHandle;
    pendingCocoaWindow = cocoaWindow;
    return false;
  }

  // Note: dispatchAttachment used to schedule a blocking client.execute and wait
  // for the
  // result. This caused the background thread to wait for the render thread,
  // producing
  // timeouts and potential freeze conditions. We now schedule the attachment and
  // let
  // `attachmentFuture` be completed by the render thread. The pollAttachment
  // logic will
  // handle completion and expiration.
  @SuppressWarnings("unused")
  private static boolean dispatchAttachment(long nativeHandle, long cocoaWindow)
      throws ExecutionException, InterruptedException, TimeoutException {
    // Kept for compatibility with prior call-sites; not used in the new flow.
    throw new UnsupportedOperationException("dispatchAttachment is no longer supported");
  }

  private static boolean attemptAttachment(long nativeHandle, long cocoaWindow) {
    MetalLogger.info("[MetalSurface] attemptAttachment ENTERED: nativeHandle=%d, cocoaWindow=%d", nativeHandle,
        cocoaWindow);
    if (!isMacOs() || nativeHandle == 0L || cocoaWindow == 0L) {
      MetalLogger.info("[MetalSurface] attemptAttachment early return: isMacOs=%s, nativeHandle=%d, cocoaWindow=%d",
          isMacOs(), nativeHandle, cocoaWindow);
      return false;
    }

    try {
      MetalLogger.info("[MetalSurface] About to call nAttachSurface JNI");
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

  public static void resize(long nativeHandle, int width,
      int height, float scale) {
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

  public static void pollAttachment() {
    // First check if future is done
    if (attachmentFuture != null) {
      MetalLogger.info("[MetalSurface] pollAttachment: checking future, isDone=%s", attachmentFuture.isDone());
      if (attachmentFuture.isDone()) {
        boolean attached = false;
        try {
          attached = attachmentFuture.getNow(false);
        } catch (Exception e) {
          MetalLogger.error("[MetalSurface] Attachment future completed with exception", e);
        }
        if (attached) {
          attachmentSucceeded.set(true);
          attachmentFuture = null;
          attachmentAttemptTimestamp.set(0);
          MetalLogger.info("[MetalSurface] Attachment completed successfully!");
        } else {
          // If it failed quickly, allow retry
          attachmentAttempted.set(false);
          attachmentFuture = null;
          attachmentAttemptTimestamp.set(0);
        }
        return;
      }
    }

    // Now check if we need to schedule the future
    long startNs = attachmentAttemptTimestamp.get();
    if (startNs > 0L) {
      long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
      // If not scheduled yet, schedule immediately (we're already on render thread
      // from renderFrame)
      if (attachmentAttempted.get() && attachmentFuture == null) {
        MetalLogger.info(
            "[MetalSurface] %dms elapsed, attachmentFuture null, pendingNativeHandle=%d, pendingCocoaWindow=%d",
            elapsedMs, pendingNativeHandle, pendingCocoaWindow);
        // Schedule immediately - no delay needed, we're called from render thread
        if (pendingNativeHandle != 0L && pendingCocoaWindow != 0L) {
          try {
            final long nh = pendingNativeHandle;
            final long cw = pendingCocoaWindow;
            // We're already on render thread, just do it directly
            MetalLogger.info("[MetalSurface] Performing attachment directly: nativeHandle=%d, cocoaWindow=%d", nh, cw);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            try {
              MetalLogger.info("[MetalSurface] About to call attemptAttachment with nh=%d, cw=%d", nh, cw);
              boolean ok = attemptAttachment(nh, cw);
              MetalLogger.info("[MetalSurface] attemptAttachment returned: %s", ok);
              result.complete(ok);
            } catch (Throwable t) {
              MetalLogger.error("[MetalSurface] Attachment attempt failed", t);
              result.complete(false);
            }
            attachmentFuture = result;
          } catch (Exception e) {
            MetalLogger.error("[MetalSurface] Failed to perform attachment", e);
            // allow retry on next poll
            attachmentAttempted.set(false);
            attachmentAttemptTimestamp.set(0);
          }
        }
      }

      if (elapsedMs > 10000L) {
        MetalLogger.warn("[MetalSurface] Attachment attempt taking too long (%dms), cancelling", elapsedMs);
        try {
          attachmentFuture.cancel(true);
        } catch (Exception ignored) {
        }
        attachmentAttempted.set(false);
        attachmentFuture = null;
        attachmentAttemptTimestamp.set(0);
      }
    }
  }

  private static long resolveCocoaWindow() {
    if (!isMacOs()) {
      return 0L;
    }

    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null) {
      return 0L;
    }

    Window window = client.getWindow();
    if (window == null) {
      return 0L;
    }

    long glfwHandle = window.getHandle();
    if (glfwHandle == 0L) {
      return 0L;
    }

    long cocoaWindow = GLFWNativeCocoa.glfwGetCocoaWindow(glfwHandle);
    if (cocoaWindow == 0L) {
      MetalLogger.error("[MetalSurface] Failed to obtain NSWindow pointer");
    }
    return cocoaWindow;
  }

  private static boolean isMacOs() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("mac");
  }
}
