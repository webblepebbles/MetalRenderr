package com.metalrender.config.gui;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

public class MetalRenderConfigScreen extends Screen {
  private final Screen parent;
  private static final int BUTTON_WIDTH = 200;
  private static final int BUTTON_HEIGHT = 20;
  private static final int CATEGORY_SPACING = 10;
  private static final int OPTION_SPACING = 22;

  public MetalRenderConfigScreen(Screen parent) {
    super(Text.literal("§6MetalRender Configuration"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    int centerX = this.width / 2;
    int leftCol = centerX - BUTTON_WIDTH - 5;
    int rightCol = centerX + 5;
    int y = 50;

    y += CATEGORY_SPACING;
    this.addDrawableChild(
        CyclingButtonWidget
            .onOffBuilder(Text.literal("§aMetalRender Systems: ON"),
                          Text.literal("§cMetalRender Systems: OFF"))
            .initially(MetalRenderConfig.metalRenderEnabled())
            .build(
                centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Text.literal("MetalRender Systems"), (button, value) -> {
                  MetalRenderConfig.setMetalRenderEnabled(value);
                  if (!value) {
                    if (com.metalrender.MetalRenderClient.getWorldRenderer() !=
                        null) {
                      com.metalrender.MetalRenderClient.getWorldRenderer()
                          .destroy();
                      try {
                        java.lang.reflect.Field f =
                            com.metalrender.MetalRenderClient
                                .class.getDeclaredField("WORLD");
                        f.setAccessible(true);
                        f.set(null, null);
                      } catch (Exception e) {
                        e.printStackTrace();
                      }
                    }
                  }
                }));
    y += OPTION_SPACING;

    y += CATEGORY_SPACING;

    boolean masterEnabled =
        MetalRenderConfig.metalRenderEnabled() &&
        com.metalrender.MetalRenderClient.getWorldRenderer() != null;
    CyclingButtonWidget<Boolean> dynQualBtn =
        CyclingButtonWidget
            .onOffBuilder(Text.literal("§aDynamic Quality: ON"),
                          Text.literal("§cDynamic Quality: OFF"))
            .initially(MetalRenderConfig.dynamicQuality())
            .build(leftCol, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                   Text.literal("Dynamic Quality"), (button, value) -> {
                     if (masterEnabled)
                       MetalRenderConfig.setDynamicQuality(value);
                   });
    dynQualBtn.active = masterEnabled;
    this.addDrawableChild(dynQualBtn);

    ResolutionScaleSlider resSlider =
        new ResolutionScaleSlider(rightCol, y, BUTTON_WIDTH, BUTTON_HEIGHT);
    resSlider.active = masterEnabled;
    this.addDrawableChild(resSlider);

    y += OPTION_SPACING;

    CyclingButtonWidget<Boolean> metalFxBtn =
        CyclingButtonWidget
            .onOffBuilder(Text.literal("§aMetalFX Upscaling: ON"),
                          Text.literal("§cMetalFX Upscaling: OFF"))
            .initially(MetalRenderConfig.metalFXEnabled())
            .build(
                leftCol, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Text.literal("MetalFX Upscaling"), (button, value) -> {
                  if (masterEnabled) {
                    MetalRenderConfig.setMetalFXEnabled(value);
                    long handle = MetalRenderClient.getWorldRenderer() != null
                                      ? MetalRenderClient.getWorldRenderer()
                                            .getNativeHandle()
                                      : 0L;
                    if (handle != 0L && com.metalrender.nativebridge
                                            .NativeBridge.nSupportsMetalFX()) {
                      com.metalrender.nativebridge.NativeBridge
                          .nSetMetalFXEnabled(handle, value);
                    }
                  }
                });
    metalFxBtn.active = masterEnabled;
    this.addDrawableChild(metalFxBtn);

    MetalFXScaleSlider fxSlider =
        new MetalFXScaleSlider(rightCol, y, BUTTON_WIDTH, BUTTON_HEIGHT);
    fxSlider.active = masterEnabled;
    this.addDrawableChild(fxSlider);

    y += OPTION_SPACING;

    y += CATEGORY_SPACING;

    CyclingButtonWidget<Boolean> frustumBtn =
        CyclingButtonWidget
            .onOffBuilder(Text.literal("§aFrustum Culling: ON"),
                          Text.literal("§cFrustum Culling: OFF"))
            .initially(MetalRenderConfig.aggressiveFrustumCulling())
            .build(leftCol, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                   Text.literal("Frustum Culling"), (button, value) -> {
                     if (masterEnabled)
                       MetalRenderConfig.setAggressiveFrustumCulling(value);
                   });
    frustumBtn.active = masterEnabled;
    this.addDrawableChild(frustumBtn);

    CyclingButtonWidget<Boolean> occBtn =
        CyclingButtonWidget
            .onOffBuilder(Text.literal("§aOcclusion Culling: ON"),
                          Text.literal("§cOcclusion Culling: OFF"))
            .initially(MetalRenderConfig.occlusionCulling())
            .build(rightCol, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                   Text.literal("Occlusion Culling"), (button, value) -> {
                     if (masterEnabled)
                       MetalRenderConfig.setOcclusionCulling(value);
                   });
    occBtn.active = masterEnabled;
    this.addDrawableChild(occBtn);

    y += OPTION_SPACING;

    y += CATEGORY_SPACING;

    CyclingButtonWidget<Boolean> mirrorBtn =
        CyclingButtonWidget
            .onOffBuilder(Text.literal("§aMirror Uploads: ON"),
                          Text.literal("§cMirror Uploads: OFF"))
            .initially(MetalRenderConfig.mirrorUploads())
            .build(leftCol, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                   Text.literal("Mirror Uploads"), (button, value) -> {
                     if (masterEnabled)
                       MetalRenderConfig.setMirrorUploads(value);
                   });
    mirrorBtn.active = masterEnabled;
    this.addDrawableChild(mirrorBtn);

    y += OPTION_SPACING + 20;

    this.addDrawableChild(ButtonWidget
                              .builder(ScreenTexts.DONE,
                                       button -> {
                                         if (this.client != null) {
                                           this.client.setScreen(this.parent);
                                         }
                                       })
                              .dimensions(centerX - BUTTON_WIDTH / 2, y,
                                          BUTTON_WIDTH, BUTTON_HEIGHT)
                              .build());
  }

  @Override
  public void render(DrawContext context, int mouseX, int mouseY, float delta) {
    super.render(context, mouseX, mouseY, delta);

    boolean metalRenderOn =
        MetalRenderConfig.metalRenderEnabled() &&
        com.metalrender.MetalRenderClient.getWorldRenderer() != null;
    boolean metalFXOn = metalRenderOn && MetalRenderConfig.metalFXEnabled();
    boolean metalFXSupported =
        com.metalrender.nativebridge.NativeBridge.nSupportsMetalFX();

    String status =
        "§7MetalRender: " + (metalRenderOn ? "§aON" : "§cOFF") +
        " | MetalFX: " + (metalFXOn && metalFXSupported ? "§aON" : "§cOFF") +
        (metalFXSupported ? "" : " (§cNot Supported§7)");
    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
                                       this.width / 2, this.height - 30,
                                       0xFFFFFF);

    context.drawCenteredTextWithShadow(
        this.textRenderer,
        Text.literal("§7Optimized for Apple Silicon (M-series chips)"),
        this.width / 2, this.height - 15, 0x888888);
  }

  @Override
  public void close() {
    if (this.client != null) {
      this.client.setScreen(this.parent);
    }
  }

  private static class MetalFXScaleSlider extends SliderWidget {
    public MetalFXScaleSlider(int x, int y, int width, int height) {
      super(x, y, width, height,
            Text.literal("MetalFX Scale: " +
                         String.format("%.0f%%",
                                       MetalRenderConfig.metalFXScale() * 100)),
            (MetalRenderConfig.metalFXScale() - 0.5f) / (1.0f - 0.5f));
    }

    @Override
    protected void updateMessage() {
      float scale = 0.5f + (float)this.value * (1.0f - 0.5f);
      scale = Math.max(0.5f, Math.min(1.0f, scale));
      this.setMessage(Text.literal("MetalFX Scale: " +
                                   String.format("%.0f%%", scale * 100)));
    }

    @Override
    protected void applyValue() {
      float scale = 0.5f + (float)this.value * (1.0f - 0.5f);
      scale = Math.max(0.5f, Math.min(1.0f, scale));
      MetalRenderConfig.setMetalFXScale(scale);
    }
  }

  private static class ResolutionScaleSlider extends SliderWidget {
    public ResolutionScaleSlider(int x, int y, int width, int height) {
      super(x, y, width, height,
            Text.literal(
                "Resolution: " +
                String.format("%.0f%%",
                              MetalRenderConfig.resolutionScale() * 100)),
            (MetalRenderConfig.resolutionScale() - 0.5) / 1.0);
    }

    @Override
    protected void updateMessage() {
      float scale = 0.5f + (float)this.value * 1.0f;
      this.setMessage(
          Text.literal("Resolution: " + String.format("%.0f%%", scale * 100)));
    }

    @Override
    protected void applyValue() {
      float scale = 0.5f + (float)this.value * 1.0f;
      MetalRenderConfig.setResolutionScale(scale);
    }
  }
}
