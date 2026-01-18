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
  private ToggleControl meshShaderToggle;
  private ToggleControl dynamicQualityToggle;
  private ToggleControl temporalToggle;

  private ToggleControl lodToggle;
  private ToggleControl occlusionToggle;
  private ToggleControl frustumToggle;
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

    int centerX = this.width / 2;
    int y = 90;
    int buttonWidth = 95;
    int sliderWidth = 280;

    y += 5;
    this.metalRenderToggle = addToggle(
        centerX - 190, y, buttonWidth, "MetalRender",
        data.metalRenderEnabled, 0x00FF00, 0xFF3333, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.metalRenderEnabled = value);
          MetalRenderClient.refreshEnabledState();
        });

    this.meshShaderToggle = addToggle(
        centerX - 85, y, buttonWidth, "Mesh Shaders",
        data.meshShadersEnabled, 0x4488FF, 0xFF9900, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.meshShadersEnabled = value);
          scheduleChunkReload();
        });

    this.dynamicQualityToggle = addToggle(
        centerX + 20, y, buttonWidth, "Dynamic Qual.",
        data.dynamicQuality, 0xFFDD00, 0xFF9900, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.dynamicQuality = value);
        });

    this.temporalToggle = addToggle(
        centerX + 125, y, buttonWidth, "Temporal AA",
        data.temporalAAEnabled, 0xFF66FF, 0xFF9900, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.temporalAAEnabled = value);
        });

    y += 28;
    this.lodToggle = addToggle(
        centerX - 190, y, buttonWidth, "LOD System",
        data.distanceLodEnabled, 0x00DDFF, 0xFF9900, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.distanceLodEnabled = value);
          scheduleChunkReload();
        });

    this.occlusionToggle = addToggle(
        centerX - 85, y, buttonWidth, "Occlusion",
        data.occlusionCulling, 0x33DD33, 0xFF9900, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.occlusionCulling = value);
          scheduleChunkReload();
        });

    this.frustumToggle = addToggle(
        centerX + 20, y, buttonWidth, "Frustum",
        data.aggressiveFrustumCulling, 0xFF8844, 0xFF9900, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.aggressiveFrustumCulling = value);
        });

    this.mirrorUploadsToggle = addToggle(
        centerX + 125, y, buttonWidth, "Mirror (DBG)",
        data.mirrorUploads, 0xAAAAAA, 0x555555, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.mirrorUploads = value);
        });

    y += 28;
    this.dqTargetSlider = addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Target Frame Time (ms)",
        6.0D, 25.0D, 0.5D, data.dqTargetFrameMs, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.dqTargetFrameMs = value);
        }, v -> String.format("%.1f", v));
    y += 22;

    this.temporalScaleSlider = addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Temporal Upscale (%)",
        0.75D, 1.0D, 0.01D, data.temporalUpscaleTarget, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.temporalUpscaleTarget = (float) value);
        }, v -> String.format("%.0f%%", v * 100));
    y += 22;

    this.temporalBlendSlider = addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Temporal Blend (%)",
        0.05D, 0.30D, 0.01D, data.temporalBlendFactor, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.temporalBlendFactor = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y += 28;
    this.dqMinSlider = addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Min Resolution (%)",
        0.5D, 1.0D, 0.01D, data.dqMinScale, value -> {
          MetalRenderConfigManager.update(cfg -> {
            cfg.dqMinScale = (float) value;
            if (cfg.dqMaxScale < cfg.dqMinScale) {
              cfg.dqMaxScale = cfg.dqMinScale;
              if (this.dqMaxSlider != null)
                this.dqMaxSlider.setRealValue(cfg.dqMaxScale);
            }
          });
        }, v -> String.format("%.0f%%", v * 100));
    y += 22;

    this.dqMaxSlider = addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Max Resolution (%)",
        0.7D, 1.25D, 0.01D, data.dqMaxScale, value -> {
          MetalRenderConfigManager.update(cfg -> {
            cfg.dqMaxScale = (float) value;
            if (cfg.dqMaxScale < cfg.dqMinScale) {
              cfg.dqMinScale = cfg.dqMaxScale;
              if (this.dqMinSlider != null)
                this.dqMinSlider.setRealValue(cfg.dqMinScale);
            }
          });
        }, v -> String.format("%.0f%%", v * 100));
    y += 22;

    this.dqStepSlider = addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Scale Step (%)",
        0.01D, 0.25D, 0.01D, data.dqScaleStep, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.dqScaleStep = (float) value);
        }, v -> String.format("%.0f%%", v * 100));
    y += 22;

    this.lodDistantScaleSlider = addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Distant LOD (%)",
        0.10D, 1.0D, 0.01D, data.lodDistantScale, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.lodDistantScale = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y = this.height - 25;
    ButtonWidget resetButton = ButtonWidget.builder(Text.literal("Reset"), btn -> {
      MetalRenderConfigManager.resetToDefaults();
      MetalRenderClient.refreshEnabledState();
      reloadFromConfig();
    })
        .position(centerX - 160, y)
        .size(70, 20)
        .build();
    this.addDrawableChild(resetButton);

    ButtonWidget doneButton = ButtonWidget.builder(Text.literal("Done"), btn -> close())
        .position(centerX + 90, y)
        .size(70, 20)
        .build();
    this.addDrawableChild(doneButton);
  }

  private ToggleControl addToggle(int x, int y, int width, String label,
      boolean initial, int colorOn, int colorOff,
      Consumer<Boolean> listener) {
    // colorOn and colorOff are ignored in simplified version
    ToggleControl control = new ToggleControl(label, initial, listener);
    ButtonWidget button = control.createButton(x, y, width, 20);
    this.addDrawableChild(button);
    return control;
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

  // Use composition instead of inheritance to avoid MC 1.21.11 PressableWidget
  // issues
  private final class ToggleControl {
    final String label;
    final Consumer<Boolean> listener;
    ButtonWidget button;
    boolean value;

    private ToggleControl(String label, boolean initial, Consumer<Boolean> listener) {
      this.label = label;
      this.listener = listener;
      this.value = initial;
    }

    private ButtonWidget createButton(int x, int y, int width, int height) {
      this.button = ButtonWidget.builder(
          net.minecraft.text.Text.literal(getDisplayText()),
          btn -> toggle()).position(x, y).size(width, height).build();
      return this.button;
    }

    private void toggle() {
      setValue(!this.value, true);
    }

    private void setValue(boolean newValue) {
      setValue(newValue, false);
    }

    private void setValue(boolean newValue, boolean notify) {
      this.value = newValue;
      if (this.button != null) {
        this.button.setMessage(net.minecraft.text.Text.literal(getDisplayText()));
      }
      if (notify) {
        this.listener.accept(newValue);
      }
    }

    private String getDisplayText() {
      return this.label + ": " + (this.value ? "ON" : "OFF");
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
      super(x, y, width, 20, net.minecraft.text.Text.literal(""), normalize(min, max, currentValue));
      this.label = label;
      this.min = min;
      this.max = max;
      this.step = step;
      this.listener = listener;
      this.formatter = formatter != null ? formatter : value -> String.format("%.2f", value);
      updateMessage();
    }

    @Override
    protected void updateMessage() {
      double value = quantize(denormalize(this.value));
      this.setMessage(
          net.minecraft.text.Text.literal(this.label + ": " + this.formatter.apply(value)));
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
