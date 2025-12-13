package com.metalrender;

import com.metalrender.commands.MetalRenderCommand;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.config.MetalRenderConfigManager;
import com.metalrender.debug.ConfigDumpCommand;
import com.metalrender.nativebridge.MetalHardwareChecker;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class MetalRenderClient implements ClientModInitializer {
  private static volatile boolean ENABLED = false;
  private static volatile MetalWorldRenderer WORLD;
  private static volatile MeshShaderBackend MESH_BACKEND;
  private static volatile boolean HARDWARE_SUPPORTED;

  public void onInitializeClient() {
    MetalRenderConfigManager.load();
    MetalRenderConfig.loadFromSystemProperties();
    MetalRenderConfigManager.syncFromRuntime(false);
    HARDWARE_SUPPORTED = MetalHardwareChecker.isCompatible();
    if (!HARDWARE_SUPPORTED) {
      ENABLED = false;
      MetalRenderConfig.setMetalRenderEnabled(false);
      MetalLogger.warn("MetalRender disabled due to incompatible hardware");
      return;
    }

    refreshEnabledState();

    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess) -> {
          MetalRenderCommand.register(dispatcher);
          ConfigDumpCommand.register(dispatcher);
        });
  }

  public static boolean isEnabled() { return ENABLED; }

  public static MetalWorldRenderer getWorldRenderer() { return WORLD; }

  public static MeshShaderBackend getMeshBackend() { return MESH_BACKEND; }

  public static boolean isHardwareSupported() { return HARDWARE_SUPPORTED; }

  public static synchronized void refreshEnabledState() {
    if (!HARDWARE_SUPPORTED) {
      ENABLED = false;
      MetalRenderConfig.setMetalRenderEnabled(false);
      return;
    }

    boolean desired = MetalRenderConfig.metalRenderEnabled();
    if (desired && !ENABLED) {
      enableRenderer();
    } else if (!desired && ENABLED) {
      disableRenderer();
    }
  }

  private static void enableRenderer() {
    MetalWorldRenderer renderer = new MetalWorldRenderer();
    if (!renderer.isReady()) {
      renderer.destroy();
      MetalRenderConfig.setMetalRenderEnabled(false);
      MetalLogger.error("MetalRender failed to initialize; leaving disabled");
      ENABLED = false;
      return;
    }

    WORLD = renderer;
    MESH_BACKEND = new MeshShaderBackend();
    ENABLED = true;
    MetalLogger.info("MetalRender runtime enabled");
  }

  private static void disableRenderer() {
    ENABLED = false;
    if (WORLD != null) {
      WORLD.destroy();
      WORLD = null;
    }
    if (MESH_BACKEND != null) {
      MESH_BACKEND.destroy();
      MESH_BACKEND = null;
    }
    MetalLogger.info("MetalRender runtime disabled");
  }
}
