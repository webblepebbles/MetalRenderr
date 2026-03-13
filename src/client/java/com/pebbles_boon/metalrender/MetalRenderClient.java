package com.pebbles_boon.metalrender;

import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.unified.MetalRenderCoordinator;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.sodium.backend.SodiumMetalInterface;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;

public class MetalRenderClient implements ClientModInitializer {

  private static MetalRenderClient instance;
  private static MetalRenderer renderer;
  private static MetalRenderConfig config;
  private static MetalRenderCoordinator coordinator;
  private static MeshShaderBackend meshShaderBackend;
  private static SodiumMetalInterface sodiumInterface;
  private static MetalWorldRenderer worldRenderer;
  private static boolean metalAvailable = false;

  @Override
  public void onInitializeClient() {
    instance = this;
    MetalLogger.info("MetalRender v0.1.7 starteding...");

    config = MetalRenderConfig.load();

    if (!config.enableMetalRendering) {
      MetalLogger.info("Metal rendering disabled in config");
      return;
    }

    try {
      NativeBridge.loadLibrary();
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.error("Native library unavailable - Metal rendering disabled",
                        e);
      return;
    }

    try {
      if (MetalHardwareChecker.isMetalSupported()) {
        renderer = new MetalRenderer();
        renderer.init(0, 0);
        metalAvailable = renderer.isAvailable();

        if (metalAvailable) {
          coordinator = new MetalRenderCoordinator();
          coordinator.initialize();
          worldRenderer = new MetalWorldRenderer();
          meshShaderBackend = new MeshShaderBackend();
          meshShaderBackend.initialize();
          MetalLogger.info("Metal backend ready: " +
                           MetalHardwareChecker.getDeviceName());
        }
      } else {
        MetalLogger.warn("Metal not supported on this device");
      }
    } catch (Exception e) {
      MetalLogger.error("Failed to initialize Metal backend", e);
      metalAvailable = false;
    }
  }

  public static MetalRenderClient getInstance() { return instance; }

  public static MetalRenderer getRenderer() { return renderer; }

  public static MetalRenderConfig getConfig() { return config; }

  public static MetalRenderCoordinator getCoordinator() { return coordinator; }

  public static MeshShaderBackend getMeshShaderBackend() {
    return meshShaderBackend;
  }

  public static boolean isMetalAvailable() { return metalAvailable; }

  public static boolean isEnabled() {
    return metalAvailable && renderer != null && renderer.isAvailable();
  }

  public static MetalWorldRenderer getWorldRenderer() { return worldRenderer; }

  public static SodiumMetalInterface getSodiumInterface() {
    if (sodiumInterface == null) {
      sodiumInterface = new SodiumMetalInterface();
    }
    return sodiumInterface;
  }

  public static boolean isSodiumLoaded() {
    try {
      return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(
          "sodium");
    } catch (Exception e) {
      return false;
    }
  }
}
