package com.metalrender.sodium.mixins.accessor;

import net.minecraft.client.texture.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GlTexture.class)
public interface GlTextureAccessor {
    @Invoker("getGlId")
    int metalrender$getGlId();
}
