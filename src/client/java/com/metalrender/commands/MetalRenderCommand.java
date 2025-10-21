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
            .then(literal("help").executes(MetalRenderCommand::showHelp)));
  }

  private static int cacheClear(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MetalWorldRenderer world = MetalRenderClient.getWorldRenderer();
      MeshShaderBackend backend = MetalRenderClient.getMeshBackend();

      if (world != null) {
        world.destroy();
      }
      if (backend != null) {
        backend.destroy();
      }

      MetalRenderClient.refreshEnabledState();

      ctx.getSource().sendFeedback(
          Text.literal("MetalRender cache cleared and renderer restarted"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("Failed to clear cache: " + e.getMessage()));
      return 0;
    }
  }

  private static int lodReset(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MetalRenderConfig.setLodDistanceThreshold(8);
      MetalRenderConfig.setLodFarDistance(16);
      MetalRenderConfig.setLodDistantScale(0.10F);
      MetalRenderConfig.setDistanceLodEnabled(true);

      MetalRenderConfigManager.syncFromRuntime(true);

      ctx.getSource().sendFeedback(
          Text.literal("§a✓ LOD settings reset to defaults"));
      ctx.getSource().sendFeedback(
          Text.literal("Near: 8 chunks, Far: 16 chunks, Scale: 0.10"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("Failed to reset LOD: " + e.getMessage()));
      return 0;
    }
  }

  private static int lodEnable(CommandContext<FabricClientCommandSource> ctx) {
    MetalRenderConfig.setDistanceLodEnabled(true);
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(Text.literal("§a✓ LOD system enabled"));
    return 1;
  }

  private static int lodDisable(CommandContext<FabricClientCommandSource> ctx) {
    MetalRenderConfig.setDistanceLodEnabled(false);
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(Text.literal("§a✓ LOD system disabled"));
    return 1;
  }

  private static int
  lodSetThreshold(CommandContext<FabricClientCommandSource> ctx) {
    int distance = IntegerArgumentType.getInteger(ctx, "distance");
    MetalRenderConfig.setLodDistanceThreshold(distance);
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(
        Text.literal("§a✓ LOD threshold set to " + distance + " chunks"));
    return 1;
  }

  private static int
  reloadWorld(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.world != null) {
        client.worldRenderer.reload();
        ctx.getSource().sendFeedback(
            Text.literal("§a✓ World renderer reloaded"));
      } else {
        ctx.getSource().sendError(Text.literal("No world is currently loaded"));
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
      MetalRenderConfig.setMetalRenderEnabled(false);
      MetalRenderClient.refreshEnabledState();

      Thread.sleep(100);

      MetalRenderConfig.setMetalRenderEnabled(true);
      MetalRenderClient.refreshEnabledState();

      if (MetalRenderClient.isEnabled()) {
        ctx.getSource().sendFeedback(
            Text.literal("§a✓ MetalRender restarted successfully"));
      } else {
        ctx.getSource().sendError(
            Text.literal("§c✗ MetalRender failed to restart"));
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
        Text.literal("§e§l━━━ MetalRender Status ━━━"));
    ctx.getSource().sendFeedback(
        Text.literal("§7Enabled: §f" +
                     (MetalRenderClient.isEnabled() ? "§a✓ Yes" : "§c✗ No")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7Hardware Support: §f" +
        (MetalRenderClient.isHardwareSupported() ? "§a✓ Yes" : "§c✗ No")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7Mesh Shaders: §f" + (MetalRenderConfig.meshShadersEnabled()
                                    ? "§a✓ Enabled"
                                    : "§c✗ Disabled")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7LOD System: §f" + (MetalRenderConfig.distanceLodEnabled()
                                  ? "§a✓ Enabled"
                                  : "§c✗ Disabled")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7Dynamic Quality: §f" +
        (MetalRenderConfig.dynamicQuality() ? "§a✓ Enabled" : "§c✗ Disabled")));
    ctx.getSource().sendFeedback(
        Text.literal("§7Temporal AA: §f" +
                     (MetalRenderConfig.temporalAAEnabled() ? "§a✓ Enabled"
                                                            : "§c✗ Disabled")));
    ctx.getSource().sendFeedback(Text.literal(
        "§7Resolution Scale: §f" +
        String.format("%.2f", MetalRenderConfig.resolutionScale())));

    if (MetalRenderConfig.distanceLodEnabled()) {
      ctx.getSource().sendFeedback(
          Text.literal("§7LOD Near: §f" +
                       MetalRenderConfig.lodDistanceThreshold() + " chunks"));
      ctx.getSource().sendFeedback(Text.literal(
          "§7LOD Far: §f" + MetalRenderConfig.lodFarDistance() + " chunks"));
      ctx.getSource().sendFeedback(Text.literal(
          String.format("§7LOD Distribution: §fL0: %d | L1: %d | L2: %d",
                        RenderingMetrics.getChunksAtLod0(),
                        RenderingMetrics.getChunksAtLod1(),
                        RenderingMetrics.getChunksAtLod2())));
    }

    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7GPU Load: §f%,d vertices in %d draws",
                                   RenderingMetrics.getTotalVertices(),
                                   RenderingMetrics.getTotalDrawCommands())));

    return 1;
  }

  private static int configSave(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MetalRenderConfigManager.syncFromRuntime(true);
      ctx.getSource().sendFeedback(
          Text.literal("§a✓ Configuration saved to disk"));
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
      MetalRenderConfigManager.load();
      ctx.getSource().sendFeedback(
          Text.literal("§a✓ Configuration reloaded from disk"));
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
      MetalRenderConfig.setMetalRenderEnabled(true);
      MetalRenderConfig.setMirrorUploads(true);
      MetalRenderConfig.setAggressiveFrustumCulling(true);
      MetalRenderConfig.setOcclusionCulling(true);
      MetalRenderConfig.setDynamicQuality(true);
      MetalRenderConfig.setMeshShadersEnabled(true);
      MetalRenderConfig.setTemporalAAEnabled(false);
      MetalRenderConfig.setDistanceLodEnabled(true);
      MetalRenderConfig.setResolutionScale(1.0F);
      MetalRenderConfig.setDqMinScale(0.5F);
      MetalRenderConfig.setDqMaxScale(1.0F);
      MetalRenderConfig.setDqScaleStep(0.05F);
      MetalRenderConfig.setDqTargetFrameMs(6.67F);
      MetalRenderConfig.setTemporalUpscaleTarget(1.5F);
      MetalRenderConfig.setTemporalBlendFactor(0.1F);
      MetalRenderConfig.setLodDistanceThreshold(8);
      MetalRenderConfig.setLodFarDistance(16);
      MetalRenderConfig.setLodDistantScale(0.10F);

      MetalRenderConfigManager.syncFromRuntime(true);

      ctx.getSource().sendFeedback(
          Text.literal("§a✓ All settings reset to defaults"));
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
      MetalRenderConfig.setDqMinScale(0.5F);
      MetalRenderConfig.setDqMaxScale(1.0F);
      MetalRenderConfig.setDqScaleStep(0.05F);
      MetalRenderConfig.setDqTargetFrameMs(6.67F);
      MetalRenderConfig.setResolutionScale(1.0F);

      MetalRenderConfigManager.syncFromRuntime(true);

      ctx.getSource().sendFeedback(
          Text.literal("§a✓ Performance settings reset"));
      ctx.getSource().sendFeedback(
          Text.literal("§7  Resolution: 1.0x, Target: 150 FPS"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Failed to reset performance: " + e.getMessage()));
      return 0;
    }
  }

  private static int showHelp(CommandContext<FabricClientCommandSource> ctx) {
    ctx.getSource().sendFeedback(
        Text.literal("§e§l━━━ MetalRender Commands ━━━"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender status §7- Show renderer status"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender cache clear §7- Clear cache & restart"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender reload §7- Reload world renderer"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender restart §7- Restart MetalRender"));
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lLOD Commands:"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender lod reset §7- Reset LOD to defaults"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender lod enable §7- Enable LOD system"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender lod disable §7- Disable LOD system"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender lod threshold <distance> §7- Set threshold"));
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lConfig Commands:"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender config save §7- Save config to disk"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender config reload §7- Reload from disk"));
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender config reset §7- Reset all settings"));
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lPerformance:"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender performance reset §7- Reset perf settings"));
    return 1;
  }
}
