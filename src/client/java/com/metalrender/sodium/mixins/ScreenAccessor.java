package com.metalrender.sodium.mixins;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Screen.class)
public interface ScreenAccessor {
  @Invoker("addDrawableChild")
  <T extends Element> T metalrender$addDrawableChild(T widget);
}
