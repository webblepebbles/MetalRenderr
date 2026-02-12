package com.metalrender.commands;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.config.MetalRenderConfigManager;
import com.metalrender.performance.RenderDistanceManager;
import com.metalrender.performance.RenderingMetrics;
import com.metalrender.render.MetalWorldRenderer;
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
            .then(literal("uploadAtlas").executes(MetalRenderCommand::uploadAtlas))
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
            .then(literal("preset")
                .then(literal("low").executes(ctx -> applyPreset(ctx, MetalRenderConfig.Preset.LOW)))
                .then(literal("medium").executes(ctx -> applyPreset(ctx, MetalRenderConfig.Preset.MEDIUM)))
                .then(literal("high").executes(ctx -> applyPreset(ctx, MetalRenderConfig.Preset.HIGH)))
                .then(literal("ultra").executes(ctx -> applyPreset(ctx, MetalRenderConfig.Preset.ULTRA))))
            .then(literal("autodistance")
                .then(literal("enable").executes(MetalRenderCommand::autoDistanceEnable))
                .then(literal("disable").executes(MetalRenderCommand::autoDistanceDisable))
                .then(literal("target")
                    .then(argument("fps", IntegerArgumentType.integer(30, 240))
                        .executes(MetalRenderCommand::autoDistanceSetTarget)))
                .then(literal("status").executes(MetalRenderCommand::autoDistanceStatus)))
            .then(literal("culling")
                .then(literal("normal").executes(ctx -> setCullingLevel(ctx, 0)))
                .then(literal("aggressive").executes(ctx -> setCullingLevel(ctx, 1)))
                .then(literal("ultra").executes(ctx -> setCullingLevel(ctx, 2)))
                .then(literal("status").executes(MetalRenderCommand::cullingStatus)))
            .then(literal("lodlevels")
                .then(literal("status").executes(MetalRenderCommand::lodLevelsStatus))
                .then(literal("set")
                    .then(argument("level", IntegerArgumentType.integer(0, 4))
                        .then(argument("distance", IntegerArgumentType.integer(16, 1024))
                            .executes(MetalRenderCommand::lodLevelSetDistance)))))
            .then(literal("defrag").executes(MetalRenderCommand::defragMemory))
            .then(literal("help").executes(MetalRenderCommand::showHelp)));
  }

  private static int cacheClear(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MetalRenderClient.resetRenderer();

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

  private static int lodSetThreshold(CommandContext<FabricClientCommandSource> ctx) {
    int distance = IntegerArgumentType.getInteger(ctx, "distance");
    MetalRenderConfig.setLodDistanceThreshold(distance);
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(
        Text.literal("§a✓ LOD threshold set to " + distance + " chunks"));
    ctx.getSource().sendFeedback(
        Text.literal("§7Note: LOD takes effect immediately for existing chunks."));
    ctx.getSource().sendFeedback(
        Text.literal("§7Use §f/metalrender reload§7 if chunks don't update."));
    return 1;
  }

  private static int reloadWorld(CommandContext<FabricClientCommandSource> ctx) {
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

  private static int restartRenderer(CommandContext<FabricClientCommandSource> ctx) {
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
      ctx.getSource().sendFeedback(Text.literal(
          String.format("§7LOD Ranges: §f%d / %d / %d / %d / %d blocks",
              MetalRenderConfig.lodLevel0Distance(),
              MetalRenderConfig.lodLevel1Distance(),
              MetalRenderConfig.lodLevel2Distance(),
              MetalRenderConfig.lodLevel3Distance(),
              MetalRenderConfig.lodLevel4Distance())));
    }
    String[] cullingNames = { "Normal", "Aggressive", "Ultra" };
    ctx.getSource().sendFeedback(Text.literal(
        "§7Block Culling: §f" + cullingNames[MetalRenderConfig.blockCullingLevel()]));

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

  private static int configReload(CommandContext<FabricClientCommandSource> ctx) {
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

  private static int configReset(CommandContext<FabricClientCommandSource> ctx) {
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
      MetalRenderConfig.setLodLevel0Distance(64);
      MetalRenderConfig.setLodLevel1Distance(128);
      MetalRenderConfig.setLodLevel2Distance(256);
      MetalRenderConfig.setLodLevel3Distance(384);
      MetalRenderConfig.setLodLevel4Distance(512);
      MetalRenderConfig.setLodLevel1Scale(0.50F);
      MetalRenderConfig.setLodLevel2Scale(0.25F);
      MetalRenderConfig.setLodLevel3Scale(0.125F);
      MetalRenderConfig.setLodLevel4Scale(0.0625F);
      MetalRenderConfig.setBlockCullingLevel(0);

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

  private static int performanceReset(CommandContext<FabricClientCommandSource> ctx) {
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

  private static int applyPreset(CommandContext<FabricClientCommandSource> ctx, MetalRenderConfig.Preset preset) {
    try {
      MetalRenderConfig.applyPreset(preset);
      MetalRenderConfigManager.syncFromRuntime(true);
      RenderDistanceManager rdm = RenderDistanceManager.getInstance();
      rdm.setEnabled(MetalRenderConfig.autoRenderDistanceEnabled());
      rdm.setTargetFps(MetalRenderConfig.autoRenderDistanceTargetFps());

      ctx.getSource().sendFeedback(
          Text.literal("§aApplied §e" + preset.name() + "§a preset"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("Failed to apply preset: " + e.getMessage()));
      return 0;
    }
  }

  private static int autoDistanceEnable(CommandContext<FabricClientCommandSource> ctx) {
    MetalRenderConfig.setAutoRenderDistanceEnabled(true);
    RenderDistanceManager rdm = RenderDistanceManager.getInstance();
    rdm.setEnabled(true);
    ctx.getSource().sendFeedback(
        Text.literal("§aAuto render distance §eenabled§a (target: " +
            String.format("%.0f", rdm.getTargetFps()) + " FPS)"));
    return 1;
  }

  private static int autoDistanceDisable(CommandContext<FabricClientCommandSource> ctx) {
    MetalRenderConfig.setAutoRenderDistanceEnabled(false);
    RenderDistanceManager.getInstance().setEnabled(false);
    ctx.getSource().sendFeedback(
        Text.literal("§aAuto render distance §cdisabled"));
    return 1;
  }

  private static int autoDistanceSetTarget(CommandContext<FabricClientCommandSource> ctx) {
    int fps = IntegerArgumentType.getInteger(ctx, "fps");
    MetalRenderConfig.setAutoRenderDistanceTargetFps(fps);
    RenderDistanceManager rdm = RenderDistanceManager.getInstance();
    rdm.setTargetFps(fps);
    ctx.getSource().sendFeedback(
        Text.literal("§aAuto render distance target set to §e" + fps + " FPS"));
    return 1;
  }

  private static int autoDistanceStatus(CommandContext<FabricClientCommandSource> ctx) {
    RenderDistanceManager rdm = RenderDistanceManager.getInstance();
    ctx.getSource().sendFeedback(
        Text.literal("§e§l━━━ Auto Render Distance ━━━"));
    ctx.getSource().sendFeedback(
        Text.literal("§6Enabled: §f" + rdm.isEnabled()));
    ctx.getSource().sendFeedback(
        Text.literal("§6Target FPS: §f" + String.format("%.0f", rdm.getTargetFps())));
    ctx.getSource().sendFeedback(
        Text.literal("§6Current FPS: §f" + String.format("%.1f", rdm.getSmoothedFps())));
    ctx.getSource().sendFeedback(
        Text.literal("§6Current Distance: §f" + rdm.getCurrentRenderDistance() + " chunks"));
    return 1;
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
    ctx.getSource().sendFeedback(
        Text.literal("§6/metalrender uploadAtlas §7- Upload block atlas to Metal"));
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
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lPresets:"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender preset <low|medium|high|ultra> §7- Apply preset"));
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lAuto Render Distance:"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender autodistance enable §7- Enable auto distance"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender autodistance disable §7- Disable auto distance"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender autodistance target <fps> §7- Set target FPS"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender autodistance status §7- Show status"));
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lBlock Culling:"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender culling normal §7- Standard culling distances"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender culling aggressive §7- Tighter culling"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender culling ultra §7- Maximum culling"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender culling status §7- Show culling distances"));
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lLOD Levels:"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender lodlevels status §7- Show LOD level distances"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender lodlevels set <0-4> <distance> §7- Set level distance"));
    ctx.getSource().sendFeedback(Text.literal(""));
    ctx.getSource().sendFeedback(Text.literal("§e§lMemory:"));
    ctx.getSource().sendFeedback(Text.literal(
        "§6/metalrender defrag §7- Defragment GPU buffer"));
    return 1;
  }

  private static int setCullingLevel(CommandContext<FabricClientCommandSource> ctx, int level) {
    MetalRenderConfig.setBlockCullingLevel(level);
    MetalRenderConfigManager.syncFromRuntime(true);
    String[] names = { "Normal", "Aggressive", "Ultra" };
    ctx.getSource().sendFeedback(
        Text.literal("§a✓ Block culling set to §e" + names[level]));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7  Decorative: %.0f  Functional: %.0f  Structural: %.0f",
            MetalRenderConfig.blockCullingDecorativeDistance(),
            MetalRenderConfig.blockCullingFunctionalDistance(),
            MetalRenderConfig.blockCullingStructuralDistance())));
    return 1;
  }

  private static int cullingStatus(CommandContext<FabricClientCommandSource> ctx) {
    String[] names = { "Normal", "Aggressive", "Ultra" };
    int level = MetalRenderConfig.blockCullingLevel();
    ctx.getSource().sendFeedback(
        Text.literal("§e§l━━━ Block Culling ━━━"));
    ctx.getSource().sendFeedback(
        Text.literal("§7Level: §e" + names[level] + " §7(" + level + ")"));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7Decorative: §f%.0f blocks",
            MetalRenderConfig.blockCullingDecorativeDistance())));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7Functional: §f%.0f blocks",
            MetalRenderConfig.blockCullingFunctionalDistance())));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7Structural: §f%.0f blocks",
            MetalRenderConfig.blockCullingStructuralDistance())));
    return 1;
  }

  private static int lodLevelsStatus(CommandContext<FabricClientCommandSource> ctx) {
    ctx.getSource().sendFeedback(
        Text.literal("§e§l━━━ LOD Levels ━━━"));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7L0 (Full):    §f0 - %d blocks  §7(100%%)",
            MetalRenderConfig.lodLevel0Distance())));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7L1 (½ quads): §f%d - %d blocks  §7(%.0f%%)",
            MetalRenderConfig.lodLevel0Distance(),
            MetalRenderConfig.lodLevel1Distance(),
            MetalRenderConfig.lodLevel1Scale() * 100)));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7L2 (¼ quads): §f%d - %d blocks  §7(%.0f%%)",
            MetalRenderConfig.lodLevel1Distance(),
            MetalRenderConfig.lodLevel2Distance(),
            MetalRenderConfig.lodLevel2Scale() * 100)));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7L3 (⅛ quads): §f%d - %d blocks  §7(%.1f%%)",
            MetalRenderConfig.lodLevel2Distance(),
            MetalRenderConfig.lodLevel3Distance(),
            MetalRenderConfig.lodLevel3Scale() * 100)));
    ctx.getSource().sendFeedback(
        Text.literal(String.format("§7L4 (1/16):    §f%d - %d blocks  §7(%.2f%%)",
            MetalRenderConfig.lodLevel3Distance(),
            MetalRenderConfig.lodLevel4Distance(),
            MetalRenderConfig.lodLevel4Scale() * 100)));
    return 1;
  }

  private static int lodLevelSetDistance(CommandContext<FabricClientCommandSource> ctx) {
    int level = IntegerArgumentType.getInteger(ctx, "level");
    int distance = IntegerArgumentType.getInteger(ctx, "distance");
    switch (level) {
      case 0 -> MetalRenderConfig.setLodLevel0Distance(distance);
      case 1 -> MetalRenderConfig.setLodLevel1Distance(distance);
      case 2 -> MetalRenderConfig.setLodLevel2Distance(distance);
      case 3 -> MetalRenderConfig.setLodLevel3Distance(distance);
      case 4 -> MetalRenderConfig.setLodLevel4Distance(distance);
    }
    MetalRenderConfigManager.syncFromRuntime(true);
    ctx.getSource().sendFeedback(
        Text.literal("§a✓ LOD level " + level + " distance set to " + distance + " blocks"));
    return 1;
  }

  private static int defragMemory(CommandContext<FabricClientCommandSource> ctx) {
    try {
      ctx.getSource().sendFeedback(
          Text.literal("§7Defragmentation: No active sparse buffer at this time"));
      ctx.getSource().sendFeedback(
          Text.literal("§7(Sparse buffer will be used once extended render distance is active)"));
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(
          Text.literal("§c✗ Defragmentation failed: " + e.getMessage()));
      return 0;
    }
  }

  private static int uploadAtlas(CommandContext<FabricClientCommandSource> ctx) {
    try {
      MetalWorldRenderer world = MetalRenderClient.getWorldRenderer();
      if (world == null) {
        ctx.getSource().sendError(Text.literal("No world renderer available"));
        return 0;
      }
      net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
        try {
          boolean ok = world.uploadAtlas();
          if (ok) {
            ctx.getSource().sendFeedback(Text.literal("Uploaded atlas to Metal backend"));
          } else {
            ctx.getSource().sendError(Text.literal("Failed to upload atlas: atlas not found or not ready"));
          }
        } catch (Throwable t) {
          ctx.getSource().sendError(Text.literal("Failed to upload atlas: " + t.getMessage()));
        }
      });
      return 1;
    } catch (Exception e) {
      ctx.getSource().sendError(Text.literal("Failed to upload atlas: " + e.getMessage()));
      return 0;
    }
  }
}
