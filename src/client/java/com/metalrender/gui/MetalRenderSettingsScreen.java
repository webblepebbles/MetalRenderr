package com.metalrender.gui;

import com.metalrender.config.MetalRenderConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;

public class MetalRenderSettingsScreen extends Screen {
    private final MetalRenderConfig config;

    public MetalRenderSettingsScreen() {
        super(Text.literal("MetalRender Settings"));
        this.config = MetalRenderConfig.get();
    }

    @Override
    protected void init() {
        int y = this.height / 2 - 60;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Shaders: " + (config.enableShaders ? "ON" : "OFF")), b -> {
            config.enableShaders = !config.enableShaders;
            config.save();
            this.recreate();
        }).dimensions(this.width / 2 - 100, y, 200, 20).build());
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Simple Lighting: " + (config.enableSimpleLighting ? "ON" : "OFF")), b -> {
            config.enableSimpleLighting = !config.enableSimpleLighting;
            config.save();
            this.recreate();
        }).dimensions(this.width / 2 - 100, y, 200, 20).build());
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Iris Shaders: " + (config.enableIrisShaders ? "ON" : "OFF")), b -> {
            config.enableIrisShaders = !config.enableIrisShaders;
            config.save();
            this.recreate();
        }).dimensions(this.width / 2 - 100, y, 200, 20).build());
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Debug Overlay: " + (config.enableDebugOverlay ? "ON" : "OFF")), b -> {
            config.enableDebugOverlay = !config.enableDebugOverlay;
            config.save();
            this.recreate();
        }).dimensions(this.width / 2 - 100, y, 200, 20).build());
        y += 40;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> {
            MinecraftClient.getInstance().setScreen(null);
        }).dimensions(this.width / 2 - 100, y, 200, 20).build());
    }

    private void recreate() {
        MinecraftClient.getInstance().setScreen(new MetalRenderSettingsScreen());
    }
}
