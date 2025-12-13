package com.metalrender.sodium.mixins.accessor;

import java.util.Map;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteAtlasTexture.class)
public interface SpriteAtlasTextureAccessor {
  @Accessor("sprites")
  Map<Identifier, Sprite> metalrender$getSprites();

  @Accessor("width")
  int metalrender$getAtlasWidth();

  @Accessor("height")
  int metalrender$getAtlasHeight();
}
