package com.metalrender.sodium.mixins.accessor;

import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {
    @Invoker("getGlTexture")
    GpuTexture metalrender$getGlTexture();
}
