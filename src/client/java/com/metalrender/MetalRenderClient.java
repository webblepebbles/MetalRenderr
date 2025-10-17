package com.metalrender;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.MetalHardwareChecker;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;

public final class MetalRenderClient implements ClientModInitializer {
   private static volatile boolean ENABLED = false;
   private static volatile MetalWorldRenderer WORLD;
   private static volatile MeshShaderBackend MESH_BACKEND;

   public void onInitializeClient() {
      MetalRenderConfig.loadFromSystemProperties();
      ENABLED = MetalHardwareChecker.isCompatible();
      if (ENABLED) {
         WORLD = new MetalWorldRenderer();
         MESH_BACKEND = new MeshShaderBackend();
         MetalLogger.info("MetalRender initialized (Sodium integration active)");
      } else {
         MetalLogger.warn("MetalRender disabled due to incompatible hardware");
      }

   }

   public static boolean isEnabled() {
      return ENABLED;
   }

   public static MetalWorldRenderer getWorldRenderer() {
      return WORLD;
   }

   public static MeshShaderBackend getMeshBackend() {
      return MESH_BACKEND;
   }
}
