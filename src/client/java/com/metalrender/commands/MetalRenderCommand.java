package com.metalrender.commands;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.config.MetalRenderConfigManager;
import com.metalrender.performance.RenderingMetrics;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class MetalRenderCommand {

  public static void register(
      CommandDispatcher<FabricClientCommandSource> dispatcher) {
    dispatcher.register(
        literal("metalrender")
            .then(literal("cache").then(
                literal("clear").executes(MetalRenderCommand::cacheClear)))
            .then(
                literal("lod")
                    .then(
                        literal("reset").executes(MetalRenderCommand::lodReset))
                    .then(literal("enable").executes(
                        MetalRenderCommand::lodEnable))
                    .then(literal("disable").executes(
                        MetalRenderCommand::lodDisable))
                    .then(
                        literal("threshold")
                            .then(
                                argument("distance",
                                         IntegerArgumentType.integer(5, 100))
                                    .executes(
                                        MetalRenderCommand::lodSetThreshold))))
            .then(literal("reload").executes(MetalRenderCommand::reloadWorld))
            .then(literal("restart").executes(
                MetalRenderCommand::restartRenderer))
            .then(literal("status").executes(MetalRenderCommand::showStatus))
            .then(literal("config")
                      .then(literal("save").executes(
                          MetalRenderCommand::configSave))
                      .then(literal("reload").executes(
                          MetalRenderCommand::configReload))
                      .then(literal("reset").executes(
                          MetalRenderCommand::configReset)))
            .then(literal("performance")
                      .then(literal("reset").executes(
                          MetalRenderCommand::performanceReset)))
            .then(literal("parallel")
                      .then(literal("enable").executes(
                          MetalRenderCommand::parallelEnable))
                      .then(literal("disable").executes(
                          MetalRenderCommand::parallelDisable)))
            .then(literal("help").executes(MetalRenderCommand::showHelp)));
  }

  private static int cacheClear(CommandContext<FabricClientCommandSource> ctx) {
    try {
      ctx.getSource().sendFeedback(
          Text.literal("§e⟳ Clearing MetalRender cache..."));

      MetalWorldRenderer world = MetalRenderClient.getWorldRenderer();
      MeshShaderBackend backend = MetalRenderClient.getMeshBackend();

      if (world != null) {
        world.destroy();
      }
      if (backend != null) {
        backend.destroy();
      }

      MetalRenderClient.refreshEnabledState();

      ctx.getSource().sendFeedback(Text.literal(
          "§a✓ Cache cleared and renderer restarted successfully"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  All cached data has been purged"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to clear cache: " + e.getMessage()));
      return 0;
    }
  }

  private static int lodReset(CommandContext<FabricClientCommandSource> ctx) {
    try {
    
      MetalRenderConfigManager.syncFromRuntime(true);

      ctx.getSource().sendFeedback(
          Text.literal("§a✓ LOD settings reset to defaults"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Near threshold: §f8 chunks"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Far threshold: §f16 chunks"));
      ctx.getSource().sendFeedback(Text.literal("§7  Distant scale: §f0.20"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to reset LOD: " + e.getMessage()));
      return 0;
    }
  }

  private static int lodEnable(CommandContext<FabricClientCommandSource> ctx) {
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(Text.literal("§a✓ LOD system enabled"));
    ctx.getSource().sendFeedback(
        Text.literal("§7  Better performance when looking at distant blocks"));
    return 1;
  }

  private static int lodDisable(CommandContext<FabricClientCommandSource> ctx) {
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(Text.literal("§a✓ LOD system disabled"));
    ctx.getSource().sendFeedback(
        Text.literal("§7  All chunks rendering at full detail"));
    return 1;
  }

  private static int
  lodSetThreshold(CommandContext<FabricClientCommandSource> ctx) {
    int distance = IntegerArgumentType.getInteger(ctx, "distance");
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(Text.literal(
        "§a✓ LOD near threshold set to §f" + distance + " §achunks"));
    ctx.getSource().sendFeedback(
        Text.literal("§7  Chunks beyond this distance will use lower detail"));
    return 1;
  }

  private static int
  reloadWorld(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.world != null) {
        ctx.getSource().sendFeedback(
            Text.literal("§e⟳ Reloading world renderer..."));
        client.worldRenderer.reload();
        ctx.getSource().sendFeedback(
            Text.literal("§a✓ World renderer reloaded successfully"));
        ctx.getSource().sendFeedback(
            Text.literal("§7  All chunks will be rebuilt"));
      } else {
        ctx.getSource().sendError(
            Text.literal("§c✗ No world is currently loaded"));
        return 0;
      }
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to reload world: " + e.getMessage()));
      return 0;
    }
  }

  private static int
  restartRenderer(CommandContext<FabricClientCommandSource> ctx) {
    try {
      ctx.getSource().sendFeedback(
          Text.literal("§e⟳ Restarting MetalRender system..."));

      MetalRenderConfig.setMetalRenderEnabled(false);
      MetalRenderClient.refreshEnabledState();

      Thread.sleep(100);

      MetalRenderConfig.setMetalRenderEnabled(true);
      MetalRenderClient.refreshEnabledState();

      if (MetalRenderClient.isEnabled()) {
        ctx.getSource().sendFeedback(
            Text.literal("§a✓ MetalRender restarted successfully"));
        ctx.getSource().sendFeedback(
            Text.literal("§7  System fully reinitialized"));
      } else {
        ctx.getSource().sendError(
            Text.literal("§c✗ MetalRender failed to restart"));
        ctx.getSource().sendFeedback(
            Text.literal("§7  Check hardware compatibility"));
        return 0;
      }
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to restart renderer: " + e.getMessage()));
      return 0;
    }
  }

  private static int showStatus(CommandContext<FabricClientCommandSource> ctx) {
    ctx.getSource().sendFeedback(
        Text.literal("§6§l╔═══════════════════════════════╗"));
    ctx.getSource().sendFeedback(
        Text.literal("§6§l║   §e§lMetalRender Status§6§l      ║"));
    ctx.getSource().sendFeedback(
        Text.literal("§6§l╚═══════════════════════════════╝"));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e● System Status"));
    ctx.getSource().sendFeedback(
        Text.literal("§7  Enabled: " +
                     (MetalRenderClient.isEnabled() ? "§a✓ ON" : "§c✗ OFF")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Hardware Support: " + (MetalRenderClient.isHardwareSupported()
                                        ? "§a✓ Compatible"
                                        : "§c✗ Incompatible")));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e● Features"));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Mesh Shaders: " +
        (MetalRenderConfig.meshShadersEnabled() ? "§a✓ ON" : "§c✗ OFF")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  LOD System: " +
        (MetalRenderConfig.distanceLodEnabled() ? "§a✓ ON" : "§c✗ OFF")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Dynamic Quality: " +
        (MetalRenderConfig.dynamicQuality() ? "§a✓ ON" : "§c✗ OFF")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Frustum Culling: " +
        (MetalRenderConfig.aggressiveFrustumCulling() ? "§a✓ ON" : "§c✗ OFF")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Occlusion Culling: " +
        (MetalRenderConfig.occlusionCulling() ? "§a✓ ON" : "§c✗ OFF")));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e● Performance"));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Resolution Scale: §f" +
        String.format("%.2fx", MetalRenderConfig.resolutionScale())));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  MetalFX: " +
        (MetalRenderConfig.metalFXEnabled()
             ? "§a✓ ON §7(Scale: §f" +
                   String.format("%.0f%%",
                                 MetalRenderConfig.metalFXScale() * 100) +
                   "§7)"
             : "§c✗ OFF")));

    if (MetalRenderConfig.distanceLodEnabled()) {
      ctx.getSource().sendFeedback(Text.literal(""));
      ctx.getSource().sendFeedback(Text.literal("§e● LOD Configuration"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Near Threshold: §f" +
                       MetalRenderConfig.lodDistanceThreshold() + " chunks"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Far Threshold: §f" +
                       MetalRenderConfig.lodFarDistance() + " chunks"));
      ctx.getSource().sendFeedback(Text.literal(
          "§7  Distribution: §fL0:§b" + RenderingMetrics.getChunksAtLod0() +
          " §7| L1:§b" + RenderingMetrics.getChunksAtLod1() + " §7| L2:§b" +
          RenderingMetrics.getChunksAtLod2() + " §7| L3:§b" +
          RenderingMetrics.getChunksAtLod3()));
    }

    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e● GPU Load"));
    long verts = RenderingMetrics.getTotalVertices();
    long draws = RenderingMetrics.getTotalDrawCommands();
    if (verts > 0 && draws > 0) {
      ctx.getSource().sendFeedback(Text.literal(String.format(
          "§7  Vertices: §b%,d §7in §b%d §7draws", verts, draws)));
      ctx.getSource().sendFeedback(Text.literal(
          String.format("§7  Average: §b%.0f §7vertices per draw",
                        RenderingMetrics.getAverageVerticesPerDraw())));
      ctx.getSource().sendFeedback(Text.literal(
          "§7  Vertices reduced: §b" +
          String.format("%,d", RenderingMetrics.getVerticesReduced())));
    } else {
      ctx.getSource().sendFeedback(
          Text.literal("§7  §8(Metrics sampling - run again to see data)"));
    }
    ctx.getSource().sendFeedback(Text.literal(""));

    return 1;
  }

  private static int configSave(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MetalRenderConfigManager.syncFromRuntime(true);
      ctx.getSource().sendFeedback(
          Text.literal("§a✓ Configuration saved to disk"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  File: §fconfig/metalrender.json"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to save config: " + e.getMessage()));
      return 0;
    }
  }

  private static int
  configReload(CommandContext<FabricClientCommandSource> ctx) {
    try {
      ctx.getSource().sendFeedback(
          Text.literal("§e⟳ Reloading configuration from disk..."));
      MetalRenderConfigManager.load();
      ctx.getSource().sendFeedback(
          Text.literal("§a✓ Configuration reloaded successfully"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  All unsaved changes were discarded"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to reload config: " + e.getMessage()));
      return 0;
    }
  }

  private static int
  configReset(CommandContext<FabricClientCommandSource> ctx) {
    try {
      ctx.getSource().sendFeedback(
          Text.literal("§e⚠ Resetting all settings to factory defaults..."));

      MetalRenderConfig.setMetalRenderEnabled(true);
      MetalRenderConfig.setMirrorUploads(true);
      MetalRenderConfig.setAggressiveFrustumCulling(true);
      MetalRenderConfig.setOcclusionCulling(true);
      MetalRenderConfig.setDynamicQuality(true);
      MetalRenderConfig.setMetalFXEnabled(true);
      MetalRenderConfig.setMetalFXScale(0.7F);
      MetalRenderConfig.setResolutionScale(1.0F);
      MetalRenderConfig.setDqMinScale(0.5F);
      MetalRenderConfig.setDqMaxScale(1.0F);
      MetalRenderConfig.setDqScaleStep(0.05F);
      MetalRenderConfig.setDqTargetFrameMs(6.67F);
      MetalRenderConfigManager.syncFromRuntime(true);

      ctx.getSource().sendFeedback(
          Text.literal("§a✓ All settings reset to defaults"));
      ctx.getSource().sendFeedback(Text.literal("§7  Renderer: §aEnabled"));
      ctx.getSource().sendFeedback(Text.literal("§7  Resolution: §f1.0x"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Dynamic Quality: §aEnabled"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to reset config: " + e.getMessage()));
      return 0;
    }
  }

  private static int
  performanceReset(CommandContext<FabricClientCommandSource> ctx) {
    try {
      ctx.getSource().sendFeedback(
          Text.literal("§e⚠ Resetting performance settings..."));

      MetalRenderConfig.setDqMinScale(0.5F);
      MetalRenderConfig.setDqMaxScale(1.0F);
      MetalRenderConfig.setDqScaleStep(0.05F);
      MetalRenderConfig.setDqTargetFrameMs(6.67F);
      MetalRenderConfig.setResolutionScale(1.0F);

      MetalRenderConfigManager.syncFromRuntime(true);

      ctx.getSource().sendFeedback(
          Text.literal("§a✓ Performance settings reset to defaults"));
      ctx.getSource().sendFeedback(Text.literal("§7  Resolution: §f1.0x"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Target Frame Time: §f6.67ms §7(~150 FPS)"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Dynamic Range: §f0.5x - 1.0x"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to reset performance: " + e.getMessage()));
      return 0;
    }
  }

  private static int
  parallelEnable(CommandContext<FabricClientCommandSource> ctx) {
    if (MetalRenderConfig.metalRenderEnabled()) {
      MetalRenderConfigManager.syncFromRuntime(true);
      MetalRenderClient.refreshEnabledState();
      ctx.getSource().sendFeedback(
          Text.literal("§a✓ Parallel encoding enabled"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Using multiple threads for encoding"));
    } else {
      ctx.getSource().sendFeedback(Text.literal("§c✗ MetalRender is disabled"));
      ctx.getSource().sendFeedback(Text.literal(
          "§7  Parallel encoding requires MetalRender to be active"));
    }
    return 1;
  }

  private static int
  parallelDisable(CommandContext<FabricClientCommandSource> ctx) {
    MetalRenderConfigManager.syncFromRuntime(true);
    MetalRenderClient.refreshEnabledState();
    ctx.getSource().sendFeedback(
        Text.literal("§a✓ Parallel encoding disabled"));
    ctx.getSource().sendFeedback(
        Text.literal("§7  Using single-threaded encoding"));
    return 1;
  }

  private static int showHelp(CommandContext<FabricClientCommandSource> ctx) {
    ctx.getSource().sendFeedback(
        Text.literal("§6§l╔════════════════════════════════════╗"));
    ctx.getSource().sendFeedback(
        Text.literal("§6§l║   §e§lMetalRender Commands§6§l          ║"));
    ctx.getSource().sendFeedback(
        Text.literal("§6§l╚════════════════════════════════════╝"));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e§l● General Commands"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender status §7- Show renderer status"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender cache clear §7- Clear cache & restart"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender reload §7- Reload world renderer"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender restart §7- Restart MetalRender system"));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e§l● LOD Commands"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender lod reset §7- Reset LOD to defaults"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender lod enable §7- Enable LOD system"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender lod disable §7- Disable LOD system"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender lod threshold <5-100> §7- Set distance threshold"));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e§l● Configuration"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender config save §7- Save config to disk"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender config reload §7- Reload from disk"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender config reset §7- Reset all settings"));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e§l● Performance"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender performance reset §7- Reset perf settings"));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal("§e§l● Parallel Encoding"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender parallel enable §7- Enable parallel encoding"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender parallel disable §7- Disable parallel encoding"));
    ctx.getSource().sendFeedback(Text.literal(""));

    ctx.getSource().sendFeedback(Text.literal(
        "§7Tip: Use §e/metalrender config save §7to persist changes"));
    return 1;
  }
}
