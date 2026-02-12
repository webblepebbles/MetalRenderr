package com.metalrender;

import com.metalrender.commands.MetalRenderCommand;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.config.MetalRenderConfigManager;
import com.metalrender.culling.AsyncOcclusionTracker;
import com.metalrender.debug.ConfigDumpCommand;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.gl2metal.GL2MetalManager;
import com.metalrender.memory.GpuMemoryBudget;
import com.metalrender.nativebridge.MetalHardwareChecker;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.atlas.CapturedAtlasRepository;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.item.MetalItemRenderer;
import com.metalrender.render.particle.MetalParticleRenderer;
import com.metalrender.render.texture.MetalTextureManager;
import com.metalrender.render.unified.MetalRenderCoordinator;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

public final class MetalRenderClient implements ClientModInitializer {
  private static final Identifier BLOCKS_ATLAS_ID = Identifier.of("minecraft",
      "textures/atlas/blocks.png");
  private static volatile boolean ENABLED = false;
  private static volatile MetalWorldRenderer WORLD;
  private static volatile MeshShaderBackend MESH_BACKEND;
  private static volatile AsyncOcclusionTracker OCCLUSION_TRACKER;
  private static volatile GpuMemoryBudget MEMORY_BUDGET;
  private static volatile boolean HARDWARE_SUPPORTED;
  private static volatile boolean SOLO_MODE;

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

    ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
        new SimpleSynchronousResourceReloadListener() {
          @Override
          public Identifier getFabricId() {
            return Identifier.of("metalrender", "atlas_reloader");
          }

          @Override
          public void reload(ResourceManager manager) {
            if (WORLD != null && WORLD.isReady()) {
              MinecraftClient.getInstance().execute(() -> {
                try {
                  MetalLogger.info("[MetalRender] Resources reloaded, re-uploading atlas");
                  WORLD.forceAtlasReupload();
                } catch (Throwable t) {
                  MetalLogger.warn("[MetalRender] Failed to re-upload atlas after reload", t);
                }
              });
            }
          }
        });
  }

  public static boolean isEnabled() {
    if (GL2MetalManager.isEnabled()) {
      return false;
    }
    return ENABLED;
  }

  public static boolean isGL2MetalMode() {
    return GL2MetalManager.isEnabled();
  }

  public static boolean isMetalRenderMode() {
    return ENABLED && !GL2MetalManager.isEnabled();
  }

  public static String getRenderingModeDescription() {
    if (GL2MetalManager.isEnabled()) {
      return "GL2Metal (OpenGL→Metal interception)";
    } else if (ENABLED) {
      return "MetalRender (Custom Metal pipeline)";
    } else {
      return "Vanilla OpenGL";
    }
  }

  public static MetalWorldRenderer getWorldRenderer() {
    return WORLD;
  }

  public static MeshShaderBackend getMeshBackend() {
    return MESH_BACKEND;
  }

  public static String getDeviceName() {
    try {
      if (WORLD != null && WORLD.isReady() && WORLD.getHandle() != 0L) {
        return com.metalrender.nativebridge.NativeBridge.nGetDeviceName(WORLD.getHandle());
      }
    } catch (Throwable e) {
    }
    return "";
  }

  public static AsyncOcclusionTracker getOcclusionTracker() {
    return OCCLUSION_TRACKER;
  }

  public static GpuMemoryBudget getMemoryBudget() {
    return MEMORY_BUDGET;
  }

  public static boolean isHardwareSupported() {
    return HARDWARE_SUPPORTED;
  }

  public static boolean issodiumless() {
    return ENABLED && SOLO_MODE;
  }

  public static boolean isSoloMode() {
    return issodiumless();
  }

  public static synchronized void refreshEnabledState() {
    if (!HARDWARE_SUPPORTED) {
      ENABLED = false;
      MetalRenderConfig.setMetalRenderEnabled(false);
      MetalRenderConfig.setsodiumlessEnabled(false);
      SOLO_MODE = false;
      return;
    }

    SOLO_MODE = MetalRenderConfig.sodiumlessEnabled();
    boolean desired = MetalRenderConfig.metalRenderEnabled();
    if (desired) {
      boolean needsRenderer = !ENABLED || WORLD == null || !WORLD.isReady();
      if (needsRenderer) {
        if (ENABLED) {
          disableRenderer();
        }
        enableRenderer();
      }
    } else if (ENABLED) {
      disableRenderer();
    }

    if (WORLD != null) {
      WORLD.setsodiumless(SOLO_MODE);
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
    OCCLUSION_TRACKER = new AsyncOcclusionTracker();
    long deviceHandle = renderer.getHandle();
    if (deviceHandle != 0) {
      MetalRenderCoordinator.getInstance().initialize(deviceHandle);
      MetalLogger.info("MetalRenderCoordinator initialized with all sub-renderers");
    }

    MetalLogger.info("MetalRender runtime enabled with AsyncOcclusionTracker");
    ENABLED = true;
    SOLO_MODE = MetalRenderConfig.sodiumlessEnabled();
    scheduleAtlasUpload();
  }

  private static void disableRenderer() {
    ENABLED = false;
    MetalRenderCoordinator.getInstance().destroy();

    if (WORLD != null) {
      WORLD.destroy();
      WORLD = null;
    }
    if (MESH_BACKEND != null) {
      MESH_BACKEND.destroy();
      MESH_BACKEND = null;
    }
    if (OCCLUSION_TRACKER != null) {
      OCCLUSION_TRACKER.shutdown();
      OCCLUSION_TRACKER = null;
    }
    MEMORY_BUDGET = null;
    SOLO_MODE = false;
    MetalLogger.info("MetalRender runtime disabled");
  }

  public static void resetRenderer() {
    try {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client != null && !client.isOnThread()) {
        client.execute(MetalRenderClient::forceRebuildRenderer);
      } else {
        forceRebuildRenderer();
      }
    } catch (Throwable e) {
      MetalLogger.error("[MetalRender] resetRenderer failed: %s", e.getMessage());
    }
  }

  private static synchronized void forceRebuildRenderer() {
    if (!HARDWARE_SUPPORTED) {
      return;
    }
    boolean desired = MetalRenderConfig.metalRenderEnabled();
    disableRenderer();
    if (desired) {
      enableRenderer();
    }
  }

  private static void scheduleAtlasUpload() {
    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null) {
      MetalLogger.warn("[MetalRender] Cannot schedule atlas upload: MinecraftClient unavailable");
      return;
    }

    client.execute(() -> {
      MetalWorldRenderer renderer = WORLD;
      if (renderer == null || !renderer.isReady()) {
        return;
      }

      boolean hasCapturedAtlas = CapturedAtlasRepository.get(BLOCKS_ATLAS_ID).isPresent();
      if (!hasCapturedAtlas) {
        MetalLogger.info("[MetalRender] Deferring initial atlas upload; capture not ready yet");
        return;
      }

      MetalLogger.info("[MetalRender] Scheduling initial atlas upload (captured atlas ready)");
      renderer.forceAtlasReupload();
    });
  }
}
