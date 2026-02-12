package com.metalrender.config.gui;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.config.MetalRenderConfigData;
import com.metalrender.config.MetalRenderConfigManager;
import com.metalrender.performance.RenderDistanceManager;
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
  private int currentTab = 0;
  private static final String[] TAB_NAMES = { "General", "Performance", "Quality", "Advanced" };

  public MetalRenderConfigScreen(Screen parent) {
    super(Text.literal("MetalRender Settings"));
    this.parent = parent;
    MetalRenderConfigManager.syncFromRuntime(false);
  }

  @Override
  protected void init() {
    this.clearChildren();
    MetalRenderConfigData data = MetalRenderConfigManager.getCurrent();

    int centerX = this.width / 2;

    int tabWidth = 80;
    int tabSpacing = 4;
    int totalTabWidth = TAB_NAMES.length * tabWidth + (TAB_NAMES.length - 1) * tabSpacing;
    int tabStartX = centerX - totalTabWidth / 2;
    int tabY = 40;

    for (int i = 0; i < TAB_NAMES.length; i++) {
      final int tabIndex = i;
      String label = (i == currentTab) ? "\u00A7l\u00A7n" + TAB_NAMES[i] : "\u00A77" + TAB_NAMES[i];
      this.addDrawableChild(ButtonWidget.builder(
          Text.literal(label),
          btn -> {
            currentTab = tabIndex;
            init();
          }).position(tabStartX + i * (tabWidth + tabSpacing), tabY)
          .size(tabWidth, 20).build());
    }

    int contentY = 70;
    int sliderWidth = 280;

    switch (currentTab) {
      case 0 -> initGeneralTab(centerX, contentY, sliderWidth, data);
      case 1 -> initPerformanceTab(centerX, contentY, sliderWidth, data);
      case 2 -> initQualityTab(centerX, contentY, sliderWidth, data);
      case 3 -> initAdvancedTab(centerX, contentY, sliderWidth, data);
    }

    int bottomY = this.height - 50;

    int presetWidth = 55;
    int presetSpacing = 4;
    int totalPresetWidth = presetWidth * 4 + presetSpacing * 3;
    int presetStartX = centerX - totalPresetWidth / 2;

    this.addDrawableChild(ButtonWidget.builder(Text.literal("\u00A7cLow"), btn -> {
      MetalRenderConfig.applyPreset(MetalRenderConfig.Preset.LOW);
      MetalRenderConfigManager.syncFromRuntime(true);
      RenderDistanceManager.getInstance().setEnabled(MetalRenderConfig.autoRenderDistanceEnabled());
      init();
    }).position(presetStartX, bottomY).size(presetWidth, 20).build());

    this.addDrawableChild(ButtonWidget.builder(Text.literal("\u00A7eMed"), btn -> {
      MetalRenderConfig.applyPreset(MetalRenderConfig.Preset.MEDIUM);
      MetalRenderConfigManager.syncFromRuntime(true);
      init();
    }).position(presetStartX + presetWidth + presetSpacing, bottomY).size(presetWidth, 20).build());

    this.addDrawableChild(ButtonWidget.builder(Text.literal("\u00A7aHigh"), btn -> {
      MetalRenderConfig.applyPreset(MetalRenderConfig.Preset.HIGH);
      MetalRenderConfigManager.syncFromRuntime(true);
      init();
    }).position(presetStartX + (presetWidth + presetSpacing) * 2, bottomY).size(presetWidth, 20).build());

    this.addDrawableChild(ButtonWidget.builder(Text.literal("\u00A7bUltra"), btn -> {
      MetalRenderConfig.applyPreset(MetalRenderConfig.Preset.ULTRA);
      MetalRenderConfigManager.syncFromRuntime(true);
      init();
    }).position(presetStartX + (presetWidth + presetSpacing) * 3, bottomY).size(presetWidth, 20).build());

    int doneY = this.height - 25;
    this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> close())
        .position(centerX - 50, doneY).size(100, 20).build());
  }

  private void initGeneralTab(int centerX, int y, int sliderWidth, MetalRenderConfigData data) {
    int buttonWidth = 130;
    int col1 = centerX - buttonWidth - 5;
    int col2 = centerX + 5;

    addToggle(col1, y, buttonWidth, "MetalRender",
        data.metalRenderEnabled, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.metalRenderEnabled = value);
          MetalRenderClient.refreshEnabledState();
        });

    addToggle(col2, y, buttonWidth, "Mesh Shaders",
        data.meshShadersEnabled, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.meshShadersEnabled = value);
          scheduleChunkReload();
        });

    y += 28;
    addToggle(col1, y, buttonWidth, "Frustum Cull",
        data.aggressiveFrustumCulling, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.aggressiveFrustumCulling = value);
        });

    addToggle(col2, y, buttonWidth, "Occlusion Cull",
        data.occlusionCulling, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.occlusionCulling = value);
          scheduleChunkReload();
        });

    y += 35;

    addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Render Distance",
        2.0D, 256.0D, 1.0D,
        getViewDistance(),
        value -> {
          int dist = (int) Math.round(value);
          setViewDistance(dist);
        }, v -> String.format("%d chunks", (int) Math.round(v)));
  }

  private void initPerformanceTab(int centerX, int y, int sliderWidth, MetalRenderConfigData data) {
    int buttonWidth = 130;
    int col1 = centerX - buttonWidth - 5;
    int col2 = centerX + 5;

    addToggle(col1, y, buttonWidth, "Dynamic Qual.",
        data.dynamicQuality, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.dynamicQuality = value);
        });

    addToggle(col2, y, buttonWidth, "Auto Distance",
        MetalRenderConfig.autoRenderDistanceEnabled(), value -> {
          MetalRenderConfig.setAutoRenderDistanceEnabled(value);
          RenderDistanceManager.getInstance().setEnabled(value);
          MetalRenderConfigManager.update(cfg -> {
          });
        });

    y += 28;
    addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Target FPS",
        30.0D, 240.0D, 5.0D,
        1000.0D / data.dqTargetFrameMs,
        value -> {
          double ms = 1000.0D / Math.max(30.0D, value);
          MetalRenderConfigManager.update(cfg -> cfg.dqTargetFrameMs = ms);
        }, v -> String.format("%.0f FPS", v));

    y += 22;
    addSlider(centerX - sliderWidth / 2, y, sliderWidth, "Min Resolution",
        0.5D, 1.0D, 0.05D, data.dqMinScale, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.dqMinScale = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y += 22;
    addSlider(centerX - sliderWidth / 2, y, sliderWidth, "Max Resolution",
        0.7D, 1.5D, 0.05D, data.dqMaxScale, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.dqMaxScale = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y += 28;
    addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Block Culling",
        0.0D, 2.0D, 1.0D, data.blockCullingLevel, value -> {
          int lvl = (int) Math.round(value);
          MetalRenderConfigManager.update(cfg -> {
            cfg.blockCullingLevel = lvl;
            MetalRenderConfig.setBlockCullingLevel(lvl);
          });
        }, v -> {
          int lvl = (int) Math.round(v);
          return switch (lvl) {
            case 0 -> "Normal";
            case 1 -> "Aggressive";
            case 2 -> "Ultra";
            default -> "?";
          };
        });
  }

  private void initQualityTab(int centerX, int y, int sliderWidth, MetalRenderConfigData data) {
    int buttonWidth = 130;
    int col1 = centerX - buttonWidth - 5;
    int col2 = centerX + 5;

    addToggle(col1, y, buttonWidth, "MetalFX Spatial",
        MetalRenderConfig.metalFXEnabled(), value -> {
          MetalRenderConfig.setMetalFXEnabled(value);
          MetalRenderConfigManager.update(cfg -> {
          });
        });

    addToggle(col2, y, buttonWidth, "Dynamic Light",
        MetalRenderConfig.dynamicLightingEnabled(), value -> {
          MetalRenderConfig.setDynamicLightingEnabled(value);
          MetalRenderConfigManager.update(cfg -> {
          });
        });

    y += 28;
    addSlider(
        centerX - sliderWidth / 2, y, sliderWidth, "Resolution Scale",
        0.5D, 1.5D, 0.05D, data.resolutionScale, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.resolutionScale = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y += 22;
    addSlider(centerX - sliderWidth / 2, y, sliderWidth, "Distant Detail",
        0.10D, 1.0D, 0.05D, data.lodDistantScale, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.lodDistantScale = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y += 22;
    addSlider(centerX - sliderWidth / 2, y, sliderWidth, "Full Detail Range",
        16.0D, 256.0D, 8.0D, data.lodLevel0Distance, value -> {
          MetalRenderConfigManager.update(cfg -> {
            cfg.lodLevel0Distance = (int) value;
            MetalRenderConfig.setLodLevel0Distance((int) value);
          });
        }, v -> String.format("%.0f blocks", v));

    y += 22;
    addSlider(centerX - sliderWidth / 2, y, sliderWidth, "Fog Distance",
        100.0D, 4096.0D, 50.0D, MetalRenderConfig.fogEndDistance(), value -> {
          MetalRenderConfig.setFogEndDistance((float) value);
          MetalRenderConfigManager.update(cfg -> {
          });
        }, v -> String.format("%.0f blocks", v));
  }

  private void initAdvancedTab(int centerX, int y, int sliderWidth, MetalRenderConfigData data) {
    int buttonWidth = 130;
    int col1 = centerX - buttonWidth - 5;
    int col2 = centerX + 5;

    addToggle(col1, y, buttonWidth, "Mirror (DBG)",
        data.mirrorUploads, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.mirrorUploads = value);
        });

    addToggle(col2, y, buttonWidth, "Debug Info",
        MetalRenderConfig.isDebugEnabled(), value -> {
          MetalRenderConfigManager.update(cfg -> {
          });
        });

    y += 28;
    addToggle(col1, y, buttonWidth, "LOD System",
        data.distanceLodEnabled, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.distanceLodEnabled = value);
          scheduleChunkReload();
        });

    addToggle(col2, y, buttonWidth, "Temporal AA",
        data.temporalAAEnabled, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.temporalAAEnabled = value);
        });

    y += 35;
    addSlider(centerX - sliderWidth / 2, y, sliderWidth, "Temporal Blend",
        0.05D, 0.30D, 0.01D, data.temporalBlendFactor, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.temporalBlendFactor = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y += 22;
    addSlider(centerX - sliderWidth / 2, y, sliderWidth, "Temporal Upscale",
        0.50D, 1.0D, 0.05D, data.temporalUpscaleTarget, value -> {
          MetalRenderConfigManager.update(cfg -> cfg.temporalUpscaleTarget = (float) value);
        }, v -> String.format("%.0f%%", v * 100));

    y += 28;

    this.addDrawableChild(ButtonWidget.builder(Text.literal("\u00A7cReset All Settings"), btn -> {
      MetalRenderConfigManager.resetToDefaults();
      MetalRenderClient.refreshEnabledState();
      init();
    }).position(centerX - 80, y).size(160, 20).build());
  }

  private int getViewDistance() {
    return MetalRenderConfig.extremeRenderDistance();
  }

  private void setViewDistance(int distance) {
    try {
      int clamped = Math.max(2, Math.min(256, distance));
      MetalRenderConfig.setExtremeRenderDistance(clamped);
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc != null && mc.options != null) {
        mc.options.getViewDistance().setValue(clamped);
      }
    } catch (Throwable e) {
      MetalLogger.warn("Failed to set view distance: {}", e.getMessage());
    }
  }

  private void addToggle(int x, int y, int width, String label,
      boolean initial, Consumer<Boolean> listener) {
    ToggleControl control = new ToggleControl(label, initial, listener);
    ButtonWidget button = control.createButton(x, y, width, 20);
    this.addDrawableChild(button);
  }

  private void addSlider(int x, int y, int width, String label,
      double min, double max, double step,
      double initial, DoubleConsumer listener,
      Function<Double, String> formatter) {
    RangeSlider slider = new RangeSlider(label, x, y, width, min, max, step,
        initial, listener, formatter);
    this.addDrawableChild(slider);
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
  public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
    super.render(drawContext, mouseX, mouseY, delta);

    drawContext.drawCenteredTextWithShadow(this.textRenderer, this.title,
        this.width / 2, 15, 0xFFFFFF);

    String gpu = MetalRenderClient.getDeviceName();
    if (gpu != null && !gpu.isEmpty()) {
      drawContext.drawCenteredTextWithShadow(this.textRenderer,
          Text.literal("\u00A77GPU: " + gpu),
          this.width / 2, 28, 0x888888);
    }
  }

  private void scheduleChunkReload() {
    MinecraftClient client = this.client;
    if (client != null && client.worldRenderer != null) {
      MetalLogger.info("[Config UI] Scheduling chunk reload for config changes");
      client.worldRenderer.reload();
    }
  }

  private static final class ToggleControl {
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
          Text.literal(getDisplayText()),
          btn -> toggle()).position(x, y).size(width, height).build();
      return this.button;
    }

    private void toggle() {
      this.value = !this.value;
      if (this.button != null) {
        this.button.setMessage(Text.literal(getDisplayText()));
      }
      this.listener.accept(this.value);
    }

    private String getDisplayText() {
      return this.label + ": " + (this.value ? "\u00A7aON" : "\u00A7cOFF");
    }
  }

  private static final class RangeSlider extends SliderWidget {
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
      super(x, y, width, 20, Text.literal(""), normalize(min, max, currentValue));
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
      this.setMessage(Text.literal(this.label + ": " + this.formatter.apply(value)));
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
  }

  private static double normalize(double min, double max, double value) {
    if (max - min <= 0.0D)
      return 0.0D;
    return MathHelper.clamp((value - min) / (max - min), 0.0D, 1.0D);
  }
}
