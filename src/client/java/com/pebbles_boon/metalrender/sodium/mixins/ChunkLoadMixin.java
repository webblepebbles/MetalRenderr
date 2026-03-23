package com.pebbles_boon.metalrender.sodium.mixins;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;
import java.util.function.Consumer;
@Mixin(ClientChunkManager.class)
public class ChunkLoadMixin {
    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"), require = 0)
    private void metalrender$onChunkLoaded(int x, int z, PacketByteBuf buf,
            Map<?, ?> blockEntityTags, Consumer<?> lightUpdateConsumer,
            CallbackInfoReturnable<WorldChunk> cir) {
        if (!MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
        if (wr == null || !wr.isReady())
            return;
        WorldChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            wr.onChunkLoaded(x, z, chunk);
        }
    }
}
