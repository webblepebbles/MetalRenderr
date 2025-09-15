package com.metalrender.config;

import com.google.gson.Gson;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MetalRenderConfig {
    private static final String CONFIG_PATH = "src/main/resources/metalrender-config.json";
    private static MetalRenderConfig instance;

    public boolean enableShaders = true;
    public boolean enableSimpleLighting = true;
    public boolean enableDebugOverlay = false;
    public boolean enableIrisShaders = false;

    public AdvancedMetalFeatures advancedMetalFeatures = new AdvancedMetalFeatures();

    private MetalRenderConfig() {}

    public static MetalRenderConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static MetalRenderConfig load() {
        try {
            String json = new String(Files.readAllBytes(Paths.get(CONFIG_PATH)));
            Gson gson = new Gson();
            return gson.fromJson(json, MetalRenderConfig.class);
        } catch (Exception e) {
            return new MetalRenderConfig();
        }
    }

    public void save() {
        try (Writer writer = new FileWriter(CONFIG_PATH)) {
            Gson gson = new Gson();
            gson.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public AdvancedMetalFeatures getAdvancedMetalFeatures() {
        return advancedMetalFeatures;
    }
}