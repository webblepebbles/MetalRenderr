package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.gui.components.MetalOptionSlider;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

public class MetalRenderSettingsScreen extends Screen {

  private static final int BG_DARK = 0x40000000;
  private static final int PANEL_BG = 0x66000000;
  private static final int PANEL_BORDER = 0x88FFFFFF;
  private static final int TAB_SELECTED = 0x55FFFFFF;
  private static final int TAB_HOVER = 0x33FFFFFF;
  private static final int TAB_NORMAL = 0x11FFFFFF;
  private static final int ROW_BG_EVEN = 0x1AFFFFFF;
  private static final int ROW_BG_ODD = 0x0AFFFFFF;
  private static final int ROW_HOVER = 0x2AFFFFFF;
  private static final int ACCENT = 0xFF007AFF;
  private static final int TEXT_BRIGHT = 0xFFFFFFFF;
  private static final int TEXT_DIM = 0xFFAAAAAA;
  private static final int TEXT_ON = 0xFF34C759;
  private static final int TEXT_OFF = 0xFFFF3B30;
  private static final int HEADER_COLOR = 0xFFFFFFFF;
  private static final int SCROLLBAR_BG = 0x44FFFFFF;
  private static final int SCROLLBAR_FG = 0x88FFFFFF;

  private static final int TAB_WIDTH = 140;
  private static final int TAB_HEIGHT = 36;
  private static final int ROW_HEIGHT = 36;
  private static final int HEADER_HEIGHT = 45;
  private static final int PAD = 16;
  private static final int FOOTER_HEIGHT = 40;

  private final Screen parent;
  private MetalRenderConfig config;
  private int selectedPage;
  private int scrollOffset;
  private int maxScroll;
  private boolean draggingScrollbar;
  private int pendingRenderDistance;
  private int pendingSimulationDistance;
  private int pendingMaxFps;
  private int pendingGuiScale;
  private double pendingBrightness;
  private int pendingFov;
  private double pendingDistortion;
  private double pendingFovEffects;
  private int pendingZone1Radius;
  private int pendingZone2Radius;
  private float pendingLodTransition;
  private int pendingBiomeDetail;
  private int pendingTargetFps;
  private int pendingMaxMemoryMb;
  private int pendingLod1Distance;
  private int pendingLod2Distance;
  private int pendingLod3Distance;
  private int pendingLod4Distance;
  private boolean pendingLodEnabled;
  private MetalOptionSlider renderDistanceSlider;
  private MetalOptionSlider simulationDistanceSlider;
  private MetalOptionSlider maxFpsSlider;
  private MetalOptionSlider guiScaleSlider;
  private MetalOptionSlider brightnessSlider;
  private MetalOptionSlider fovSlider;
  private MetalOptionSlider distortionSlider;
  private MetalOptionSlider fovEffectsSlider;
  private MetalOptionSlider zone1RadiusSlider;
  private MetalOptionSlider zone2RadiusSlider;
  private MetalOptionSlider lodTransitionSlider;
  private MetalOptionSlider targetFpsSlider;
  private MetalOptionSlider maxMemorySlider;
  private MetalOptionSlider biomeDetailSlider;
  private MetalOptionSlider lod1DistanceSlider;
  private MetalOptionSlider lod2DistanceSlider;
  private MetalOptionSlider lod3DistanceSlider;
  private MetalOptionSlider lod4DistanceSlider;

  private int renderDistanceRow = -1;
  private int simulationDistanceRow = -1;
  private int maxFpsRow = -1;
  private int guiScaleRow = -1;
  private int brightnessRow = -1;
  private int fovRow = -1;
  private int distortionRow = -1;
  private int fovEffectsRow = -1;
  private int zone1Row = -1;
  private int zone2Row = -1;
  private int transitionRow = -1;
  private int targetFpsRow = -1;
  private int maxMemoryRow = -1;
  private int biomeDetailRow = -1;
  private int lod1DistanceRow = -1;
  private int lod2DistanceRow = -1;
  private int lod3DistanceRow = -1;
  private int lod4DistanceRow = -1;

  private final String[] pages = {"Video",       "MetalRender", "Quality",
                                  "Performance", "Advanced",    "LOD"};

  private final List<SettingRow> currentRows = new ArrayList<>();

  public MetalRenderSettingsScreen(Screen parent) {
    super(Text.literal("MetalRender Settings"));
    this.parent = parent;
    this.selectedPage = 0;
    this.scrollOffset = 0;
  }

  @Override
  protected void init() {
    config = MetalRenderClient.getConfig();
    if (config == null)
      config = MetalRenderConfig.load();

    GameOptions options = MinecraftClient.getInstance().options;
    pendingRenderDistance = options.getViewDistance().getValue();
    pendingSimulationDistance = options.getSimulationDistance().getValue();
    pendingMaxFps = options.getMaxFps().getValue();
    pendingGuiScale = options.getGuiScale().getValue();
    pendingBrightness = options.getGamma().getValue();
    pendingFov = options.getFov().getValue();
    pendingDistortion = options.getDistortionEffectScale().getValue();
    pendingFovEffects = options.getFovEffectScale().getValue();
    pendingZone1Radius = config.zone1Radius;
    pendingZone2Radius = config.zone2Radius;
    pendingLodTransition = config.lodTransitionDistance;
    pendingBiomeDetail = config.biomeTransitionDetail;
    pendingTargetFps = config.targetFrameRate;
    pendingMaxMemoryMb = config.maxMemoryMB;
    pendingLod1Distance = MetalRenderConfig.lod1Distance();
    pendingLod2Distance = MetalRenderConfig.lod2Distance();
    pendingLod3Distance = MetalRenderConfig.lod3Distance();
    pendingLod4Distance = MetalRenderConfig.lod4Distance();
    pendingLodEnabled = MetalRenderConfig.lodEnabled();
    rebuildRows();
  }

  @Override
  public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
    renderInGameBackground(ctx);
    ctx.fillGradient(0, 0, width, height, 0x88000000, 0xFF000000);
    ctx.fillGradient(0, 0, width, height, 0x88000000, 0xFF000000);
    ctx.fillGradient(0, 0, width, height, 0x88000000, 0xFF000000);

    ctx.drawCenteredTextWithShadow(textRenderer,
                                   Text.literal("MetalRender Settings"),
                                   width / 2, 8, TEXT_BRIGHT);

    int tabX = PAD;
    int tabY = HEADER_HEIGHT;
    int tabPanelH = height - HEADER_HEIGHT - FOOTER_HEIGHT;

    ctx.fill(tabX - 1, tabY - 1, tabX + TAB_WIDTH + 1, tabY + tabPanelH + 1,
             PANEL_BORDER);
    ctx.fill(tabX, tabY, tabX + TAB_WIDTH, tabY + tabPanelH, PANEL_BG);

    for (int i = 0; i < pages.length; i++) {
      int ty = tabY + i * TAB_HEIGHT;
      boolean selected = (i == selectedPage);
      boolean hovered = mouseX >= tabX && mouseX <= tabX + TAB_WIDTH &&
                        mouseY >= ty && mouseY < ty + TAB_HEIGHT;
      int bg = selected ? TAB_SELECTED : (hovered ? TAB_HOVER : TAB_NORMAL);
      drawSlightRoundedRect(ctx, tabX, ty, tabX + TAB_WIDTH,
                            ty + TAB_HEIGHT - 1, bg);
      if (selected) {
        ctx.fill(tabX, ty, tabX + 3, ty + TAB_HEIGHT - 1, ACCENT);
      }
      ctx.drawTextWithShadow(textRenderer, Text.literal(pages[i]), tabX + 10,
                             ty + (TAB_HEIGHT - 9) / 2,
                             selected ? TEXT_BRIGHT : TEXT_DIM);
    }

    int optX = tabX + TAB_WIDTH + PAD;
    int optY = tabY;
    int optW = width - optX - PAD;
    int optH = tabPanelH;

    updateSliderPositions(optX, optY, optW, optH);

    ctx.fill(optX - 1, optY - 1, optX + optW + 1, optY + optH + 1,
             PANEL_BORDER);
    ctx.fill(optX, optY, optX + optW, optY + optH, PANEL_BG);

    int contentH = currentRows.size() * ROW_HEIGHT;
    maxScroll = Math.max(0, contentH - optH);
    scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

    ctx.enableScissor(optX, optY, optX + optW, optY + optH);
    for (int i = 0; i < currentRows.size(); i++) {
      int ry = optY + i * ROW_HEIGHT - scrollOffset;
      if (ry + ROW_HEIGHT < optY || ry > optY + optH)
        continue;

      SettingRow row = currentRows.get(i);
      boolean rowHover = mouseX >= optX && mouseX <= optX + optW &&
                         mouseY >= Math.max(ry, optY) &&
                         mouseY < Math.min(ry + ROW_HEIGHT, optY + optH);

      if (row.type == RowType.HEADER) {
        ctx.drawTextWithShadow(textRenderer, Text.literal(row.label), optX + 8,
                               ry + (ROW_HEIGHT - 9) / 2, HEADER_COLOR);
        ctx.fill(optX + 8, ry + ROW_HEIGHT - 4, optX + optW - 8,
                 ry + ROW_HEIGHT - 3, PANEL_BORDER);
      } else if (row.type == RowType.INFO) {
        String infoText = row.value == null || row.value.isEmpty()
                              ? row.label
                              : row.label + ": " + row.value;
        ctx.drawText(textRenderer, Text.literal(infoText), optX + 12,
                     ry + (ROW_HEIGHT - 9) / 2, TEXT_DIM, false);
      } else {
        int rowBg =
            rowHover ? ROW_HOVER : (i % 2 == 0 ? ROW_BG_EVEN : ROW_BG_ODD);
        ctx.fill(optX + 4, ry + 1, optX + optW - 4, ry + ROW_HEIGHT - 1, rowBg);

        ctx.drawText(textRenderer, Text.literal(row.label), optX + 12,
                     ry + (ROW_HEIGHT - 9) / 2, TEXT_BRIGHT, false);

        String valStr = row.value;
        int valColor = ACCENT;
        if ("Enabled".equals(valStr) || "ON".equals(valStr) ||
            "Yes".equals(valStr) || "Supported".equals(valStr) ||
            "Detected".equals(valStr)) {
          valColor = TEXT_ON;
        } else if ("Disabled".equals(valStr) || "OFF".equals(valStr) ||
                   "No".equals(valStr) || "Not Available".equals(valStr) ||
                   "Not Installed".equals(valStr)) {
          valColor = TEXT_OFF;
        }
        int valW = textRenderer.getWidth(valStr);
        ctx.drawText(textRenderer, Text.literal(valStr),
                     optX + optW - valW - 12, ry + (ROW_HEIGHT - 9) / 2,
                     valColor, false);
      }
    }
    ctx.disableScissor();

    if (maxScroll > 0) {
      int sbX = optX + optW - 6;
      int sbH = optH;
      float thumbRatio = (float)optH / (contentH);
      int thumbH = Math.max(20, (int)(sbH * thumbRatio));
      int thumbY =
          optY + (int)((float)scrollOffset / maxScroll * (sbH - thumbH));

      ctx.fill(sbX, optY, sbX + 4, optY + sbH, SCROLLBAR_BG);
      ctx.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, SCROLLBAR_FG);
    }

    String version = "v0.1.7";
    String gpuStr = MetalHardwareChecker.getDeviceName();
    if (gpuStr == null || gpuStr.isEmpty())
      gpuStr = "Unknown";
    ctx.drawText(textRenderer, Text.literal(version), tabX + 6,
                 tabY + tabPanelH - 22, TEXT_DIM, false);
    int gpuW = textRenderer.getWidth(gpuStr);
    if (gpuW > TAB_WIDTH - 12)
      gpuStr = gpuStr.substring(0, Math.min(gpuStr.length(), 14)) + "…";
    ctx.drawText(textRenderer, Text.literal(gpuStr), tabX + 6,
                 tabY + tabPanelH - 11, TEXT_DIM, false);

    super.render(ctx, mouseX, mouseY, delta);
  }

  @Override
  public boolean mouseClicked(Click click, boolean bl) {
    double mouseX = click.x();
    double mouseY = click.y();

    int tabX = PAD;
    int tabY = HEADER_HEIGHT;
    if (mouseX >= tabX && mouseX <= tabX + TAB_WIDTH) {
      for (int i = 0; i < pages.length; i++) {
        int ty = tabY + i * TAB_HEIGHT;
        if (mouseY >= ty && mouseY < ty + TAB_HEIGHT) {
          if (selectedPage != i) {
            selectedPage = i;
            scrollOffset = 0;
            rebuildRows();
          }
          return true;
        }
      }
    }

    int optX = tabX + TAB_WIDTH + PAD;
    int optY = tabY;
    int optW = width - optX - PAD;
    int optH = height - HEADER_HEIGHT - FOOTER_HEIGHT;

    if (mouseX >= optX && mouseX <= optX + optW && mouseY >= optY &&
        mouseY <= optY + optH) {
      int relY = (int)mouseY - optY + scrollOffset;
      int row = relY / ROW_HEIGHT;
      if (row >= 0 && row < currentRows.size()) {
        SettingRow sr = currentRows.get(row);
        if (sr.type == RowType.TOGGLE) {
          sr.action.run();
          rebuildRows();
          return true;
        } else if (sr.type == RowType.CYCLE) {
          sr.action.run();
          rebuildRows();
          return true;
        } else if (sr.type == RowType.VANILLA_OPTION &&
                   sr.vanillaOption != null) {

          cycleVanillaOption(sr.vanillaOption);
          rebuildRows();
          return true;
        }
      }
    }

    return super.mouseClicked(click, bl);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double hAmount,
                               double vAmount) {
    scrollOffset -= (int)(vAmount * ROW_HEIGHT * 2);
    scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    return true;
  }

  @Override
  public void close() {
    applyStagedNumericOptions();
    config.save();
    MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
    if (wr != null && wr.getChunkMesher() != null) {
      wr.getChunkMesher().markAllDirty();
    }
    if (client != null) {
      client.setScreen(parent);
    }
  }

  private void applyStagedNumericOptions() {
    GameOptions options = MinecraftClient.getInstance().options;
    options.getViewDistance().setValue(pendingRenderDistance);
    options.getSimulationDistance().setValue(pendingSimulationDistance);
    options.getMaxFps().setValue(pendingMaxFps);
    options.getGuiScale().setValue(pendingGuiScale);
    options.getGamma().setValue(pendingBrightness);
    options.getFov().setValue(pendingFov);
    options.getDistortionEffectScale().setValue(pendingDistortion);
    options.getFovEffectScale().setValue(pendingFovEffects);
    config.zone1Radius = pendingZone1Radius;
    config.zone2Radius = pendingZone2Radius;
    config.lodTransitionDistance = pendingLodTransition;
    config.biomeTransitionDetail = pendingBiomeDetail;
    config.targetFrameRate = pendingTargetFps;
    config.maxMemoryMB = pendingMaxMemoryMb;
    MetalRenderConfig.setLodEnabled(pendingLodEnabled);
    MetalRenderConfig.setLod1Distance(pendingLod1Distance);
    MetalRenderConfig.setLod2Distance(pendingLod2Distance);
    MetalRenderConfig.setLod3Distance(pendingLod3Distance);
    MetalRenderConfig.setLod4Distance(pendingLod4Distance);
  }

  private void rebuildRows() {
    clearChildren();
    addFooterButtons();
    clearSliders();
    currentRows.clear();
    maxFpsRow = -1;
    guiScaleRow = -1;
    renderDistanceRow = -1;
    simulationDistanceRow = -1;
    brightnessRow = -1;
    fovRow = -1;
    distortionRow = -1;
    fovEffectsRow = -1;
    zone1Row = -1;
    zone2Row = -1;
    transitionRow = -1;
    targetFpsRow = -1;
    maxMemoryRow = -1;
    biomeDetailRow = -1;
    lod1DistanceRow = -1;
    lod2DistanceRow = -1;
    lod3DistanceRow = -1;
    lod4DistanceRow = -1;
    switch (selectedPage) {
    case 0 -> buildVideoPage();
    case 1 -> buildMetalRenderPage();
    case 2 -> buildQualityPage();
    case 3 -> buildPerformancePage();
    case 4 -> buildAdvancedPage();
    case 5 -> buildLodPage();
    }
    addPageSliders();
  }

  private void addFooterButtons() {
    int btnW = 110;
    int btnGap = 10;
    int totalBtnW = btnW * 2 + btnGap;
    int btnX = width / 2 - totalBtnW / 2;
    int btnY = height - 30;

    addDrawableChild(ButtonWidget
                         .builder(Text.literal("Reset"),
                                  btn -> {
                                    config = MetalRenderConfig.load();
                                    rebuildRows();
                                  })
                         .dimensions(btnX, btnY, btnW, 20)
                         .build());

    addDrawableChild(
        ButtonWidget.builder(Text.literal("Done"), btn -> { close(); })
            .dimensions(btnX + btnW + btnGap, btnY, btnW, 20)
            .build());
  }

  private void clearSliders() {
    renderDistanceSlider = null;
    simulationDistanceSlider = null;
    maxFpsSlider = null;
    guiScaleSlider = null;
    brightnessSlider = null;
    fovSlider = null;
    distortionSlider = null;
    fovEffectsSlider = null;
    zone1RadiusSlider = null;
    zone2RadiusSlider = null;
    lodTransitionSlider = null;
    targetFpsSlider = null;
    maxMemorySlider = null;
    biomeDetailSlider = null;
    lod1DistanceSlider = null;
    lod2DistanceSlider = null;
    lod3DistanceSlider = null;
    lod4DistanceSlider = null;
  }

  private void addPageSliders() {
    if (selectedPage == 0) {
      maxFpsSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 10, 260, 10, pendingMaxFps,
          v -> pendingMaxFps = (int)(float)v));
      guiScaleSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 0, 6, 1, pendingGuiScale,
          v -> pendingGuiScale = (int)(float)v));
      renderDistanceSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 2, 32, 1, pendingRenderDistance,
          v -> pendingRenderDistance = (int)(float)v));
      simulationDistanceSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 5, 32, 1, pendingSimulationDistance,
          v -> pendingSimulationDistance = (int)(float)v));
      brightnessSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 0.0f, 1.0f, 0.05f,
          (float)pendingBrightness, v -> pendingBrightness = v));
      fovSlider = addDrawableChild(
          new MetalOptionSlider(0, 0, 100, 12, Text.literal(""), 30f, 110f, 1f,
                                pendingFov, v -> pendingFov = (int)(float)v));
      distortionSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 0.0f, 1.0f, 0.05f,
          (float)pendingDistortion, v -> pendingDistortion = v));
      fovEffectsSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 0.0f, 1.0f, 0.05f,
          (float)pendingFovEffects, v -> pendingFovEffects = v));
    }

    if (selectedPage == 2) {
      zone1RadiusSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 8, 64, 8, pendingZone1Radius,
          v -> pendingZone1Radius = (int)(float)v));

      zone2RadiusSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 32, 256, 32, pendingZone2Radius,
          v -> pendingZone2Radius = (int)(float)v));

      lodTransitionSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 0.5f, 1.0f, 0.05f,
          pendingLodTransition, v -> pendingLodTransition = v));

      biomeDetailSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 0, 4, 1, pendingBiomeDetail,
          v -> pendingBiomeDetail = (int)(float)v));
    }

    if (selectedPage == 5) {
      lod1DistanceSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 1, 32, 1, pendingLod1Distance,
          v -> pendingLod1Distance = (int)(float)v));
      lod2DistanceSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 2, 32, 1, pendingLod2Distance,
          v -> pendingLod2Distance = (int)(float)v));
      lod3DistanceSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 3, 32, 1, pendingLod3Distance,
          v -> pendingLod3Distance = (int)(float)v));
      lod4DistanceSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 4, 32, 1, pendingLod4Distance,
          v -> pendingLod4Distance = (int)(float)v));
    }

    if (selectedPage == 3) {
      targetFpsSlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 30, 240, 30, pendingTargetFps,
          v -> pendingTargetFps = (int)(float)v));
      maxMemorySlider = addDrawableChild(new MetalOptionSlider(
          0, 0, 100, 12, Text.literal(""), 512, 4096, 512, pendingMaxMemoryMb,
          v -> pendingMaxMemoryMb = (int)(float)v));
    }
  }

  private void updateSliderPositions(int optX, int optY, int optW, int optH) {
    positionSlider(maxFpsSlider, maxFpsRow, optX, optY, optW, optH);
    positionSlider(guiScaleSlider, guiScaleRow, optX, optY, optW, optH);
    positionSlider(renderDistanceSlider, renderDistanceRow, optX, optY, optW,
                   optH);
    positionSlider(simulationDistanceSlider, simulationDistanceRow, optX, optY,
                   optW, optH);
    positionSlider(brightnessSlider, brightnessRow, optX, optY, optW, optH);
    positionSlider(fovSlider, fovRow, optX, optY, optW, optH);
    positionSlider(distortionSlider, distortionRow, optX, optY, optW, optH);
    positionSlider(fovEffectsSlider, fovEffectsRow, optX, optY, optW, optH);
    positionSlider(zone1RadiusSlider, zone1Row, optX, optY, optW, optH);
    positionSlider(zone2RadiusSlider, zone2Row, optX, optY, optW, optH);
    positionSlider(lodTransitionSlider, transitionRow, optX, optY, optW, optH);
    positionSlider(targetFpsSlider, targetFpsRow, optX, optY, optW, optH);
    positionSlider(maxMemorySlider, maxMemoryRow, optX, optY, optW, optH);
    positionSlider(biomeDetailSlider, biomeDetailRow, optX, optY, optW, optH);
    positionSlider(lod1DistanceSlider, lod1DistanceRow, optX, optY, optW, optH);
    positionSlider(lod2DistanceSlider, lod2DistanceRow, optX, optY, optW, optH);
    positionSlider(lod3DistanceSlider, lod3DistanceRow, optX, optY, optW, optH);
    positionSlider(lod4DistanceSlider, lod4DistanceRow, optX, optY, optW, optH);
  }

  private void positionSlider(MetalOptionSlider slider, int rowIndex, int optX,
                              int optY, int optW, int optH) {
    if (slider == null || rowIndex < 0) {
      return;
    }
    int rowY = optY + rowIndex * ROW_HEIGHT - scrollOffset;
    slider.setPosition(optX + optW - 140, rowY + 7);
    slider.setWidth(124);
    boolean visible = rowY >= optY && rowY + ROW_HEIGHT <= optY + optH;
    slider.visible = visible;
    slider.active = visible;
  }

  private void buildVideoPage() {
    if (client == null)
      return;
    GameOptions opts = client.options;

    currentRows.add(SettingRow.header("Display"));
    addVanillaOption("Fullscreen", opts.getFullscreen());
    addVanillaOption("VSync", opts.getEnableVsync());
    maxFpsRow = currentRows.size();
    currentRows.add(SettingRow.info("Max FPS", ""));
    guiScaleRow = currentRows.size();
    currentRows.add(SettingRow.info("GUI Scale", ""));

    currentRows.add(SettingRow.header("Quality"));
    addVanillaOption("Graphics", opts.getPreset());
    renderDistanceRow = currentRows.size();
    currentRows.add(SettingRow.info("Render Distance", ""));
    simulationDistanceRow = currentRows.size();
    currentRows.add(SettingRow.info("Simulation Distance", ""));
    brightnessRow = currentRows.size();
    currentRows.add(SettingRow.info("Brightness", ""));
    addVanillaOption("Smooth Lighting", opts.getAo());
    addVanillaOption("Chunk Builder", opts.getChunkBuilderMode());

    currentRows.add(SettingRow.header("Interface"));
    addVanillaOption("View Bobbing", opts.getBobView());
    addVanillaOption("Entity Shadows", opts.getEntityShadows());
    fovRow = currentRows.size();
    currentRows.add(SettingRow.info("FOV", ""));
    distortionRow = currentRows.size();
    currentRows.add(SettingRow.info("Distortion Effects", ""));
    fovEffectsRow = currentRows.size();
    currentRows.add(SettingRow.info("FOV Effects", ""));
  }

  private void buildMetalRenderPage() {
    currentRows.add(SettingRow.header("Metal Rendering"));
    currentRows.add(SettingRow.toggle(
        "Metal Rendering", config.enableMetalRendering ? "Enabled" : "Disabled",
        () -> config.enableMetalRendering = !config.enableMetalRendering));
    currentRows.add(SettingRow.toggle(
        "Simple Lighting", config.enableSimpleLighting ? "Enabled" : "Disabled",
        () -> config.enableSimpleLighting = !config.enableSimpleLighting));
    currentRows.add(SettingRow.toggle(
        "Debug Overlay", config.enableDebugOverlay ? "Enabled" : "Disabled",
        () -> config.enableDebugOverlay = !config.enableDebugOverlay));

    currentRows.add(SettingRow.header("Status"));
    currentRows.add(
        SettingRow.info("GPU", MetalHardwareChecker.getDeviceName()));
    currentRows.add(
        SettingRow.info("Metal Available",
                        MetalRenderClient.isMetalAvailable() ? "Yes" : "No"));
    currentRows.add(SettingRow.info("Sodium", MetalRenderClient.isSodiumLoaded()
                                                  ? "isInstaled"
                                                  : "isNotInstalled"));
    currentRows.add(SettingRow.info(
        "Apple Silicon", MetalHardwareChecker.isAppleSilicon() ? "Yes" : "No"));
  }

  private void buildQualityPage() {
    currentRows.add(SettingRow.header("LOD Settings"));
    zone1Row = currentRows.size();
    currentRows.add(SettingRow.info("Zone 1 Radius", ""));
    zone2Row = currentRows.size();
    currentRows.add(SettingRow.info("Zone 2 Radius", ""));
    transitionRow = currentRows.size();
    currentRows.add(SettingRow.info("LOD Transition", ""));
    currentRows.add(SettingRow.toggle(
        "Zone 2 LOD", config.enableZone2Lod ? "Enabled" : "Disabled",
        () -> config.enableZone2Lod = !config.enableZone2Lod));
    biomeDetailRow = currentRows.size();
    currentRows.add(SettingRow.info("Biome Transition Detail", ""));

    currentRows.add(SettingRow.header("Culling"));
    currentRows.add(SettingRow.cycle(
        "Leaves Mode", leafCullingModeName(config.leafCullingMode),
        ()
            -> config.leafCullingMode =
                   cycleValue(config.leafCullingMode, 0, 1, 1)));
  }

  private void buildPerformancePage() {
    currentRows.add(SettingRow.header("Frame Pacing"));
    targetFpsRow = currentRows.size();
    currentRows.add(SettingRow.info("Target FPS", ""));
    currentRows.add(SettingRow.toggle(
        "Triple Buffering", config.enableTripleBuffering ? "yes" : "no",
        () -> config.enableTripleBuffering = !config.enableTripleBuffering));

    currentRows.add(SettingRow.header("Memory"));
    maxMemoryRow = currentRows.size();
    currentRows.add(SettingRow.info("Max GPU Memory", ""));
    currentRows.add(
        SettingRow.toggle("Memory Pressure Fallback",
                          config.enableMemoryPressureFallback ? "yes" : "no",
                          ()
                              -> config.enableMemoryPressureFallback =
                                     !config.enableMemoryPressureFallback));

    currentRows.add(SettingRow.header("Runtime Info"));
    Runtime rt = Runtime.getRuntime();
    long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long maxMB = rt.maxMemory() / (1024 * 1024);
    currentRows.add(
        SettingRow.info("Heap Usage", usedMB + " / " + maxMB + " MB"));
  }

  private void buildAdvancedPage() {
    currentRows.add(SettingRow.header("Metal Features"));
    currentRows.add(SettingRow.toggle(
        "Mesh Shaders", config.enableMeshShaders ? "yes" : "no",
        () -> config.enableMeshShaders = !config.enableMeshShaders));
    currentRows.add(SettingRow.toggle(
        "Argument Buffers", config.enableArgumentBuffers ? "yes" : "no",
        () -> config.enableArgumentBuffers = !config.enableArgumentBuffers));
    currentRows.add(
        SettingRow.toggle("Programmable Blending",
                          config.enableProgrammableBlending ? "yes" : "no",
                          ()
                              -> config.enableProgrammableBlending =
                                     !config.enableProgrammableBlending));
    currentRows.add(
        SettingRow.toggle("Indirect CMD Buffers",
                          config.enableIndirectCommandBuffers ? "yes" : "no",
                          ()
                              -> config.enableIndirectCommandBuffers =
                                     !config.enableIndirectCommandBuffers));
    currentRows.add(SettingRow.toggle(
        "Memoryless Targets", config.enableMemorylessTargets ? "yes" : "no",
        ()
            -> config.enableMemorylessTargets =
                   !config.enableMemorylessTargets));

    currentRows.add(SettingRow.header("Hardware Capabilities"));
    currentRows.add(SettingRow.info("Mesh Shader HW",
                                    MetalHardwareChecker.supportsMeshShaders()
                                        ? "Supported"
                                        : "Not Available"));
    currentRows.add(SettingRow.info(
        "Apple Silicon", MetalHardwareChecker.isAppleSilicon() ? "Yes" : "No"));
    currentRows.add(
        SettingRow.info("GPU", MetalHardwareChecker.getDeviceName()));
  }

  private void buildLodPage() {
    currentRows.add(SettingRow.header("Level of Detail"));
    currentRows.add(
        SettingRow.toggle("LOD System", pendingLodEnabled ? "yes" : "no",
                          () -> { pendingLodEnabled = !pendingLodEnabled; }));
    currentRows.add(
        SettingRow.info("LOD 0", "Full detail (always near player)"));

    currentRows.add(SettingRow.header("LOD Distances (chunks)"));
    lod1DistanceRow = currentRows.size();
    currentRows.add(SettingRow.info("LOD 1 Distance",
                                    pendingLod1Distance +
                                        " chunks — skip non-full blocks"));
    lod2DistanceRow = currentRows.size();
    currentRows.add(
        SettingRow.info("LOD 2 Distance",
                        pendingLod2Distance + " chunks — skip decorations"));
    lod3DistanceRow = currentRows.size();
    currentRows.add(
        SettingRow.info("LOD 3 Distance",
                        pendingLod3Distance + " chunks — skip small blocks"));
    lod4DistanceRow = currentRows.size();
    currentRows.add(SettingRow.info(
        "LOD 4 Distance", pendingLod4Distance + " chunks — full cubes only"));

    currentRows.add(SettingRow.header("LOD Level Descriptions"));
    currentRows.add(SettingRow.info("LOD 0", "All blocks rendered"));
    currentRows.add(
        SettingRow.info("LOD 1", "Remove grass, flowers, non-full blocks"));
    currentRows.add(
        SettingRow.info("LOD 2", "Also remove fences, walls, pressure plates"));
    currentRows.add(
        SettingRow.info("LOD 3", "Also remove slabs, stairs, trapdoors"));
    currentRows.add(SettingRow.info("LOD 4", "Only render full cube blocks"));
  }

  private void addVanillaOption(String name, SimpleOption<?> option) {
    currentRows.add(
        SettingRow.vanilla(name, formatVanillaValue(option), option));
  }

  private String formatVanillaValue(SimpleOption<?> option) {
    Object val = option.getValue();
    if (val instanceof Boolean b)
      return b ? "ON" : "OFF";
    if (val instanceof Integer i)
      return String.valueOf(i);
    if (val instanceof Double d) {
      if (d == (int)(double)d)
        return String.valueOf((int)(double)d);
      return String.format("%.1f", d);
    }
    return val.toString();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void cycleVanillaOption(SimpleOption option) {

    Object val = option.getValue();
    if (val instanceof Boolean b) {
      option.setValue(!b);
      return;
    }
  }

  private int cycleValue(int current, int min, int max, int step) {
    int next = current + step;
    return next > max ? min : next;
  }

  private float cycleFloat(float current, float min, float max, float step) {
    float next = current + step;
    return next > max + 0.001f ? min : Math.round(next * 100f) / 100f;
  }

  private String leafCullingModeName(int mode) {
    return switch (mode) {
      case 0 -> "Fast";
      case 1 -> "Fancy";
      default -> "Unknown";
    };
  }

  private void drawSlightRoundedRect(DrawContext ctx, int x1, int y1, int x2,
                                     int y2, int color) {
    ctx.fill(x1 + 3, y1, x2 - 3, y2, color);
    ctx.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, color);
    ctx.fill(x1, y1 + 3, x2, y2 - 3, color);
  }

  private enum RowType { HEADER, TOGGLE, CYCLE, INFO, VANILLA_OPTION }

  private static class SettingRow {
    final RowType type;
    final String label;
    final String value;
    final Runnable action;
    final SimpleOption<?> vanillaOption;

    SettingRow(RowType type, String label, String value, Runnable action,
               SimpleOption<?> vanillaOption) {
      this.type = type;
      this.label = label;
      this.value = value;
      this.action = action;
      this.vanillaOption = vanillaOption;
    }

    static SettingRow header(String label) {
      return new SettingRow(RowType.HEADER, label, "", null, null);
    }

    static SettingRow toggle(String label, String value, Runnable action) {
      return new SettingRow(RowType.TOGGLE, label, value, action, null);
    }

    static SettingRow cycle(String label, String value, Runnable action) {
      return new SettingRow(RowType.CYCLE, label, value, action, null);
    }

    static SettingRow info(String label, String value) {
      return new SettingRow(RowType.INFO, label, value, null, null);
    }

    static SettingRow vanilla(String label, String value,
                              SimpleOption<?> option) {
      return new SettingRow(RowType.VANILLA_OPTION, label, value, null, option);
    }
  }
}
