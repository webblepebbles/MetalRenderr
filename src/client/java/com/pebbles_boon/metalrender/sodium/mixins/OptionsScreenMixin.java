package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

  protected OptionsScreenMixin() { super(null); }

  @Inject(method = "method_19828", at = @At("HEAD"), cancellable = true,
          require = 0)
  private void
  metalrender$openVideoSettings(CallbackInfoReturnable<Screen> cir) {
    cir.setReturnValue(new MetalRenderSettingsScreen((Screen)(Object)this));
  }
}
