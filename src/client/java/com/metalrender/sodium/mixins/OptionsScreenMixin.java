package com.metalrender.sodium.mixins;

import com.metalrender.config.gui.MetalRenderConfigScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void metalrender$addSettingsButton(CallbackInfo ci) {
        int buttonX = this.width / 2 - 155;
        int buttonY = this.height - 27;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("\u00A7b\u2699 MetalRender"),
                btn -> {
                    if (this.client != null) {
                        this.client.setScreen(new MetalRenderConfigScreen((Screen) (Object) this));
                    }
                }).position(buttonX, buttonY).size(150, 20).build());
    }
}
