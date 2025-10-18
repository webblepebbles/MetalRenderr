package com.metalrender.debug;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

public final class ConfigDumpCommand {
  public static void register(
      CommandDispatcher<FabricClientCommandSource> dispatcher) {
    dispatcher.register(
        ClientCommandManager.literal("metalrender")
            .then(ClientCommandManager.literal("config").executes(
                ConfigDumpCommand::dumpConfig)));
  }

  private static int dumpConfig(CommandContext<FabricClientCommandSource> ctx) {
    ctx.getSource().sendFeedback(
        Text.literal("§6[MetalRender] Current Configuration:"));
    ctx.getSource().sendFeedback(
        Text.literal("§7  MetalRender Enabled: §f" +
                     MetalRenderConfig.metalRenderEnabled()));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Mesh Shaders: §f" + MetalRenderConfig.meshShadersEnabled()));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Dynamic Quality: §f" + MetalRenderConfig.dynamicQuality()));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  LOD System: §f" + MetalRenderConfig.distanceLodEnabled()));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Occlusion Culling: §f" + MetalRenderConfig.occlusionCulling()));
    ctx.getSource().sendFeedback(
        Text.literal("§7  Aggressive Frustum: §f" +
                     MetalRenderConfig.aggressiveFrustumCulling()));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Temporal AA: §f" + MetalRenderConfig.temporalAAEnabled()));
    ctx.getSource().sendFeedback(Text.literal(
        "§7  Target Frame Time: §f" +
        String.format("%.1fms", MetalRenderConfig.dqTargetFrameMs())));

    MetalLogger.info("[Config Dump] MetalRender=%s, MeshShaders=%s, "
                         + "DynQuality=%s, LOD=%s, Occlusion=%s",
                     MetalRenderConfig.metalRenderEnabled(),
                     MetalRenderConfig.meshShadersEnabled(),
                     MetalRenderConfig.dynamicQuality(),
                     MetalRenderConfig.distanceLodEnabled(),
                     MetalRenderConfig.occlusionCulling());

    return 1;
  }
}
