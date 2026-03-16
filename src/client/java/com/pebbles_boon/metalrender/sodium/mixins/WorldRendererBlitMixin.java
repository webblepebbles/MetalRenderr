package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.FramePass;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(WorldRenderer.class)
public class WorldRendererBlitMixin {

  @Shadow private DefaultFramebufferSet framebufferSet;

  @Unique private int metalrender$blitFrameCount = 0;
  @Unique private int metalrender$tmpFbo = 0;


  @Inject(method = "renderMain", at = @At("HEAD"), require = 0)
  private void metalrender$doMetalRender(FrameGraphBuilder frameGraphBuilder,
                                         Frustum frustum, Matrix4f posMatrix,
                                         GpuBufferSlice fogBuffer,
                                         boolean renderBlockOutline,
                                         WorldRenderState state,
                                         RenderTickCounter tickCounter,
                                         Profiler profiler, CallbackInfo ci) {

    if (!MetalRenderClient.isEnabled())
      return;

    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.shouldRenderWithMetal())
      return;

    try {
      MinecraftClient client = MinecraftClient.getInstance();
      Camera camera = client.gameRenderer.getCamera();
      if (camera == null || camera.getCameraPos() == null)
        return;

      float tickDelta = tickCounter.getTickProgress(true);

      float fov = client.options.getFov().getValue().floatValue();
      Matrix4f proj = client.gameRenderer.getBasicProjectionMatrix(fov);

      Matrix4f mv = new Matrix4f();
      mv.rotateX((float)Math.toRadians(camera.getPitch()));
      mv.rotateY((float)Math.toRadians(camera.getYaw() + 180.0f));

      CapturedMatrices.capture(proj, mv, camera.getCameraPos().x,
                               camera.getCameraPos().y,
                               camera.getCameraPos().z);

      worldRenderer.beginFrame(camera, tickDelta, new Matrix4f(proj),
                               new Matrix4f(mv));
      worldRenderer.endFrame();
    } catch (Exception e) {
      MetalLogger.error("[WorldRendererBlitMixin] Metal render failed: %s",
                        e.getMessage());
    }
  }


  @Inject(method = "renderParticles", at = @At("HEAD"), require = 0)
  private void metalrender$addBlitPass(FrameGraphBuilder frameGraphBuilder,
                                       GpuBufferSlice fogBuffer,
                                       CallbackInfo ci) {

    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.shouldRenderWithMetal())
      return;

    FramePass pass = frameGraphBuilder.createPass("metalrender_blit");
    this.framebufferSet.mainFramebuffer =
        pass.transfer(this.framebufferSet.mainFramebuffer);

    pass.setRenderer(() -> {
      net.minecraft.client.gl.Framebuffer mainFb =
          MinecraftClient.getInstance().getFramebuffer();
      int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      boolean boundColorFbo = false;
      boolean didDepth = false;

      MinecraftClient client = MinecraftClient.getInstance();
      int fbWidth = client.getWindow().getFramebufferWidth();
      int fbHeight = client.getWindow().getFramebufferHeight();


      if (mainFb != null && mainFb.getColorAttachment() instanceof
                                GlTexture glTex) {
        int texId = glTex.getGlId();

        if (metalrender$tmpFbo == 0) {
          metalrender$tmpFbo = GL30.glGenFramebuffers();
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, metalrender$tmpFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
                                    GL30.GL_COLOR_ATTACHMENT0,
                                    GL11.GL_TEXTURE_2D, texId, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
          boundColorFbo = true;
        } else {
          GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        }
      }

      worldRenderer.forceBlitNow();

      if (boundColorFbo) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
      }






      if (fbWidth > 0 && fbHeight > 0) {
        worldRenderer.forceBlitDepthNow(fbWidth, fbHeight);
        didDepth = true;
      }

      metalrender$blitFrameCount++;
      if (metalrender$blitFrameCount <= 3 ||
          metalrender$blitFrameCount % 600 == 0) {
        MetalLogger.info(
            "[WorldRendererBlitMixin] Blit pass (frame %d, color=%s, depth=%s, "
                + "drawFBO=%d, %dx%d)",
            metalrender$blitFrameCount, boundColorFbo, didDepth, prevDrawFbo,
            fbWidth, fbHeight);
      }
    });
  }
}
