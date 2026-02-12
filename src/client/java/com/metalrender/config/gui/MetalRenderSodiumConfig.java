package com.metalrender.config.gui;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.config.MetalRenderConfigManager;
import com.metalrender.performance.RenderDistanceManager;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class MetalRenderSodiumConfig implements ConfigEntryPoint {

        private static final StorageEventHandler SAVE_HANDLER = () -> {
                MetalRenderConfigManager.syncFromRuntime(true);
                MetalRenderConfigManager.saveIfDirty();
        };

        private static final ControlValueFormatter BLOCKS_FORMATTER = v -> Text.literal(v + " blocks");
        private static final ControlValueFormatter CHUNKS_FORMATTER = v -> Text.literal(v + " chunks");
        private static final ControlValueFormatter PERCENT_FORMATTER = v -> Text.literal(v + "%");
        private static final ControlValueFormatter FPS_FORMATTER = v -> Text.literal(v + " FPS");

        @Override
        public void registerConfigLate(ConfigBuilder builder) {
                ModOptionsBuilder mod = builder.registerModOptions("metalrender", "MetalRender", "0.1.7");

                OptionPageBuilder generalPage = builder.createOptionPage();
                generalPage.setName(Text.literal("General"));

                OptionGroupBuilder mainGroup = builder.createOptionGroup();
                mainGroup.setName(Text.literal("Core"));

                BooleanOptionBuilder enabledOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "enabled"));
                enabledOpt.setName(Text.literal("MetalRender Enabled"));
                enabledOpt.setTooltip(Text.literal("Enable or disable the Metal rendering backend. Requires restart."));
                enabledOpt.setBinding(
                                v -> {
                                        MetalRenderConfig.setMetalRenderEnabled(v);
                                        MetalRenderClient.refreshEnabledState();
                                },
                                MetalRenderConfig::metalRenderEnabled);
                enabledOpt.setDefaultValue(true);
                enabledOpt.setImpact(OptionImpact.HIGH);
                enabledOpt.setStorageHandler(SAVE_HANDLER);
                mainGroup.addOption(enabledOpt);

                BooleanOptionBuilder lodOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "lod_enabled"));
                lodOpt.setName(Text.literal("LOD System"));
                lodOpt.setTooltip(
                                Text.literal("Enable distance-based Level of Detail. Distant chunks use simplified shaders."));
                lodOpt.setBinding(
                                MetalRenderConfig::setDistanceLodEnabled,
                                MetalRenderConfig::distanceLodEnabled);
                lodOpt.setDefaultValue(true);
                lodOpt.setImpact(OptionImpact.HIGH);
                lodOpt.setStorageHandler(SAVE_HANDLER);
                mainGroup.addOption(lodOpt);

                BooleanOptionBuilder frustumOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "frustum_cull"));
                frustumOpt.setName(Text.literal("Aggressive Frustum Culling"));
                frustumOpt.setTooltip(Text.literal("Tighter frustum culling to skip chunks outside the camera view."));
                frustumOpt.setBinding(
                                MetalRenderConfig::setAggressiveFrustumCulling,
                                MetalRenderConfig::aggressiveFrustumCulling);
                frustumOpt.setDefaultValue(true);
                frustumOpt.setImpact(OptionImpact.MEDIUM);
                frustumOpt.setStorageHandler(SAVE_HANDLER);
                mainGroup.addOption(frustumOpt);

                BooleanOptionBuilder occlusionOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "occlusion_cull"));
                occlusionOpt.setName(Text.literal("Occlusion Culling"));
                occlusionOpt.setTooltip(
                                Text.literal("Use Hi-Z depth buffer to skip chunks hidden behind other terrain."));
                occlusionOpt.setBinding(
                                MetalRenderConfig::setOcclusionCulling,
                                MetalRenderConfig::occlusionCulling);
                occlusionOpt.setDefaultValue(true);
                occlusionOpt.setImpact(OptionImpact.HIGH);
                occlusionOpt.setStorageHandler(SAVE_HANDLER);
                mainGroup.addOption(occlusionOpt);

                generalPage.addOptionGroup(mainGroup);

                OptionGroupBuilder distGroup = builder.createOptionGroup();
                distGroup.setName(Text.literal("Render Distance"));

                IntegerOptionBuilder renderDistOpt = builder.createIntegerOption(
                                Identifier.of("metalrender", "render_distance"));
                renderDistOpt.setName(Text.literal("Render Distance"));
                renderDistOpt.setTooltip(Text
                                .literal(
                                                "Maximum chunk render distance. Higher values show more terrain but need more GPU power. Chunks past 16 use LOD."));
                renderDistOpt.setRange(new Range(2, 256, 1));
                renderDistOpt.setValueFormatter(CHUNKS_FORMATTER);
                renderDistOpt.setBinding(
                                v -> {
                                        try {
                                                int clamped = Math.max(2, Math.min(256, v));
                                                MetalRenderConfig.setExtremeRenderDistance(clamped);
                                                MinecraftClient mc = MinecraftClient.getInstance();
                                                if (mc != null && mc.options != null) {
                                                        mc.options.getViewDistance().setValue(clamped);
                                                }
                                        } catch (Throwable ignored) {
                                        }
                                },
                                () -> MetalRenderConfig.extremeRenderDistance());
                renderDistOpt.setDefaultValue(16);
                renderDistOpt.setImpact(OptionImpact.HIGH);
                renderDistOpt.setStorageHandler(SAVE_HANDLER);
                distGroup.addOption(renderDistOpt);

                IntegerOptionBuilder fogOpt = builder.createIntegerOption(
                                Identifier.of("metalrender", "fog_distance"));
                fogOpt.setName(Text.literal("Fog Distance"));
                fogOpt.setTooltip(
                                Text.literal("Distance at which fog fully obscures terrain. Higher = clearer view but more GPU work."));
                fogOpt.setRange(new Range(100, 4100, 50));
                fogOpt.setValueFormatter(BLOCKS_FORMATTER);
                fogOpt.setBinding(
                                v -> MetalRenderConfig.setFogEndDistance((float) v),
                                () -> (int) MetalRenderConfig.fogEndDistance());
                fogOpt.setDefaultValue(2050);
                fogOpt.setImpact(OptionImpact.LOW);
                fogOpt.setStorageHandler(SAVE_HANDLER);
                distGroup.addOption(fogOpt);

                generalPage.addOptionGroup(distGroup);
                mod.addPage(generalPage);

                OptionPageBuilder lodPage = builder.createOptionPage();
                lodPage.setName(Text.literal("LOD Distances"));

                OptionGroupBuilder lodDistGroup = builder.createOptionGroup();
                lodDistGroup.setName(Text.literal("LOD Distance Thresholds (blocks)"));

                lodDistGroup.addOption(createLodDistanceOption(builder, 0,
                                "Full Detail (LOD 0)", "Full texture + full block/sky lighting.",
                                16, 256, 8, 64,
                                MetalRenderConfig::setLodLevel0Distance,
                                MetalRenderConfig::lodLevel0Distance));

                lodDistGroup.addOption(createLodDistanceOption(builder, 1,
                                "Reduced Detail (LOD 1)", "Full texture + full lighting, prepared for reduction.",
                                32, 512, 16, 128,
                                MetalRenderConfig::setLodLevel1Distance,
                                MetalRenderConfig::lodLevel1Distance));

                lodDistGroup.addOption(createLodDistanceOption(builder, 2,
                                "Medium Detail (LOD 2)", "Full texture + full lighting.",
                                64, 1024, 32, 256,
                                MetalRenderConfig::setLodLevel2Distance,
                                MetalRenderConfig::lodLevel2Distance));

                lodDistGroup.addOption(createLodDistanceOption(builder, 3,
                                "Low Detail (LOD 3)", "Full texture + simplified sky-only lighting. No block light.",
                                128, 2048, 32, 384,
                                MetalRenderConfig::setLodLevel3Distance,
                                MetalRenderConfig::lodLevel3Distance));

                lodDistGroup.addOption(createLodDistanceOption(builder, 4,
                                "Very Low (LOD 4)", "Full texture + sky-only lighting. No block light.",
                                256, 2048, 64, 512,
                                MetalRenderConfig::setLodLevel4Distance,
                                MetalRenderConfig::lodLevel4Distance));

                lodDistGroup.addOption(createLodDistanceOption(builder, 5,
                                "Ultra Distant (LOD 5)", "No texture sampling — palette colors only + sky lighting.",
                                512, 8192, 128, 4096,
                                MetalRenderConfig::setLodLevel5Distance,
                                MetalRenderConfig::lodLevel5Distance));

                lodPage.addOptionGroup(lodDistGroup);
                mod.addPage(lodPage);

                OptionPageBuilder qualityPage = builder.createOptionPage();
                qualityPage.setName(Text.literal("Quality"));

                OptionGroupBuilder qualGroup = builder.createOptionGroup();
                qualGroup.setName(Text.literal("Rendering Quality"));

                IntegerOptionBuilder resScaleOpt = builder.createIntegerOption(
                                Identifier.of("metalrender", "resolution_scale"));
                resScaleOpt.setName(Text.literal("Resolution Scale"));
                resScaleOpt.setTooltip(Text.literal(
                                "Internal rendering resolution as percentage of display resolution. Lower = faster but blurrier."));
                resScaleOpt.setRange(new Range(50, 150, 5));
                resScaleOpt.setValueFormatter(PERCENT_FORMATTER);
                resScaleOpt.setBinding(
                                v -> MetalRenderConfig.setResolutionScale(v / 100.0f),
                                () -> Math.round(MetalRenderConfig.resolutionScale() * 100));
                resScaleOpt.setDefaultValue(100);
                resScaleOpt.setImpact(OptionImpact.HIGH);
                resScaleOpt.setStorageHandler(SAVE_HANDLER);
                qualGroup.addOption(resScaleOpt);

                BooleanOptionBuilder metalFxOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "metalfx_spatial"));
                metalFxOpt.setName(Text.literal("MetalFX Spatial Upscaling"));
                metalFxOpt.setTooltip(Text.literal(
                                "Use Apple's MetalFX spatial upscaler to render at lower resolution and upscale. Improves FPS on high-res displays."));
                metalFxOpt.setBinding(
                                MetalRenderConfig::setMetalFXEnabled,
                                MetalRenderConfig::metalFXEnabled);
                metalFxOpt.setDefaultValue(false);
                metalFxOpt.setImpact(OptionImpact.HIGH);
                metalFxOpt.setStorageHandler(SAVE_HANDLER);
                qualGroup.addOption(metalFxOpt);

                qualityPage.addOptionGroup(qualGroup);
                mod.addPage(qualityPage);

                OptionPageBuilder perfPage = builder.createOptionPage();
                perfPage.setName(Text.literal("Performance"));

                OptionGroupBuilder perfGroup = builder.createOptionGroup();
                perfGroup.setName(Text.literal("Dynamic Performance"));

                BooleanOptionBuilder dynQualOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "dynamic_quality"));
                dynQualOpt.setName(Text.literal("Dynamic Quality"));
                dynQualOpt.setTooltip(Text.literal("Automatically adjust rendering quality to maintain target FPS."));
                dynQualOpt.setBinding(
                                MetalRenderConfig::setDynamicQuality,
                                MetalRenderConfig::dynamicQuality);
                dynQualOpt.setDefaultValue(true);
                dynQualOpt.setImpact(OptionImpact.VARIES);
                dynQualOpt.setStorageHandler(SAVE_HANDLER);
                perfGroup.addOption(dynQualOpt);

                BooleanOptionBuilder autoDistOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "auto_render_distance"));
                autoDistOpt.setName(Text.literal("Auto Render Distance"));
                autoDistOpt.setTooltip(Text.literal("Dynamically adjust render distance to maintain target FPS."));
                autoDistOpt.setBinding(
                                v -> {
                                        MetalRenderConfig.setAutoRenderDistanceEnabled(v);
                                        RenderDistanceManager.getInstance().setEnabled(v);
                                },
                                MetalRenderConfig::autoRenderDistanceEnabled);
                autoDistOpt.setDefaultValue(false);
                autoDistOpt.setImpact(OptionImpact.VARIES);
                autoDistOpt.setStorageHandler(SAVE_HANDLER);
                perfGroup.addOption(autoDistOpt);

                IntegerOptionBuilder targetFpsOpt = builder.createIntegerOption(
                                Identifier.of("metalrender", "target_fps"));
                targetFpsOpt.setName(Text.literal("Target FPS"));
                targetFpsOpt.setTooltip(Text.literal("Target framerate for dynamic quality and auto render distance."));
                targetFpsOpt.setRange(new Range(30, 240, 5));
                targetFpsOpt.setValueFormatter(FPS_FORMATTER);
                targetFpsOpt.setBinding(
                                v -> MetalRenderConfig.setAutoRenderDistanceTargetFps((float) v),
                                () -> Math.round(MetalRenderConfig.autoRenderDistanceTargetFps()));
                targetFpsOpt.setDefaultValue(60);
                targetFpsOpt.setImpact(OptionImpact.VARIES);
                targetFpsOpt.setStorageHandler(SAVE_HANDLER);
                perfGroup.addOption(targetFpsOpt);

                BooleanOptionBuilder multiIcbOpt = builder.createBooleanOption(
                                Identifier.of("metalrender", "multi_icb"));
                multiIcbOpt.setName(Text.literal("GPU LOD Pipelines"));
                multiIcbOpt.setTooltip(
                                Text.literal("Use LOD-specialized shader pipelines. LOD 3+ skips block light, LOD 5 skips textures."));
                multiIcbOpt.setBinding(
                                MetalRenderConfig::setMultiICBEnabled,
                                MetalRenderConfig::multiICBEnabled);
                multiIcbOpt.setDefaultValue(true);
                multiIcbOpt.setImpact(OptionImpact.HIGH);
                multiIcbOpt.setStorageHandler(SAVE_HANDLER);
                perfGroup.addOption(multiIcbOpt);

                perfPage.addOptionGroup(perfGroup);
                mod.addPage(perfPage);
        }

        private IntegerOptionBuilder createLodDistanceOption(
                        ConfigBuilder builder, int lodLevel,
                        String name, String tooltip,
                        int min, int max, int step, int defaultVal,
                        java.util.function.Consumer<Integer> setter,
                        java.util.function.Supplier<Integer> getter) {

                IntegerOptionBuilder opt = builder.createIntegerOption(
                                Identifier.of("metalrender", "lod_" + lodLevel + "_distance"));
                opt.setName(Text.literal(name));
                opt.setTooltip(Text.literal(tooltip));
                opt.setRange(new Range(min, max, step));
                opt.setValueFormatter(BLOCKS_FORMATTER);
                opt.setBinding(setter, getter);
                opt.setDefaultValue(defaultVal);
                opt.setImpact(lodLevel >= 3 ? OptionImpact.HIGH : OptionImpact.MEDIUM);
                opt.setStorageHandler(SAVE_HANDLER);
                return opt;
        }
}
