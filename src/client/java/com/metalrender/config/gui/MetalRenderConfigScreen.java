package com.metalrender.config.gui;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfigData;
import com.metalrender.config.MetalRenderConfigManager;
import com.metalrender.util.MetalLogger;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

public final class MetalRenderConfigScreen extends Screen {
  private final Screen parent;

  private ToggleControl metalRenderToggle;
  private ToggleControl dynamicQualityToggle;
  private ToggleControl lodToggle;
  private ToggleControl occlusionToggle;
  private ToggleControl frustumToggle;
  private ToggleControl temporalToggle;
  private ToggleControl meshShaderToggle;
  private ToggleControl mirrorUploadsToggle;

  private RangeSlider dqTargetSlider;
  private RangeSlider dqMinSlider;
  private RangeSlider dqMaxSlider;
  private RangeSlider dqStepSlider;
  private RangeSlider temporalScaleSlider;
  private RangeSlider temporalBlendSlider;
  private RangeSlider lodDistantScaleSlider;

  public MetalRenderConfigScreen(Screen parent) {
    super(Text.literal("MetalRender Settings"));
    this.parent = parent;
    com.metalrender.config.MetalRenderConfigManager.syncFromRuntime(false);
  }

  @Override
  protected void init() {
    this.clearChildren();

    MetalRenderConfigData data = MetalRenderConfigManager.getCurrent();
    int left = this.width / 2 - 160;
    int right = this.width / 2 + 10;
    int y = 60;
    int toggleWidth = 150;

    this.metalRenderToggle = addToggle(
        left, y, toggleWidth, "MetalRender", data.metalRenderEnabled, value -> {
          MetalLogger.info("[Config UI] MetalRender: %s -> %s",
                           data.metalRenderEnabled, value);
          MetalRenderConfigManager.update(
              cfg -> cfg.metalRenderEnabled = value);
          MetalRenderClient.refreshEnabledState();
        });
    y += 24;
    this.dynamicQualityToggle = addToggle(
        left, y, toggleWidth, "Dynamic Quality", data.dynamicQuality, value -> {
          MetalLogger.info("[Config UI] Dynamic Quality: %s -> %s",
                           data.dynamicQuality, value);
          MetalRenderConfigManager.update(cfg -> cfg.dynamicQuality = value);
        });
    this.meshShaderToggle =
        addToggle(right, y, toggleWidth, "Mesh Shaders",
                  data.meshShadersEnabled, value -> {
                    MetalLogger.info("[Config UI] Mesh Shaders: %s -> %s",
                                     data.meshShadersEnabled, value);
                    MetalRenderConfigManager.update(
                        cfg -> cfg.meshShadersEnabled = value);
                    scheduleChunkReload();
                  });
    y += 24;
    this.lodToggle = addToggle(
        left, y, toggleWidth, "LOD System", data.distanceLodEnabled, value -> {
          MetalLogger.info("[Config UI] LOD System: %s -> %s",
                           data.distanceLodEnabled, value);
          MetalRenderConfigManager.update(
              cfg -> cfg.distanceLodEnabled = value);
          scheduleChunkReload();
        });
    this.occlusionToggle = addToggle(
        right, y, toggleWidth, "Occlusion Culling", data.occlusionCulling,
        value -> {
          MetalLogger.info("[Config UI] Occlusion Culling: %s -> %s",
                           data.occlusionCulling, value);
          MetalRenderConfigManager.update(cfg -> cfg.occlusionCulling = value);
          scheduleChunkReload();
        });
    y += 24;
    this.temporalToggle = addToggle(
        left, y, toggleWidth, "Temporal AA", data.temporalAAEnabled, value -> {
          MetalLogger.info("[Config UI] Temporal AA: %s -> %s",
                           data.temporalAAEnabled, value);
          MetalRenderConfigManager.update(cfg -> cfg.temporalAAEnabled = value);
        });
    this.frustumToggle =
        addToggle(right, y, toggleWidth, "Aggressive Frustum",
                  data.aggressiveFrustumCulling, value -> {
                    MetalLogger.info("[Config UI] Aggressive Frustum: %s -> %s",
                                     data.aggressiveFrustumCulling, value);
                    MetalRenderConfigManager.update(
                        cfg -> cfg.aggressiveFrustumCulling = value);
                  });
    y += 24;
    this.mirrorUploadsToggle = addToggle(
        left, y, toggleWidth, "Mirror Uploads", data.mirrorUploads, value -> {
          MetalLogger.info("[Config UI] Mirror Uploads: %s -> %s",
                           data.mirrorUploads, value);
          MetalRenderConfigManager.update(cfg -> cfg.mirrorUploads = value);
        });

    int sliderY = y + 40;
    int sliderWidth = 320;
    int sliderX = this.width / 2 - sliderWidth / 2;

    this.dqTargetSlider = addSlider(
        sliderX, sliderY, sliderWidth, "Dynamic Target Frame (ms)", 6.0D, 25.0D,
        0.5D, data.dqTargetFrameMs, value -> {
          MetalLogger.info("[Config UI] Dynamic Target Frame: %.2fms -> %.2fms",
                           data.dqTargetFrameMs, value);
          MetalRenderConfigManager.update(cfg -> cfg.dqTargetFrameMs = value);
        });
    sliderY += 24;

    this.dqMinSlider = addSlider(
        sliderX, sliderY, sliderWidth, "Min Resolution Scale", 0.5D, 1.0D,
        0.01D, data.dqMinScale, value -> {
          MetalLogger.info("[Config UI] Min Resolution Scale: %.2f -> %.2f",
                           data.dqMinScale, value);
          MetalRenderConfigManager.update(cfg -> {
            cfg.dqMinScale = (float)value;
            if (cfg.dqMaxScale < cfg.dqMinScale) {
              cfg.dqMaxScale = cfg.dqMinScale;
              this.dqMaxSlider.setRealValue(cfg.dqMaxScale);
            }
          });
        }, v -> String.format("%.2f", v));
    sliderY += 24;

    this.dqMaxSlider = addSlider(
        sliderX, sliderY, sliderWidth, "Max Resolution Scale", 0.7D, 1.25D,
        0.01D, data.dqMaxScale, value -> {
          MetalLogger.info("[Config UI] Max Resolution Scale: %.2f -> %.2f",
                           data.dqMaxScale, value);
          MetalRenderConfigManager.update(cfg -> {
            cfg.dqMaxScale = (float)value;
            if (cfg.dqMaxScale < cfg.dqMinScale) {
              cfg.dqMinScale = cfg.dqMaxScale;
              this.dqMinSlider.setRealValue(cfg.dqMinScale);
            }
          });
        }, v -> String.format("%.2f", v));
    sliderY += 24;

    this.dqStepSlider =
        addSlider(sliderX, sliderY, sliderWidth, "Scale Step", 0.01D, 0.25D,
                  0.01D, data.dqScaleStep, value -> {
                    MetalLogger.info("[Config UI] Scale Step: %.2f -> %.2f",
                                     data.dqScaleStep, value);
                    MetalRenderConfigManager.update(
                        cfg -> cfg.dqScaleStep = (float)value);
                  }, v -> String.format("%.2f", v));
    sliderY += 24;

    this.temporalScaleSlider = addSlider(
        sliderX, sliderY, sliderWidth, "Temporal Upscale Target", 0.75D, 1.0D,
        0.01D, data.temporalUpscaleTarget, value -> {
          MetalLogger.info("[Config UI] Temporal Upscale Target: %.2f -> %.2f",
                           data.temporalUpscaleTarget, value);
          MetalRenderConfigManager.update(
              cfg -> cfg.temporalUpscaleTarget = (float)value);
        }, v -> String.format("%.2f", v));
    sliderY += 24;

    this.temporalBlendSlider = addSlider(
        sliderX, sliderY, sliderWidth, "Temporal Blend Factor", 0.05D, 0.30D,
        0.01D, data.temporalBlendFactor, value -> {
          MetalLogger.info("[Config UI] Temporal Blend Factor: %.2f -> %.2f",
                           data.temporalBlendFactor, value);
          MetalRenderConfigManager.update(
              cfg -> cfg.temporalBlendFactor = (float)value);
        }, v -> String.format("%.2f", v));
    sliderY += 24;

    this.lodDistantScaleSlider = addSlider(
        sliderX, sliderY, sliderWidth, "Distant LOD Scale", 0.10D, 1.0D, 0.01D,
        data.lodDistantScale, value -> {
          MetalLogger.info("[Config UI] Distant LOD Scale: %.2f -> %.2f",
                           data.lodDistantScale, value);
          MetalRenderConfigManager.update(
              cfg -> cfg.lodDistantScale = (float)value);
        }, v -> String.format("%.2f", v));

    sliderY += 40;
    ButtonWidget resetButton =
        ButtonWidget
            .builder(Text.literal("Reset to Defaults"),
                     btn -> {
                       MetalLogger.info(
                           "[Config UI] Resetting all settings to defaults");
                       MetalRenderConfigManager.resetToDefaults();
                       MetalRenderClient.refreshEnabledState();
                       reloadFromConfig();
                     })
            .position(this.width / 2 - 160, sliderY)
            .size(150, 20)
            .build();
    this.addDrawableChild(resetButton);

    ButtonWidget doneButton =
        ButtonWidget.builder(Text.translatable("gui.done"), btn -> close())
            .position(this.width / 2 + 10, sliderY)
            .size(150, 20)
            .build();
    this.addDrawableChild(doneButton);
  }

  private ToggleControl addToggle(int x, int y, int width, String label,
                                  boolean initial, Consumer<Boolean> listener) {
    ToggleControl control = new ToggleControl(label, initial, listener);
    ButtonWidget widget =
        ButtonWidget.builder(Text.empty(), btn -> control.toggle())
            .position(x, y)
            .size(width, 20)
            .build();
    control.attach(widget);
    this.addDrawableChild(widget);
    return control;
  }

  private RangeSlider addSlider(int x, int y, int width, String label,
                                double min, double max, double step,
                                double initial, DoubleConsumer listener) {
    return addSlider(x, y, width, label, min, max, step, initial, listener,
                     null);
  }

  private RangeSlider addSlider(int x, int y, int width, String label,
                                double min, double max, double step,
                                double initial, DoubleConsumer listener,
                                Function<Double, String> formatter) {
    RangeSlider slider = new RangeSlider(label, x, y, width, min, max, step,
                                         initial, listener, formatter);
    this.addDrawableChild(slider);
    return slider;
  }

  @Override
  public void close() {
    MetalLogger.info("[Config UI] Closing config screen, saving changes");
    MetalRenderConfigManager.saveIfDirty();
    MinecraftClient client = this.client;
    if (client != null) {
      client.setScreen(this.parent);
    }
  }

  @Override
  public void render(DrawContext drawContext, int mouseX, int mouseY,
                     float delta) {
    super.render(drawContext, mouseX, mouseY, delta);
    drawContext.drawCenteredTextWithShadow(this.textRenderer, this.title,
                                           this.width / 2, 20, 0xFFFFFF);
  }

  private void reloadFromConfig() {
    MetalRenderConfigData data = MetalRenderConfigManager.getCurrent();
    this.metalRenderToggle.setValue(data.metalRenderEnabled);
    this.dynamicQualityToggle.setValue(data.dynamicQuality);
    this.lodToggle.setValue(data.distanceLodEnabled);
    this.occlusionToggle.setValue(data.occlusionCulling);
    this.frustumToggle.setValue(data.aggressiveFrustumCulling);
    this.temporalToggle.setValue(data.temporalAAEnabled);
    this.meshShaderToggle.setValue(data.meshShadersEnabled);
    this.mirrorUploadsToggle.setValue(data.mirrorUploads);

    this.dqTargetSlider.setRealValue(data.dqTargetFrameMs);
    this.dqMinSlider.setRealValue(data.dqMinScale);
    this.dqMaxSlider.setRealValue(data.dqMaxScale);
    this.dqStepSlider.setRealValue(data.dqScaleStep);
    this.temporalScaleSlider.setRealValue(data.temporalUpscaleTarget);
    this.temporalBlendSlider.setRealValue(data.temporalBlendFactor);
    this.lodDistantScaleSlider.setRealValue(data.lodDistantScale);
  }

  private void scheduleChunkReload() {
    MinecraftClient client = this.client;
    if (client != null && client.worldRenderer != null) {
      MetalLogger.info(
          "[Config UI] Scheduling chunk reload for config changes");
      client.worldRenderer.reload();
    }
  }

  private final class ToggleControl {
    private final String label;
    private final Consumer<Boolean> listener;
    private ButtonWidget button;
    private boolean value;

    private ToggleControl(String label, boolean initial,
                          Consumer<Boolean> listener) {
      this.label = label;
      this.listener = listener;
      this.value = initial;
    }

    private void attach(ButtonWidget button) {
      this.button = button;
      updateLabel();
    }

    private void toggle() { setValue(!this.value, true); }

    private void setValue(boolean newValue) { setValue(newValue, false); }

    private void setValue(boolean newValue, boolean notify) {
      this.value = newValue;
      updateLabel();
      if (notify) {
        this.listener.accept(newValue);
      }
    }

    private void updateLabel() {
      if (this.button != null) {
        this.button.setMessage(
            Text.literal(this.label + ": " + (this.value ? "ON" : "OFF")));
      }
    }
  }

  private final class RangeSlider extends SliderWidget {
    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer listener;
    private final Function<Double, String> formatter;

    private RangeSlider(String label, int x, int y, int width, double min,
                        double max, double step, double currentValue,
                        DoubleConsumer listener,
                        Function<Double, String> formatter) {
      super(x, y, width, 20, Text.empty(), normalize(min, max, currentValue));
      this.label = label;
      this.min = min;
      this.max = max;
      this.step = step;
      this.listener = listener;
      this.formatter =
          formatter != null ? formatter : value -> String.format("%.2f", value);
      updateMessage();
    }

    @Override
    protected void updateMessage() {
      double value = quantize(denormalize(this.value));
      this.setMessage(
          Text.literal(this.label + ": " + this.formatter.apply(value)));
    }

    @Override
    protected void applyValue() {
      double value = quantize(denormalize(this.value));
      this.listener.accept(value);
      updateMessage();
    }

    private double denormalize(double percent) {
      return MathHelper.lerp(percent, this.min, this.max);
    }

    private double quantize(double value) {
      if (this.step > 0.0D) {
        value = Math.round(value / this.step) * this.step;
      }
      return MathHelper.clamp(value, this.min, this.max);
    }

    private void setRealValue(double value) {
      this.value = normalize(this.min, this.max,
                             MathHelper.clamp(value, this.min, this.max));
      updateMessage();
    }
  }

  private static double normalize(double min, double max, double value) {
    if (max - min <= 0.0D) {
      return 0.0D;
    }
    return MathHelper.clamp((value - min) / (max - min), 0.0D, 1.0D);
  }
}
