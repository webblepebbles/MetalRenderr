package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.atlas.CapturedAtlasRepository;
import com.metalrender.render.atlas.SpriteAtlasCapture;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteAtlasTexture.class)
public abstract class SpriteAtlasTextureMixin {
  private static final Identifier BLOCKS_ATLAS_ID = Identifier.of("minecraft",
      "textures/atlas/blocks.png");

  @Shadow
  public abstract Identifier getId();

  @Inject(method = "upload", at = @At("TAIL"))
  private void metalrender$captureAtlas(SpriteLoader.StitchResult stitchResult,
      CallbackInfo ci) {
    Identifier atlasId = this.getId();
    if (!BLOCKS_ATLAS_ID.equals(atlasId)) {
      return;
    }

    SpriteAtlasTexture self = (SpriteAtlasTexture) (Object) this;
    SpriteAtlasCapture.capture(self).ifPresent(atlas -> {
      CapturedAtlasRepository.store(atlasId, atlas);
      MetalLogger.info("[AtlasCapture] Captured blocks atlas (%dx%d)", 
          atlas.width(), atlas.height());
      triggerAtlasUpload(atlasId);
    });
  }

  private static void triggerAtlasUpload(Identifier atlasId) {
    if (atlasId == null || !atlasId.equals(BLOCKS_ATLAS_ID)) {
      return;
    }

    MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
    if (renderer == null || !renderer.isReady()) {
      return;
    }

    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null) {
      return;
    }

    client.execute(renderer::forceAtlasReupload);
  }
}
