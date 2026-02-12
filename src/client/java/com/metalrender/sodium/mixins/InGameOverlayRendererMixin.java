package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.util.MetalLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameOverlayRenderer.class)
public class InGameOverlayRendererMixin {

    private static int frameCount = 0;
    private static final Identifier FIRE_0 = Identifier.ofVanilla("block/fire_0");
    private static final Identifier FIRE_1 = Identifier.ofVanilla("block/fire_1");

    @Inject(method = "renderOverlays", at = @At("HEAD"), cancellable = true)
    private static void metalrender$beforeRenderOverlays(
            boolean firstPerson, float tickDelta,
            OrderedRenderCommandQueue renderCommandQueue,
            CallbackInfo ci) {
    }
}
