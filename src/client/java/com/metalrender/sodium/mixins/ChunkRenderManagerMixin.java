package com.metalrender.sodium.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(
    targets =
        {"net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
         "me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager"})
public class ChunkRenderManagerMixin {
  // This mixin is kept for potential future use but currently unused
  // since BuilderTaskOutputMixin directly intercepts chunk builds
}
