package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.text.MetalTextRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.ItemGuiElementRenderState;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {
    static {
        System.out.println("[Metal] GuiRenderStateMixin CLASS LOADED");
    }

    private static int deferredTextCount = 0;
    private static long lastLogTime = 0;

    
    @Inject(method = "addText", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptAddText(TextGuiElementRenderState textState, CallbackInfo ci) {
    }
    private static int deferredItemCount = 0;
    private static long lastItemLogTime = 0;

    
    @Inject(method = "addItem", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptAddItem(ItemGuiElementRenderState itemState, CallbackInfo ci) {
    }
}
