package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class FramebufferBlitMixin {

    @Unique
    private static int metalrender$blitFbo = -1;

    @Unique
    private static int metalrender$frameCount = 0;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void metalrender$beforeGuiRender(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null)
            return;

        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null || !renderer.isReady()) {
            if (metalrender$frameCount <= 5) {
                MetalLogger.warn("[FramebufferBlitMixin] Renderer not ready: renderer=%s, ready=%s",
                        renderer != null ? "exists" : "null",
                        renderer != null ? renderer.isReady() : false);
            }
            return;
        }

        if (!renderer.hasRenderedThisFrame()) {
            if (metalrender$frameCount <= 10) {
                MetalLogger.warn("[FramebufferBlitMixin] No terrain rendered this frame, skipping blit");
            }
            return;
        }

        metalrender$frameCount++;
        Framebuffer mainFb = client.getFramebuffer();
        GpuTexture colorAttachment = mainFb.getColorAttachment();

        if (!(colorAttachment instanceof GlTexture glTexture)) {
            if (metalrender$frameCount <= 5) {
                MetalLogger.warn("[FramebufferBlitMixin] colorAttachment is not GlTexture: %s",
                        colorAttachment != null ? colorAttachment.getClass().getName() : "null");
            }
            return;
        }

        int texId = glTexture.getGlId();
        int texW = colorAttachment.getWidth(0);
        int texH = colorAttachment.getHeight(0);
        if (metalrender$blitFbo < 0) {
            metalrender$blitFbo = GL30.glGenFramebuffers();
            MetalLogger.info("[FramebufferBlitMixin] Created temp FBO %d for Metal terrain blit", metalrender$blitFbo);
        }
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, metalrender$blitFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texId, 0);

        if (metalrender$frameCount <= 3) {
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                MetalLogger.warn("[FramebufferBlitMixin] Temp FBO incomplete! status=0x%X texId=%d",
                        status, texId);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
                GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
                return;
            }
            MetalLogger.info("[FramebufferBlitMixin] Temp FBO %d complete, texId=%d, size=%dx%d",
                    metalrender$blitFbo, texId, texW, texH);
        }
        GL11.glViewport(0, 0, texW, texH);
        renderer.forceBlitNow();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
    }
}
