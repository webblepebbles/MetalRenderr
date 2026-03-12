package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.gui.MetalGuiRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.GlTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

  @Shadow @Final private Camera camera;

  @Unique private int metalrender$captureCount = 0;

  @Unique private int metalrender$tmpFbo = 0;

  @Inject(method = "renderWorld",
          at = @At(value = "INVOKE",
                   target = "Lnet/minecraft/client/render/"
                            +
                            "GameRenderer;renderHand(FZLorg/joml/Matrix4f;)V"))
  private void
  metalrender$captureAndRenderMetal(RenderTickCounter tickCounter,
                                    CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled())
      return;

    GameRenderer self = (GameRenderer)(Object)this;
    try {
      Camera cam = this.camera;
      if (cam == null || cam.getCameraPos() == null)
        return;

      float tickDelta = tickCounter.getTickProgress(true);

      float fov = net.minecraft.client.MinecraftClient.getInstance()
                      .options.getFov()
                      .getValue()
                      .floatValue();
      Matrix4f proj = self.getBasicProjectionMatrix(fov);

      Matrix4f mv = new Matrix4f();
      mv.rotateX((float)Math.toRadians(cam.getPitch()));
      mv.rotateY((float)Math.toRadians(cam.getYaw() + 180.0f));

      CapturedMatrices.capture(proj, mv, cam.getCameraPos().x,
                               cam.getCameraPos().y, cam.getCameraPos().z);

      metalrender$captureCount++;
      if (metalrender$captureCount <= 3 ||
          metalrender$captureCount % 600 == 0) {
        MetalLogger.info("[GameRendererMixin] Captured: fov=%.1f pitch=%.1f "
                             + "yaw=%.1f cam=(%.1f,%.1f,%.1f)",
                         fov, cam.getPitch(), cam.getYaw(),
                         cam.getCameraPos().x, cam.getCameraPos().y,
                         cam.getCameraPos().z);
      }

      MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
      if (worldRenderer != null && worldRenderer.shouldRenderWithMetal()) {
        worldRenderer.beginFrame(cam, tickDelta, new Matrix4f(proj),
                                 new Matrix4f(mv));
        worldRenderer.endFrame();

        metalrender$blitToMcFramebuffer(worldRenderer);
      }
    } catch (Exception e) {
      MetalLogger.error("[GameRendererMixin] capture/render failed", e);
    }
  }

  @Unique
  private void
  metalrender$blitToMcFramebuffer(MetalWorldRenderer worldRenderer) {
    Framebuffer mainFb = MinecraftClient.getInstance().getFramebuffer();
    if (mainFb == null || mainFb.getColorAttachment() == null) {

      worldRenderer.forceBlitNow();
      return;
    }

    try {

      if (mainFb.getColorAttachment() instanceof GlTexture glTex) {
        int texId = glTex.getGlId();

        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        if (metalrender$tmpFbo == 0) {
          metalrender$tmpFbo = GL30.glGenFramebuffers();
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, metalrender$tmpFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
                                    GL30.GL_COLOR_ATTACHMENT0,
                                    GL11.GL_TEXTURE_2D, texId, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
          if (metalrender$captureCount <= 5 ||
              metalrender$captureCount % 600 == 0) {
            MetalLogger.info("[GameRendererMixin] Blitting to MC "
                                 + "framebuffer: texId=%d, tmpFbo=%d",
                             texId, metalrender$tmpFbo);
          }

          worldRenderer.forceBlitNow();
        } else {
          MetalLogger.error(
              "[GameRendererMixin] MC framebuffer FBO incomplete: status=0x%X",
              status);

          GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
          worldRenderer.forceBlitNow();
        }

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
      } else {
        if (metalrender$captureCount <= 5) {
          MetalLogger.warn(
              "[GameRendererMixin] Color attachment is not GlTexture: %s",
              mainFb.getColorAttachment().getClass().getName());
        }
        worldRenderer.forceBlitNow();
      }
    } catch (Exception e) {
      MetalLogger.error("[GameRendererMixin] blitToMcFramebuffer failed", e);
      worldRenderer.forceBlitNow();
    }
  }
}
